package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The vignette of the lens, corrected by hand: a gain in exposure that grows from [start] towards the
 * corners of the **whole frame**, as the lens drew them, and so it is the same wherever the crop falls.
 *
 * The distance is measured in the original image, with the aspect ratio kept so that the circle stays a
 * circle, and normalised so that the corner is 1. It adds `amount / 100 · MAX_EV · w²` stops, with `w`
 * rising from 0 at the midpoint's radius to 1 at the corner; a positive amount lightens the corners.
 *
 * It enters through the exposure (`TONE` and `LOCAL_BASE` add `lensEv`), in linear light, before the tone
 * adjustments: a correction of what the lens did to the light, not a look put over the result.
 */
internal object LensVignette {
    const val MAX_EV = 1.5f

    fun start(midpoint: Float): Float = 0.1f + 0.8f * (midpoint / 100f).coerceIn(0f, 1f)

    /** The stops to add at the point ([u], [v]) of the original image, whose width over height is [aspect]. */
    fun ev(amount: Float, midpoint: Float, u: Float, v: Float, aspect: Float): Float {
        if (amount == 0f) return 0f
        val dx = (u - 0.5f) * aspect
        val dy = v - 0.5f
        val radius = sqrt((dx * dx + dy * dy) / (0.25f * (aspect * aspect + 1f)))
        val weight = PhotoEffects.smoothstep(start(midpoint), 1f, radius)
        return amount / 100f * MAX_EV * weight * weight
    }

    /** The same for a pixel of the framed bitmap, through [geometry] when there is one. */
    fun evFramed(parameters: RenderParameters, geometry: FrameGeometry?, width: Int, height: Int, u: Float, v: Float, point: FloatArray): Float {
        if (!parameters.needsLensVignette) return 0f
        if (geometry == null) return ev(parameters.lensVignetteAmount, parameters.lensVignetteMidpoint, u, v, width.toFloat() / height.coerceAtLeast(1))
        geometry.imageFromFramed(u, v, point)
        if (point[0].isNaN() || point[1].isNaN()) return 0f
        return ev(parameters.lensVignetteAmount, parameters.lensVignetteMidpoint, point[0], point[1], geometry.imageAspect)
    }
}

/**
 * The defringe: the purple and green fringes that a lens leaves along high contrast edges, taken out.
 *
 * Three things have to be true of a pixel for it to lose colour: it is near an edge (the slope of the luminance
 * blurred with [BLUR_SIGMA] is steep there and falls away over a few pixels either side — it is the slope, and
 * not a difference of two blurs, which is zero exactly on the edge, where a fringe sits), its hue is the
 * fringe's (within a half-width that the Range slider sets, from 10 to 60 degrees), and it has some colour to
 * lose. A purple petal, or a green leaf, away from an edge is left as it is; where it meets one, its colour is
 * drawn towards its luminance by `amount · edge · hue`.
 *
 * The blur and the slope are taken at half resolution, where a fringe's width is a pixel or two.
 * `PhotoShaders.DEFRINGE` does the same arithmetic; `LensCorrectionTest` pins the constants.
 */
internal object Defringe {
    const val PURPLE_HUE = 285f
    const val GREEN_HUE = 115f
    /** In pixels of the full image; the blur itself is made at half resolution, at half this. */
    const val BLUR_SIGMA = 3f

    /** The slope, in luminance per half-resolution pixel, of a shallow edge and of a hard one. */
    const val EDGE_LOW = 0.02f
    const val EDGE_HIGH = 0.08f

    fun halfWidth(range: Float): Float = 10f + 50f * (range / 100f).coerceIn(0f, 1f)

