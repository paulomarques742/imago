package eu.studio742.imago.core.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A RAW's calibration: where its three primaries sit, and the tint of its shadows.
 *
 * Each primary is a colour of the working space — (1, 0, 0), (0, 1, 0), (0, 0, 1) — taken apart into
 * its grey and its chroma. The Hue slider turns the chroma around the grey axis, up to [HUE_DEGREES] to
 * either side; the Saturation slider scales it, between 1 - [SATURATION_SPAN] and 1 + [SATURATION_SPAN].
 * The matrix has the new primaries as its columns, and it is applied to the colour of a pixel *without its
 * grey*: `out = m + P·(c − m)` with `m` the smallest channel. A grey has nothing left to move, so a neutral goes
 * through untouched however far the primaries are moved; and a pure primary — `m = 0` — comes out as exactly
 * the new primary, so that moving the red leaves the green and the blue where they were, in hue and in strength.
 *
 * The tint multiplies the channels by `(1 + 0.06k, 1 - 0.12k, 1 + 0.06k)` with `k` the slider times a weight
 * that is 1 in black and 0 from a quarter of the scale up; positive is magenta, as the white balance's Tint.
 *
 * IMAGO's own values, chosen on the primaries of the samples. Done in the linear working space after the
 * white balance and before the exposure; the shader does the same (`calibrate` in `PhotoShaders`).
 */
internal object CalibrationModel {
    const val HUE_DEGREES = 40f
    const val SATURATION_SPAN = 0.6f

    /** The tint's reach: the luminance at which it has faded away. */
    const val TINT_REACH = 0.25f
    const val TINT_GREEN = 0.12f
    const val TINT_SIDES = 0.06f

    /** Row-major, or null when no primary has been moved. */
    fun matrix(p: RenderParameters): FloatArray? {
        val hues = floatArrayOf(p.calibrationRedHue, p.calibrationGreenHue, p.calibrationBlueHue)
        val saturations = floatArrayOf(p.calibrationRedSaturation, p.calibrationGreenSaturation, p.calibrationBlueSaturation)
        if (hues.all { it == 0f } && saturations.all { it == 0f }) return null
        val primary = Array(3) { FloatArray(3) }
        for (index in 0..2) {
            val angle = Math.toRadians((hues[index] / 100f * HUE_DEGREES).toDouble())
            val scale = 1f + saturations[index] / 100f * SATURATION_SPAN
            val moved = rotate(index, cos(angle).toFloat(), sin(angle).toFloat(), scale)
            for (row in 0..2) primary[index][row] = moved[row]
        }
        // P has the new primaries as columns: P[row][column] = primary[column][row].
        return FloatArray(9) { primary[it % 3][it / 3] }
    }

    /** The primary number [index], its chroma turned by (cos, sin) about the grey axis and scaled. */
    private fun rotate(index: Int, cosine: Float, sine: Float, scale: Float): FloatArray {
        val axis = 1f / sqrt(3f)
        val v = FloatArray(3).also { it[index] = 1f }
        val grey = (v[0] + v[1] + v[2]) * axis
        val chroma = floatArrayOf(v[0] - grey * axis, v[1] - grey * axis, v[2] - grey * axis)
        // axis × chroma, with the axis (a, a, a)
        val cross = floatArrayOf(axis * (chroma[2] - chroma[1]), axis * (chroma[0] - chroma[2]), axis * (chroma[1] - chroma[0]))
        return FloatArray(3) { grey * axis + scale * (chroma[it] * cosine + cross[it] * sine) }
    }

    /** The calibration of one pixel of linear colour, in place. */
    fun apply(rgb: FloatArray, matrix: FloatArray?, shadowsTint: Float) {
        if (matrix != null) {
            val grey = minOf(rgb[0], minOf(rgb[1], rgb[2]))
            val r = rgb[0] - grey
            val g = rgb[1] - grey
            val b = rgb[2] - grey
            rgb[0] = maxOf(grey + matrix[0] * r + matrix[1] * g + matrix[2] * b, 0f)
            rgb[1] = maxOf(grey + matrix[3] * r + matrix[4] * g + matrix[5] * b, 0f)
            rgb[2] = maxOf(grey + matrix[6] * r + matrix[7] * g + matrix[8] * b, 0f)
        }
        if (shadowsTint != 0f) {
            val weight = 1f - PhotoEffects.smoothstep(0f, TINT_REACH, PhotoEffects.luminance(rgb[0], rgb[1], rgb[2]))
            val k = shadowsTint / 100f * weight
            rgb[0] *= 1f + TINT_SIDES * k
            rgb[1] *= 1f - TINT_GREEN * k
            rgb[2] *= 1f + TINT_SIDES * k
        }
    }
}
