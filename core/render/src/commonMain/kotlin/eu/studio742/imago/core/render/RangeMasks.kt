package eu.studio742.imago.core.render

import java.util.stream.IntStream
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import eu.studio742.imago.core.model.ColorRange
import eu.studio742.imago.core.model.ColorSample
import eu.studio742.imago.core.model.LuminanceRange
import eu.studio742.imago.core.model.MAX_COLOR_SAMPLES
import eu.studio742.imago.core.model.MaskShape

/**
 * Oklab, from gamma-encoded sRGB.
 *
 * A range mask needs a space where a distance means what the eye sees: in RGB two blues a person calls
 * the same are further apart than a blue and a grey. Oklab (Björn Ottosson, 2020) is that, at the cost
 * of a matrix and three cube roots. Its L is also the lightness the Luminance Range reads, so both
 * ranges agree on what bright is.
 */
internal object Oklab {
    /** [r], [g] and [b] from 0 to 1, gamma-encoded; writes L (0 to 1), a and b into [out]. */
    fun fromSrgb(r: Float, g: Float, b: Float, out: FloatArray) {
        val lr = linear(r)
        val lg = linear(g)
        val lb = linear(b)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** The way back, for showing a sample's colour: gamma-encoded sRGB from 0 to 1, clamped, into [out]. */
    fun toSrgb(l: Float, a: Float, b: Float, out: FloatArray) {
        val lp = l + 0.3963377774f * a + 0.2158037573f * b
        val mp = l - 0.1055613458f * a - 0.0638541728f * b
        val sp = l - 0.0894841775f * a - 1.2914855480f * b
        val lc = lp * lp * lp
        val mc = mp * mp * mp
        val sc = sp * sp * sp
        out[0] = encode(4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc)
        out[1] = encode(-1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc)
        out[2] = encode(-0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc)
    }

    private fun encode(linear: Float): Float {
        val c = linear.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    private fun linear(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
}

/**
 * Lightroom's range masks: Luminance Range and Color Range.
 *
 * A range is painted into a raster like a brush, from the original photo's colours the Auto Mask
 * already keeps ([BrushReference]), and reaches the weight field — and the GPU — as one more raster
 * layer. That is what lets the preview and the export draw the same selection: the shader samples the
 * numbers this object computes, and never decides a colour distance of its own.
 *
 * The tolerances here were not measured against Lightroom, which does not publish its own; they are in
 * one place so that they are tuned in one place.
 */
object RangeMasks {
    /** How far past its ends, in units of lightness (0 to 100), a range fades out at Smoothness 100. */
    const val MAX_LUMINANCE_FALLOFF = 30f

    /** Tapping a point selects this much lightness either side of it, as Lightroom's eyedropper does. */
    const val LUMINANCE_POINT_HALF_RANGE = 10f

    /**
     * The share of an area's pixels left out at each end when it sets a Luminance Range: a few specular
     * highlights in a dragged rectangle should not stretch the range to white.
     */
    const val LUMINANCE_AREA_TRIM = 0.05f

    /**
     * How much a difference in lightness counts against a Color Range, next to one in hue or chroma.
     * Under a half, the lit and shaded sides of the same blue sky are still the same colour.
     */
    const val COLOR_LIGHTNESS_WEIGHT = 0.5f

    /** The Oklab distance fully selected at Refine 0 and at Refine 100. */
    const val COLOR_NEAR_AT_ZERO = 0.01f
    const val COLOR_NEAR_AT_FULL = 0.15f

    /** Where the falloff ends, as a multiple of the fully selected distance. */
    const val COLOR_FAR_RATIO = 2f

    /** The weight of a pixel of this lightness (0 to 100) in a Luminance Range. */
    fun luminanceWeight(lightness: Float, range: LuminanceRange): Float {
        val low = min(range.low, range.high).coerceIn(0f, 100f)
        val high = max(range.low, range.high).coerceIn(0f, 100f)
        if (lightness in low..high) return 1f
        val falloff = range.smoothness.coerceIn(0f, 100f) / 100f * MAX_LUMINANCE_FALLOFF
        if (falloff <= 0f) return 0f
        val distance = if (lightness < low) low - lightness else lightness - high
        return 1f - smoothstep(distance / falloff)
    }

    /** The weight of a pixel of this Oklab colour in a Color Range: the closest sample decides. */
    fun colorWeight(l: Float, a: Float, b: Float, range: ColorRange): Float {
        val samples = range.samples
        if (samples.isEmpty()) return 0f
        val near = nearDistance(range.refine)
        val far = near * COLOR_FAR_RATIO
        var closest = Float.MAX_VALUE
        for (index in 0 until min(samples.size, MAX_COLOR_SAMPLES)) {
            val sample = samples[index]
            val distance = (colorDistance(l, a, b, sample.l, sample.a, sample.b) - sample.spread).coerceAtLeast(0f)
            if (distance < closest) closest = distance
        }
        if (closest <= near) return 1f
        return 1f - smoothstep((closest - near) / (far - near))
    }

    internal fun nearDistance(refine: Float): Float {
        val t = refine.coerceIn(0f, 100f) / 100f
        return COLOR_NEAR_AT_ZERO + (COLOR_NEAR_AT_FULL - COLOR_NEAR_AT_ZERO) * t
    }

    internal fun colorDistance(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val dl = (l1 - l2) * COLOR_LIGHTNESS_WEIGHT
        val da = a1 - a2
        val db = b1 - b2
        return sqrt(dl * dl + da * da + db * db)
    }

    /** A range component into [raster], which has [reference]'s size. Rows are independent. */
    internal fun paint(raster: BrushRaster, component: MaskRenderComponent, reference: BrushReference) {
        val lab = reference.lab
        val luminance = component.luminance
        val color = component.colorRange
        IntStream.range(0, raster.height).parallel().forEach { row ->
            for (column in 0 until raster.width) {
                val texel = row * raster.width + column
                val weight = when {
                    component.shape == MaskShape.LUMINANCE && luminance != null ->
                        luminanceWeight(lab[texel * 3] * 100f, luminance)
                    component.shape == MaskShape.COLOR && color != null ->
                        colorWeight(lab[texel * 3], lab[texel * 3 + 1], lab[texel * 3 + 2], color)
                    else -> 0f
                }
                raster.set(texel, weight)
            }
        }
        raster.markDirty(0, 0, raster.width, raster.height)
    }

    /**
     * A Color Range sample of the original photo: a point when [width] and [height] are zero, else the
     * area they span around [x], [y]. Coordinates are normalised to the original image.
     */
    fun sampleColor(reference: BrushReference, x: Float, y: Float, width: Float = 0f, height: Float = 0f): ColorSample {
        val texels = reference.texelsIn(x, y, width, height)
        val lab = reference.lab
        var l = 0.0
        var a = 0.0
        var b = 0.0
        for (texel in texels) {
            l += lab[texel * 3]
            a += lab[texel * 3 + 1]
            b += lab[texel * 3 + 2]
        }
        val count = texels.size.toDouble()
        val meanL = (l / count).toFloat()
        val meanA = (a / count).toFloat()
        val meanB = (b / count).toFloat()
        var squares = 0.0
        for (texel in texels) {
            val d = colorDistance(lab[texel * 3], lab[texel * 3 + 1], lab[texel * 3 + 2], meanL, meanA, meanB)
            squares += d * d
        }
        return ColorSample(
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            width = width.coerceAtLeast(0f),
            height = height.coerceAtLeast(0f),
            l = meanL,
            a = meanA,
            b = meanB,
            spread = sqrt(squares / count).toFloat(),
        )
    }

    /**
     * The Luminance Range an eyedropper sets: around the lightness of a point, or from the darkest to
     * the brightest of an area (trimmed of its extremes). [smoothness] is kept from the range before.
     */
    fun sampleLuminance(
        reference: BrushReference,
        x: Float,
        y: Float,
        width: Float = 0f,
        height: Float = 0f,
        smoothness: Float,
    ): LuminanceRange {
        val texels = reference.texelsIn(x, y, width, height)
        val lab = reference.lab
        val values = FloatArray(texels.size) { lab[texels[it] * 3] * 100f }
        val (low, high) = if (width <= 0f || height <= 0f || values.size == 1) {
            val centre = values.average().toFloat()
            centre - LUMINANCE_POINT_HALF_RANGE to centre + LUMINANCE_POINT_HALF_RANGE
        } else {
            values.sort()
            val cut = (values.size * LUMINANCE_AREA_TRIM).toInt()
            values[cut] to values[values.size - 1 - cut]
        }
        return LuminanceRange(
            low = (low.coerceIn(0f, 100f) * 10f).roundToInt() / 10f,
            high = (high.coerceIn(0f, 100f) * 10f).roundToInt() / 10f,
            smoothness = smoothness,
        )
    }

    /** A sample's colour as opaque ARGB, for the swatch that stands for it in the panel. */
    fun sampleArgb(sample: ColorSample): Int {
        val rgb = FloatArray(3)
        Oklab.toSrgb(sample.l, sample.a, sample.b, rgb)
        val r = (rgb[0] * 255f).roundToInt()
        val g = (rgb[1] * 255f).roundToInt()
        val b = (rgb[2] * 255f).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The lightness of every texel of [reference], 0 to 100: what the stage shows as the luminance map. */
    fun lightnessMap(reference: BrushReference): FloatArray {
        val lab = reference.lab
        return FloatArray(reference.width * reference.height) { lab[it * 3] * 100f }
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}

/**
 * The texels of this reference under a point, or inside an area centred on [x], [y]; never empty, so
 * a sample taken at the very edge still reads the texel there.
 */
internal fun BrushReference.texelsIn(x: Float, y: Float, width: Float, height: Float): IntArray {
    val cx = x.coerceIn(0f, 1f) * this.width
    val cy = y.coerceIn(0f, 1f) * this.height
    if (width <= 0f || height <= 0f) {
        val tx = floor(cx).toInt().coerceIn(0, this.width - 1)
        val ty = floor(cy).toInt().coerceIn(0, this.height - 1)
        return intArrayOf(ty * this.width + tx)
    }
    val left = floor(cx - width * this.width / 2f).toInt().coerceIn(0, this.width - 1)
    val right = floor(cx + width * this.width / 2f).toInt().coerceIn(left, this.width - 1)
    val top = floor(cy - height * this.height / 2f).toInt().coerceIn(0, this.height - 1)
    val bottom = floor(cy + height * this.height / 2f).toInt().coerceIn(top, this.height - 1)
    val out = IntArray((right - left + 1) * (bottom - top + 1))
    var index = 0
    for (ty in top..bottom) for (tx in left..right) out[index++] = ty * this.width + tx
    return out
}