    fun hue(red: Float, green: Float, blue: Float): Float {
        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))
        val spread = high - low
        if (spread < 1e-5f) return 0f
        val sector = when (high) {
            red -> ((green - blue) / spread).let { if (it < 0f) it + 6f else it }
            green -> (blue - red) / spread + 2f
            else -> (red - green) / spread + 4f
        }
        return sector * 60f
    }

    /** How much of a hue is the fringe's: 1 at the centre and 0 past the half-width. */
    fun membership(hue: Float, centre: Float, range: Float): Float {
        var distance = abs(hue - centre)
        if (distance > 180f) distance = 360f - distance
        val half = halfWidth(range)
        return 1f - PhotoEffects.smoothstep(half * 0.6f, half, distance)
    }

    fun edge(dx: Float, dy: Float): Float = PhotoEffects.smoothstep(EDGE_LOW, EDGE_HIGH, 0.5f * sqrt(dx * dx + dy * dy))

    /** The share of the colour that goes, from 0 to 1. */
    fun fringe(parameters: RenderParameters, red: Float, green: Float, blue: Float, edge: Float): Float {
        if (edge <= 0f) return 0f
        val high = max(red, max(green, blue))
        val saturation = if (high > 0f) (high - min(red, min(green, blue))) / high else 0f
        val colour = PhotoEffects.smoothstep(0.05f, 0.25f, saturation)
        if (colour <= 0f) return 0f
        val hue = hue(red, green, blue)
        val purple = parameters.lensPurpleAmount / 100f * membership(hue, PURPLE_HUE, parameters.lensPurpleRange)
        val green = parameters.lensGreenAmount / 100f * membership(hue, GREEN_HUE, parameters.lensGreenRange)
        return max(purple, green) * edge * colour
    }
}

/** The export's side of the defringe, built from the toned image before the pass writes over it. */
internal class DefringePass private constructor(
    private val parameters: RenderParameters,
    private val width: Int,
    private val height: Int,
    private val slopeX: FloatArray,
    private val slopeY: FloatArray,
    private val halfWidth: Int,
    private val halfHeight: Int,
) {
    fun applyRow(y: Int, row: FloatArray) {
        val v = (y + 0.5f) / height
        for (x in 0 until width) {
            val u = (x + 0.5f) / width
            val red = row[x * 4]
            val green = row[x * 4 + 1]
            val blue = row[x * 4 + 2]
            val edge = Defringe.edge(
                DetailPyramid.sampleBilinear(slopeX, halfWidth, halfHeight, u, v),
                DetailPyramid.sampleBilinear(slopeY, halfWidth, halfHeight, u, v),
            )
            val lost = Defringe.fringe(parameters, red, green, blue, edge)
            if (lost <= 0f) continue
            val luminance = PhotoEffects.luminance(red, green, blue)
            row[x * 4] = red + (luminance - red) * lost
            row[x * 4 + 1] = green + (luminance - green) * lost
            row[x * 4 + 2] = blue + (luminance - blue) * lost
        }
    }

    companion object {
        fun build(bitmap: PixelSurface, parameters: RenderParameters): DefringePass {
            val width = bitmap.width
            val height = bitmap.height
            val halfWidth = max(width / 2, 1)
            val halfHeight = max(height / 2, 1)
            val half = FloatArray(halfWidth * halfHeight)
            val top = FloatArray(width * 4)
            val bottom = FloatArray(width * 4)
            val scratch = IntArray(width)
            for (y in 0 until halfHeight) {
                val first = min(y * 2, height - 1)
                val second = min(first + 1, height - 1)
                bitmap.readRgbaRow(first, top, scratch)
                bitmap.readRgbaRow(second, bottom, scratch)
                for (x in 0 until halfWidth) {
                    val left = min(x * 2, width - 1)
                    val right = min(left + 1, width - 1)
                    half[y * halfWidth + x] = 0.25f * (
                        PhotoEffects.luminance(top[left * 4], top[left * 4 + 1], top[left * 4 + 2]) +
                            PhotoEffects.luminance(top[right * 4], top[right * 4 + 1], top[right * 4 + 2]) +
                            PhotoEffects.luminance(bottom[left * 4], bottom[left * 4 + 1], bottom[left * 4 + 2]) +
                            PhotoEffects.luminance(bottom[right * 4], bottom[right * 4 + 1], bottom[right * 4 + 2])
                        )
                }
            }
            DetailPyramid.blurInPlace(half, halfWidth, halfHeight, Defringe.BLUR_SIGMA / 2f)
            // The shader takes the slope from two samples of the blur a texel to each side; this is the same
            // number, and sampling it bilinearly gives what the sampler gives.
            val slopeX = FloatArray(halfWidth * halfHeight)
            val slopeY = FloatArray(halfWidth * halfHeight)
            for (y in 0 until halfHeight) for (x in 0 until halfWidth) {
                val at = y * halfWidth + x
                slopeX[at] = half[y * halfWidth + min(x + 1, halfWidth - 1)] - half[y * halfWidth + max(x - 1, 0)]
                slopeY[at] = half[min(y + 1, halfHeight - 1) * halfWidth + x] - half[max(y - 1, 0) * halfWidth + x]
            }
            return DefringePass(parameters, width, height, slopeX, slopeY, halfWidth, halfHeight)
        }
    }
}
