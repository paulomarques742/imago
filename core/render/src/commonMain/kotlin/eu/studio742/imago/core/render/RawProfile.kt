package eu.studio742.imago.core.render

import kotlin.math.max
import kotlin.math.min

/**
 * A RAW's base profile: how the camera's linear development reaches the screen, in place of the plain
 * sRGB transfer that process 19 used and that left a RAW darker and flatter than the camera's JPEG.
 *
 * The tone curve is T(L) = L (1 + c) / (L + c), with L linear and 1 the sensor's white: white stays
 * white, black stays black, and the slope eases off towards white instead of clipping. It was fitted
 * to the JPEGs that seventeen RAW files from seven makers (Canon, Fujifilm, Nikon, OM/Olympus,
 * Panasonic, Pentax, Sony) carry, against this app's development of the same files. Aligned on their
 * middle grey, the cameras' curves have the same shape — a logistic in stops whose exponent came out
 * at one, which is this curve — and put middle grey between 0.045 and 0.12 of the sensor's white.
 * [KNEE] puts 0.09 at display 0.49: the cameras' median is 0.07 and the phones' DNGs sit higher, so
 * a camera opens within about half a stop of its JPEG and a phone a little brighter than its own. The
 * hue-keeping tone leaves the saturation within 2 % of the cameras'.
 *
 * Above white the curve goes on as a straight line with its slope there, so what the sensor recorded
 * above white still reaches the later steps instead of being cut here.
 *
 * Colour is toned as the DNG reference does it: the curve on the largest and the smallest channel,
 * and the middle one placed between them where it was, which keeps the hue. Monochrome tones the
 * luminance. The preview's shader does the same arithmetic (`toDisplay` in `PhotoShaders`).
 */
object RawProfile {
    /** Process 19's rendering, and every JPEG: the sRGB transfer alone. */
    const val NONE = 0
    const val COLOR = 1
    const val MONOCHROME = 2

    /** Colour with less contrast, to start flat. */
    const val NEUTRAL = 3

    /** Colour with more contrast and a livelier colour. */
    const val VIVID = 4

    /**
     * What the two variants do after the base curve, IMAGO's values chosen on the samples: a contrast
     * `y = x + s·x(1 − x)(2x − 1)` on 0..1 — it keeps black, white and the middle, and rises for
     * |s| up to 2 — and for Vivid the chroma times [VIVID_SATURATION]. Light above white goes through.
     */
    private const val NEUTRAL_CONTRAST = -0.45f
    private const val VIVID_CONTRAST = 0.40f
    private const val VIVID_SATURATION = 1.2f

    const val KNEE = 0.1132f
    private const val WHITE_SLOPE = KNEE / (1f + KNEE)

    /**
     * Where the Highlights band of rendering 3 ends, as a display value: three stops above white
     * (eight times the sensor's white). The DNGs of the samples keep a channel up to about two to
     * three stops above white once the camera's white balance and BaselineExposure are on, and the
     * curve above white is so flat that this is still a single stop of display.
     */
    val HEADROOM_CEILING = 1f + WHITE_SLOPE * 7f

    fun curve(value: Float): Float {
        val linear = max(value, 0f)
        return if (linear > 1f) 1f + WHITE_SLOPE * (linear - 1f) else linear * (1f + KNEE) / (linear + KNEE)
    }

    /** The light [curve] shows as [value]. */
    fun inverse(value: Float): Float = when {
        value <= 0f -> 0f
        value >= 1f -> 1f + (value - 1f) / WHITE_SLOPE
        else -> KNEE * value / (1f + KNEE - value)
    }

    /** Linear [red], [green], [blue] to display values in [out], through [profile] ([COLOR] or [MONOCHROME]). */
    fun toDisplay(red: Float, green: Float, blue: Float, profile: Int, out: FloatArray) {
        val r = max(red, 0f)
        val g = max(green, 0f)
        val b = max(blue, 0f)
        if (profile == MONOCHROME) {
            val grey = curve(r * 0.2126f + g * 0.7152f + b * 0.0722f)
            out[0] = grey
            out[1] = grey
            out[2] = grey
            return
        }
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val top = curve(high)
        if (high - low < 1e-6f) {
            out[0] = top
            out[1] = top
            out[2] = top
        } else {
            val bottom = curve(low)
            val scale = (top - bottom) / (high - low)
            out[0] = bottom + (r - low) * scale
            out[1] = bottom + (g - low) * scale
            out[2] = bottom + (b - low) * scale
        }
        if (profile == NEUTRAL || profile == VIVID) shape(profile, out)
    }

    private fun contrast(value: Float, amount: Float): Float =
        if (value <= 0f || value >= 1f) value else value + amount * value * (1f - value) * (2f * value - 1f)

    private fun shape(profile: Int, out: FloatArray) {
        val amount = if (profile == NEUTRAL) NEUTRAL_CONTRAST else VIVID_CONTRAST
        for (channel in 0..2) out[channel] = contrast(out[channel], amount)
        if (profile == VIVID) {
            val grey = out[0] * 0.2126f + out[1] * 0.7152f + out[2] * 0.0722f
            for (channel in 0..2) out[channel] = max(grey + (out[channel] - grey) * VIVID_SATURATION, 0f)
        }
    }
}
