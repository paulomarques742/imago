package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Process 26 reads [EditRecipe.lut]; an app before it would draw the photo without it, and asks to be updated. */
const val LUT_PROCESS_VERSION = 26

/**
 * The largest grid a recipe keeps. 33 is what the common `.cube` files for photography use, and it is
 * 33³ × 3 × 2 = 215 622 bytes — about 290 KB as text, well inside the 1 MB a synced recipe may weigh.
 * A bigger table is resampled down on import ([ColorLut.fromSamples] callers do it), never stored.
 */
const val MAX_LUT_SIZE = 33
const val MIN_LUT_SIZE = 2

/** A name longer than this is cut when a LUT is made: it is shown, and travels with every recipe. */
const val MAX_LUT_NAME = 80

/**
 * A 3D colour lookup table: a look that is data, not a formula. It maps a display-referred sRGB colour
 * (0..1 per channel, after the tone, the colour and the grading) to another, and [amount] says how far
 * towards it the photo goes.
 *
 * [table] is the grid, [size]³ points of three channels each, as 16-bit unsigned integers in
 * little-endian order, base64 encoded. The red index changes fastest and the blue slowest — the order
 * of a `.cube` file — and the grid covers 0..1 with its first and last point on the ends. 16 bits and
 * not 8, so that a smooth look does not band when it is stretched.
 *
 * It lives inside the recipe, like the PNG of a mask found by a model: no second thing to sync, and a
 * recipe that is shared, exported or restored brings its look with it.
 */
@Serializable
data class ColorLut(
    val name: String,
    val size: Int,
    val table: String,
    /** 0..100: how much of the look is applied, linear in the colour. */
    val amount: Float = 100f,
) {
    /**
     * The grid as floats in 0..1, three per point, or null if [table] is not [size]³ points — a recipe
     * written by something else, or damaged. A table that cannot be read draws as no table at all.
     */
    fun samples(): FloatArray? {
        if (size !in MIN_LUT_SIZE..MAX_LUT_SIZE) return null
        val points = size * size * size
        val bytes = runCatching { Base64.getDecoder().decode(table) }.getOrNull() ?: return null
        if (bytes.size != points * 3 * 2) return null
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(points * 3) { (shorts.get(it).toInt() and 0xFFFF) / 65535f }
    }

    /** The share of the look that is applied, as 0..1. */
    val strength: Float get() = (amount / 100f).coerceIn(0f, 1f)

    /** True when this LUT changes nothing: no amount, or a table that is not readable. */
    val isInert: Boolean get() = strength == 0f || samples() == null

    companion object {
        /**
         * A LUT from its grid: [samples] holds [size]³ points of three floats, red fastest, and values
         * outside 0..1 are clamped, as the screen would.
         */
        fun fromSamples(name: String, size: Int, samples: FloatArray, amount: Float = 100f): ColorLut {
            require(size in MIN_LUT_SIZE..MAX_LUT_SIZE) { "LUT size $size is outside $MIN_LUT_SIZE..$MAX_LUT_SIZE" }
            require(samples.size == size * size * size * 3) { "LUT has ${samples.size} values, not ${size * size * size * 3}" }
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (value in samples) {
                buffer.putShort((value.coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort())
            }
            return ColorLut(
                name = name.trim().take(MAX_LUT_NAME),
                size = size,
                table = Base64.getEncoder().encodeToString(buffer.array()),
                amount = amount,
            )
        }
    }
}
