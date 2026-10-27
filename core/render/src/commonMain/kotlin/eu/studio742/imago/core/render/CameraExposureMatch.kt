package eu.studio742.imago.core.render

import eu.studio742.imago.core.raw.RawDecoder
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * The exposure that makes a RAW's development as bright as the JPEG its camera stored in it.
 *
 * Every model places middle grey somewhere else on its sensor: measured against their own JPEGs,
 * cameras of seven makers ranged from −0.6 to +0.7 EV around [RawProfile]'s curve, the same model
 * within a few hundredths of a stop from photo to photo, and phones' DNGs further still (Samsung's
 * 12-bit ones a stop bright). The camera's JPEG carries that placement — and the photographer's
 * exposure compensation, which this keeps — so it is the reference, rather than a table per model.
 *
 * The two images are compared by their luminance quantiles, not pixel by pixel: the JPEG may be
 * rotated, cropped or corrected for the lens, and none of that moves a quantile much. Each quantile
 * where the JPEG is in the mid-tones, between [LOW] and [HIGH] on the screen, is taken back through the
 * profile's inverse to the light that would have shown it; the answer is the median of the ratios.
 * Shadows are left out because phones lift them locally, highlights because they clip. The result is
 * bounded by [LIMIT]: past a stop the JPEG has done something a single exposure cannot repeat — a
 * phone's local tone mapping on a backlit window asked for +1.7 EV.
 */
object CameraExposureMatch {
    const val LOW = 0.25f
    const val HIGH = 0.85f
    const val LIMIT = 1f

    /** Below this many usable quantiles out of [QUANTILES] the JPEG says too little: no correction. */
    private const val MIN_QUANTILES = 10
    private const val QUANTILES = 91
    private const val MAX_SAMPLES = 65_536

    /**
     * [developed] is the linear development as the profile receives it at Exposure 0 — as-shot white
     * balance, BaselineExposure applied; [camera] is the camera's JPEG. In stops.
     */
    fun estimate(developed: FloatPixelBuffer, camera: PixelBuffer): Float {
        val ours = sortedLuminance(developed.width * developed.height) { i ->
            val o = i * 3
            luminance(developed.rgb[o], developed.rgb[o + 1], developed.rgb[o + 2])
        }
        val theirs = sortedLuminance(camera.width * camera.height) { i ->
            val p = camera.pixels[i]
            val linear = luminance(srgbToLinear((p ushr 16 and 255) / 255f), srgbToLinear((p ushr 8 and 255) / 255f), srgbToLinear((p and 255) / 255f))
            encodeSrgb(linear)
        }
        val ratios = ArrayList<Float>(QUANTILES)
        for (k in 0 until QUANTILES) {
            val q = 0.05f + k * 0.01f
            val shown = quantile(theirs, q)
            val light = quantile(ours, q)
            if (shown <= LOW || shown >= HIGH || light <= 1e-5f) continue
            ratios += (ln(RawProfile.inverse(shown) / light) / LN2).toFloat()
        }
        if (ratios.size < MIN_QUANTILES) return 0f
        ratios.sort()
        val median = if (ratios.size % 2 == 1) ratios[ratios.size / 2] else (ratios[ratios.size / 2 - 1] + ratios[ratios.size / 2]) / 2f
        return median.coerceIn(-LIMIT, LIMIT)
    }

    /**
     * Reads the camera's JPEG from [original] and compares it with [developed]. 0 when the file has no
     * JPEG or it cannot be read: the development is then the profile's alone.
     */
    suspend fun of(original: File, developed: FloatPixelBuffer): Float = runInterruptible(Dispatchers.IO) {
        val jpeg = RawDecoder.embeddedJpeg(original) ?: return@runInterruptible 0f
        val camera = decodePixelsReduced(jpeg, MATCH_SIDE) ?: return@runInterruptible 0f
        estimate(developed, camera)
    }

    /** Enough pixels for 91 quantiles to be steady, few enough to cost nothing beside the RAW. */
    private const val MATCH_SIDE = 512
    private val LN2 = ln(2.0)

    private inline fun sortedLuminance(count: Int, value: (Int) -> Float): FloatArray {
        val step = max(1, count / MAX_SAMPLES)
        val values = FloatArray((count + step - 1) / step) { value(min(it * step, count - 1)) }
        values.sort()
        return values
    }

    private fun quantile(sorted: FloatArray, q: Float): Float {
        val position = q * (sorted.size - 1)
        val below = position.toInt()
        val above = min(below + 1, sorted.size - 1)
        return sorted[below] + (sorted[above] - sorted[below]) * (position - below)
    }

    private fun luminance(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun srgbToLinear(value: Float): Float = BitmapPhotoProcessor.srgbToLinear(value)
}
