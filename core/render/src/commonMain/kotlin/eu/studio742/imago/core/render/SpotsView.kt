package eu.studio742.imago.core.render

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Visualize Spots: the photo as a map of what stands out from its surroundings, white on black, so that
 * dust on the sensor and small blemishes — lost in a sky at the size of a screen — show as dots and rings.
 *
 * The map is a difference of two Gaussians on the luminance, the fine one taking the pixel noise off and
 * the wide one standing for the surroundings. It is worked out on a raster of at most [SIDE] on the long
 * side — the size of a phone's proxy, which the desktop's full photo is reduced to — with radii in that
 * raster's pixels: dust is a few pixels wide whatever the photo, not a fraction of it.
 *
 * The contrast is kept for as long as the photo stays the same, on a logarithmic byte per pixel, so that
 * moving the sensitivity is a table of 256 entries looked up once per pixel and not a ramp worked out for
 * each: on a phone the threshold is redone on the GL thread at every step of the slider.
 */
class SpotsView {
    private var contrastOf: PixelBuffer? = null
    private var contrast: Contrast? = null

    /** The size of the last map, the raster the contrast was worked out on. */
    val width: Int get() = contrast?.width ?: 0
    val height: Int get() = contrast?.height ?: 0

    /** [source] as the map shows it at [sensitivity], 0 to 100; [SIDE] on the long side at most. */
    @Synchronized
    fun render(source: PixelBuffer, sensitivity: Float): PixelBuffer {
        val field = contrast?.takeIf { contrastOf === source } ?: contrastOf(source).also { contrast = it; contrastOf = source }
        val threshold = threshold(sensitivity)
        val low = threshold * RAMP_LOW
        val high = threshold * RAMP_HIGH
        val shades = IntArray(LEVELS) { level ->
            val t = ((contrastAt(level) - low) / (high - low)).coerceIn(0f, 1f)
            val grey = (t * t * (3f - 2f * t) * 255f).roundToInt()
            (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
        val out = PixelBuffer(field.width, field.height)
        IntStream.range(0, field.height).parallel().forEach { y ->
            for (i in y * field.width until (y + 1) * field.width) out.pixels[i] = shades[field.levels[i].toInt() and 0xFF]
        }
        return out
    }

    internal class Contrast(val width: Int, val height: Int, val levels: ByteArray)

    companion object {
        const val SIDE = 2048
        const val DEFAULT_SENSITIVITY = 50f

        /**
         * The difference from its surroundings at which a detail turns white. Logarithmic, because the
         * spots worth finding go from a step or two of the scale, in a clean sky, to a sixth of it.
         */
        fun threshold(sensitivity: Float): Float =
            MOST_THRESHOLD * (LEAST_THRESHOLD / MOST_THRESHOLD).pow(sensitivity.coerceIn(0f, 100f) / 100f)

        internal fun contrastOf(source: PixelBuffer): Contrast {
            val scale = min(1f, SIDE.toFloat() / max(source.width, source.height))
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            val luminance = reducedLuminance(source, width, height)
            val fine = blurred(luminance, width, height, FINE_SIGMA)
            val wide = blurred(luminance, width, height, WIDE_SIGMA)
            val levels = ByteArray(width * height)
            IntStream.range(0, height).parallel().forEach { y ->
                for (i in y * width until (y + 1) * width) levels[i] = levelOf(abs(fine[i] - wide[i])).toByte()
            }
            return Contrast(width, height, levels)
        }

        /** The level a contrast is kept at: logarithmic between [FAINTEST] and [STRONGEST], about 1.7 % a step. */
        internal fun levelOf(contrast: Float): Int =
            if (contrast <= FAINTEST) 0 else ((ln(contrast / FAINTEST) / LEVEL_SPAN) * (LEVELS - 1)).roundToInt().coerceAtMost(LEVELS - 1)

        internal fun contrastAt(level: Int): Float =
            if (level == 0) 0f else FAINTEST * exp(level.toFloat() / (LEVELS - 1) * LEVEL_SPAN)

        /** A separable Gaussian, the rows of each pass shared among the cores. */
        private fun blurred(source: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
            val weights = PhotoEffects.halfGaussianKernel(sigma)
            val radius = weights.lastIndex
            val across = FloatArray(source.size)
            IntStream.range(0, height).parallel().forEach { y ->
                val row = y * width
                for (x in 0 until width) {
                    var total = source[row + x] * weights[0]
                    for (offset in 1..radius) {
                        total += (source[row + max(x - offset, 0)] + source[row + min(x + offset, width - 1)]) * weights[offset]
                    }
                    across[row + x] = total
                }
            }
            val out = FloatArray(source.size)
            IntStream.range(0, height).parallel().forEach { y ->
                val row = y * width
                for (x in 0 until width) out[row + x] = across[row + x] * weights[0]
                for (offset in 1..radius) {
                    val above = max(y - offset, 0) * width
                    val below = min(y + offset, height - 1) * width
                    val weight = weights[offset]
                    for (x in 0 until width) out[row + x] += (across[above + x] + across[below + x]) * weight
                }
            }
            return out
        }

        /**
         * The encoded luminance, each pixel of the raster the average of the source's pixels under it: a
         * spot thinner than the reduction's step is averaged in, where sampling would skip it.
         */
        private fun reducedLuminance(source: PixelBuffer, width: Int, height: Int): FloatArray {
            val out = FloatArray(width * height)
            val stepX = source.width.toFloat() / width
            val stepY = source.height.toFloat() / height
            IntStream.range(0, height).parallel().forEach { y ->
                val top = (y * stepY).toInt()
                val bottom = max(top + 1, min(source.height, ((y + 1) * stepY).toInt()))
                for (x in 0 until width) {
                    val left = (x * stepX).toInt()
                    val right = max(left + 1, min(source.width, ((x + 1) * stepX).toInt()))
                    var total = 0f
                    for (sy in top until bottom) {
                        val row = sy * source.width
                        for (sx in left until right) {
                            val pixel = source.pixels[row + sx]
                            total += 0.2126f * ((pixel shr 16) and 255) + 0.7152f * ((pixel shr 8) and 255) + 0.0722f * (pixel and 255)
                        }
                    }
                    out[y * width + x] = total / ((bottom - top) * (right - left) * 255f)
                }
            }
            return out
        }

        private const val FINE_SIGMA = 0.8f
        private const val WIDE_SIGMA = 4f
        // At the default, 0.03: above the grain of a clean sky, which in the samples stays under 0.025 in
        // all but one pixel in a thousand. Fainter dust is found by raising the sensitivity.
        private const val MOST_THRESHOLD = 0.15f
        private const val LEAST_THRESHOLD = 0.006f
        /** A short ramp around the threshold: the edge of a spot reads as a line and not as a staircase. */
        private const val RAMP_LOW = 0.8f
        private const val RAMP_HIGH = 1.25f
        private const val LEVELS = 256
        // Below the least threshold's ramp and above the most's: what lies outside is black or white at every sensitivity.
        private const val FAINTEST = 0.003f
        private const val STRONGEST = 0.25f
        private val LEVEL_SPAN = ln(STRONGEST / FAINTEST)
    }
}
