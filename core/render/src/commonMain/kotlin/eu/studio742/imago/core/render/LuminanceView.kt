package eu.studio742.imago.core.render

import java.util.stream.IntStream
import kotlin.math.roundToInt

/**
 * Show Luminance Mask: the photo as a Luminance Range reads it, each pixel the grey of its own lightness.
 *
 * Colour says nothing about which pixels a range takes, and with it out of the way the red of the mask
 * reads as a selection and not as a tint on the photo. The map comes from the same [BrushReference] the
 * range is painted from, so a grey on screen is the lightness the slider compares, and a pixel that is
 * already grey stays as it is.
 *
 * The map is kept for as long as the photo stays the same: moving the range only moves the red.
 */
class LuminanceView {
    private var mapOf: PixelBuffer? = null
    private var map: PixelBuffer? = null

    /** [source] as the map shows it, at the size of its [BrushReference]. */
    @Synchronized
    fun render(source: PixelBuffer): PixelBuffer =
        map?.takeIf { mapOf === source } ?: mapOf(BrushReference.of(source)).also { map = it; mapOf = source }

    companion object {
        internal fun mapOf(reference: BrushReference): PixelBuffer {
            val lightness = RangeMasks.lightnessMap(reference)
            val out = PixelBuffer(reference.width, reference.height)
            IntStream.range(0, reference.height).parallel().forEach { y ->
                for (i in y * reference.width until (y + 1) * reference.width) {
                    out.pixels[i] = GREYS[(lightness[i] / 100f * (LEVELS - 1)).roundToInt().coerceIn(0, LEVELS - 1)]
                }
            }
            return out
        }

        // Fine enough that a step in the shadows, where the encoding is steepest, stays under one grey.
        private const val LEVELS = 4096

        private val GREYS = IntArray(LEVELS) { level ->
            val rgb = FloatArray(3)
            Oklab.toSrgb(level.toFloat() / (LEVELS - 1), 0f, 0f, rgb)
            val grey = (rgb[0] * 255f).roundToInt()
            (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }
}
