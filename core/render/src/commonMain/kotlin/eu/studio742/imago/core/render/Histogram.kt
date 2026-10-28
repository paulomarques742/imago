package eu.studio742.imago.core.render

/**
 * How the pixels of an image are spread over the scale, one count per [BINS] bin and channel, and how
 * many of them touch an end of it.
 *
 * [shadowsClipped] and [highlightsClipped] are shares of the pixels (0 to 1) with a channel at zero or
 * at full: the same test the clipping warning paints with, so that what the histogram says and what
 * the photo shows are one thing.
 */
class Histogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luminance: IntArray,
    val shadowsClipped: Float,
    val highlightsClipped: Float,
) {
    /** The tallest bin of any of the four, which is what the drawing scales to. */
    val peak: Int get() = maxOf(red.max(), green.max(), blue.max(), luminance.max())

    companion object {
        const val BINS = 64

        /** A channel at or below this, out of 255, counts as black; at or above [HIGH] as white. */
        const val LOW = 1
        const val HIGH = 254

        fun of(image: PixelSurface): Histogram {
            val red = IntArray(BINS)
            val green = IntArray(BINS)
            val blue = IntArray(BINS)
            val luminance = IntArray(BINS)
            var shadows = 0L
            var highlights = 0L
            val row = IntArray(image.width)
            for (y in 0 until image.height) {
                image.getPixels(row, 0, image.width, 0, y, image.width, 1)
                for (pixel in row) {
                    val r = pixel shr 16 and 255
                    val g = pixel shr 8 and 255
                    val b = pixel and 255
                    red[r * BINS / 256]++
                    green[g * BINS / 256]++
                    blue[b * BINS / 256]++
                    luminance[((r * 0.2126f + g * 0.7152f + b * 0.0722f) * BINS / 256f).toInt().coerceIn(0, BINS - 1)]++
                    if (r <= LOW || g <= LOW || b <= LOW) shadows++
                    if (r >= HIGH || g >= HIGH || b >= HIGH) highlights++
                }
            }
            val total = (image.width.toLong() * image.height).coerceAtLeast(1L).toFloat()
            return Histogram(red, green, blue, luminance, shadows / total, highlights / total)
        }
    }
}

/** What the editor paints over the photo to show where it touches an end of the scale. */
data class ClippingWarning(val shadows: Boolean = false, val highlights: Boolean = false) {
    val isOn: Boolean get() = shadows || highlights

    companion object {
        val NONE = ClippingWarning()
    }
}
