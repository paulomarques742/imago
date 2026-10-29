package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Step 11: sharpening, an unsharp mask on luminance with two controls of its own.
 *
 * The signal is the toned image (steps 1 to 8) minus the same image blurred with a Gaussian of
 * [Parameters.radius] pixels. It is read from there, and not from the output of step 9, so that
 * Texture and Clarity do not feed the sharpening and the two tools stay independent; what it makes
 * is a luminance delta that is added after step 9, before step 12.
 *
 *  - **Detail** is how much of the signal is allowed through at large amplitudes. The signal goes
 *    through `s / (1 + |s| / limit)`: it keeps the fine, small contrast of a texture and saturates on
 *    the strong edges, which is where the halos come from. [limit] runs from 0.04 at Detail 0 to 1 at
 *    Detail 100 (the limiter is then so loose it does nothing visible).
 *  - **Masking** weighs the signal by how steep the blurred image is at that pixel — an edge — so that
 *    flat areas, where the signal is mostly noise, are left alone. At 0 the weight is 1 everywhere.
 *
 * The numbers are IMAGO's, chosen on a photo and not measured on another editor. The preview does this
 * in the composite shader from a blurred copy of the stage and the export in [SharpenField] from the
 * same formulas; `PhotoShaderParityTest` pins the constants.
 */
internal object Sharpening {
    const val MIN_RADIUS = 0.5f
    const val MAX_RADIUS = 3f

    /** The Detail slider as the limit of the signal's amplitude. */
    fun limit(detail: Float): Float {
        val d = (detail / 100f).coerceIn(0f, 1f)
        return 0.04f + 0.96f * d * d
    }

    /** The gradient, in luminance per pixel, at which the mask lets an edge through completely. */
    fun edgeKnee(masking: Float): Float = 0.012f + 0.05f * (masking / 100f).coerceIn(0f, 1f)

    fun mask(gradient: Float, masking: Float): Float {
        val t = (masking / 100f).coerceIn(0f, 1f)
        if (t <= 0f) return 1f
        val edge = PhotoEffects.smoothstep(0f, edgeKnee(masking), gradient)
        return 1f + (edge - 1f) * t
    }

    /**
     * The luminance to add at one pixel.
     *
     * @param luminance the toned image's luminance here
     * @param blurred the same, blurred with the radius
     * @param gradient the length of the blurred image's gradient: half the central difference
     */
    fun delta(luminance: Float, blurred: Float, gradient: Float, amount: Float, detail: Float, masking: Float): Float {
        val signal = luminance - blurred
        val limited = signal / (1f + abs(signal) / limit(detail))
        return amount / 100f * limited * mask(gradient, masking)
    }
}

/**
 * The export's side of step 11: the delta of every pixel, computed from the toned image before the
 * finish pass overwrites it.
 *
 * It holds one float per pixel: the luminance, then its blur, then the delta, in the same array. The
 * luminance is read again from the image, which nothing has changed yet, and the blur of the row above
 * is kept aside before the delta writes over it. On a 26 Mpx RAW a second array was a hundred megabytes
 * the export did not have.
 */
internal class SharpenField private constructor(private val delta: FloatArray, private val width: Int) {
    fun at(x: Int, y: Int): Float = delta[y * width + x]

    companion object {
        fun build(bitmap: PixelSurface, parameters: RenderParameters): SharpenField {
            val width = bitmap.width
            val height = bitmap.height
            val field = FloatArray(width * height)
            val pixels = FloatArray(width * 4)
            val scratch = IntArray(width)
            for (y in 0 until height) {
                bitmap.readRgbaRow(y, pixels, scratch)
                val base = y * width
                for (x in 0 until width) {
                    field[base + x] = PhotoEffects.luminance(pixels[x * 4], pixels[x * 4 + 1], pixels[x * 4 + 2])
                }
            }
            DetailPyramid.blurInPlace(field, width, height, parameters.sharpenRadius)

            var above = FloatArray(width)
            var here = FloatArray(width)
            for (y in 0 until height) {
                val base = y * width
                field.copyInto(here, 0, base, base + width)
                if (y == 0) here.copyInto(above)
                val below = min(y + 1, height - 1) * width
                bitmap.readRgbaRow(y, pixels, scratch)
                for (x in 0 until width) {
                    val dx = here[min(x + 1, width - 1)] - here[max(x - 1, 0)]
                    val dy = field[below + x] - above[x]
                    val gradient = 0.5f * sqrt(dx * dx + dy * dy)
                    field[base + x] = Sharpening.delta(
                        luminance = PhotoEffects.luminance(pixels[x * 4], pixels[x * 4 + 1], pixels[x * 4 + 2]),
                        blurred = here[x],
                        gradient = gradient,
                        amount = parameters.sharpenAmount,
                        detail = parameters.sharpenDetail,
                        masking = parameters.sharpenMasking,
                    )
                }
                val written = above
                above = here
                here = written
            }
            return SharpenField(field, width)
        }
    }
}
