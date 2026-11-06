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
 * Shadows are left out because phones lift them locally, highlights because they clip.
 *
 * How far the answer may go depends on whether a single exposure can repeat the JPEG at all. When the
 * tones agree it can, and the bound is [STEADY_LIMIT]: an X-T30 RAF asked for +1.9 EV and another for
 * −1.1, their tones' answers within 0.15 of each other. When they disagree the JPEG has been tone-mapped
 * locally, and the bound is [LIMIT]: a phone's backlit window asked for +1.7 EV with answers 0.5 apart.
 *
 * A dark photo may have too few mid-tones to read: an X-T30 at ISO 4000 had three quantiles above [LOW].
 * Then the shadows down to [DARK_LOW] are read too, and believed only if the tones agree. Across 36 RAWs,
 * every one whose answers agreed that way landed within 0.05 EV of what its mid-tones alone said.
 */
object CameraExposureMatch {
    const val LOW = 0.25f
    const val DARK_LOW = 0.10f
    const val HIGH = 0.85f
    const val LIMIT = 1f
    const val STEADY_LIMIT = 3f

    /**
     * The widest gap, in stops, between the 10th and 90th percentile of the tones' answers that still
     * counts as one exposure. Among 36 RAWs, those asking for about a stop or more were at 0.30 or less,
     * but for a phone's backlit window and a drone's scene, both above 0.4.
     */
    private const val STEADY_SPREAD = 0.35f

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
        var ratios = ratios(ours, theirs, LOW)
        val hasMidTones = ratios.size >= MIN_QUANTILES
        if (!hasMidTones) ratios = ratios(ours, theirs, DARK_LOW)
        if (ratios.size < MIN_QUANTILES) return 0f
        ratios.sort()
        val median = if (ratios.size % 2 == 1) ratios[ratios.size / 2] else (ratios[ratios.size / 2 - 1] + ratios[ratios.size / 2]) / 2f
        val spread = ratios[min(ratios.size - 1, (ratios.size * 0.9f).toInt())] - ratios[(ratios.size * 0.1f).toInt()]
        val steady = spread <= STEADY_SPREAD
        if (!hasMidTones && !steady) return 0f
        val limit = if (steady) STEADY_LIMIT else LIMIT
        return median.coerceIn(-limit, limit)
    }

    /** What each quantile the JPEG shows between [low] and [HIGH] asks for, in stops. */
    private fun ratios(ours: FloatArray, theirs: FloatArray, low: Float): ArrayList<Float> {
        val ratios = ArrayList<Float>(QUANTILES)
        for (k in 0 until QUANTILES) {
            val q = 0.05f + k * 0.01f
            val shown = quantile(theirs, q)
            val light = quantile(ours, q)
            if (shown <= low || shown >= HIGH || light <= 1e-5f) continue
            ratios += (ln(RawProfile.inverse(shown) / light) / LN2).toFloat()
        }
        return ratios
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
