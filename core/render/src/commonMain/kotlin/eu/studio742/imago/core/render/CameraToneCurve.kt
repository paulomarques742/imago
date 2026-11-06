package eu.studio742.imago.core.render

import coil3.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Takes a server's image of a RAW to the tones of the camera's JPEG, channel by channel.
 *
 * Unless told to extract the JPEG a RAW carries, Immich develops the RAW with LibRaw's automatic
 * brightness, which stretches each photo's histogram on its own: the same photo can come out far
 * brighter or darker than the camera showed it, and than the editor will. The file's smallest JPEG is
 * enough to put that right. A quantile of the server's image is mapped to the same quantile of the JPEG,
 * so the two need not be the same size, nor turned the same way. Measured on six X-T30 RAWs, the distance
 * from what the editor shows fell from 0.113 to 0.028: as close as the camera's full JPEG, at 0.030.
 *
 * [table] holds 256 entries for red, then green, then blue.
 */
class CameraToneCurve private constructor(val table: ByteArray) {
    /** Tells two curves apart in a cache key. */
    val id: String = table.fold(FNV_OFFSET) { hash, value -> (hash xor (value.toLong() and 255)) * FNV_PRIME }.toULong().toString(16)

    /** Replaces [buffer]'s colours; alpha stays. */
    fun applyTo(buffer: PixelBuffer) {
        val pixels = buffer.pixels
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = table[p ushr 16 and 255].toInt() and 255
            val g = table[256 + (p ushr 8 and 255)].toInt() and 255
            val b = table[512 + (p and 255)].toInt() and 255
            pixels[i] = (p and ALPHA) or (r shl 16) or (g shl 8) or b
        }
    }

    companion object {
        const val TABLE_SIZE = 768

        /** Fewer would miss a curve's bends; more only follow a small JPEG's noise. */
        private const val KNOTS = 33

        /** The ends are single pixels: a stray one must not decide where black and white go. */
        private const val TAIL = 0.005f

        /** What is left of [camera] once its bars are gone, below which it says too little. */
        private const val MIN_PIXELS = 2_000

        private const val ALPHA = -0x1000000
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L

        fun of(table: ByteArray): CameraToneCurve? = if (table.size == TABLE_SIZE) CameraToneCurve(table) else null

        /** The curve from [server], the server's image, to [camera], the file's JPEG. Null when that says too little. */
        fun between(server: PixelBuffer, camera: PixelBuffer): CameraToneCurve? {
            val target = withoutBars(camera) ?: return null
            if (target.width * target.height < MIN_PIXELS) return null
            val table = ByteArray(TABLE_SIZE)
            for (channel in 0 until 3) {
                val shift = 16 - 8 * channel
                curve(sortedChannel(server, shift), sortedChannel(target, shift)).copyInto(table, channel * 256)
            }
            return CameraToneCurve(table)
        }

        private fun sortedChannel(buffer: PixelBuffer, shift: Int): IntArray =
            IntArray(buffer.pixels.size) { buffer.pixels[it] ushr shift and 255 }.also { it.sort() }

        private fun quantile(sorted: IntArray, q: Float): Int = sorted[((sorted.size - 1) * q).roundToInt()]

        /**
         * The knots, joined by straight lines, from black. Past the last one the curve goes on at the
         * photo's overall gain: a highlight keeps its detail, and a dark photo's highlights do not race to
         * white, each channel on its own, as they would on a line drawn up to 255.
         */
        private fun curve(from: IntArray, to: IntArray): ByteArray {
            val xs = ArrayList<Int>(KNOTS + 2)
            val ys = ArrayList<Int>(KNOTS + 2)
            xs += 0
            ys += 0
            for (k in 0 until KNOTS) {
                val q = TAIL + (1f - 2f * TAIL) * k / (KNOTS - 1)
                val x = quantile(from, q)
                val y = max(quantile(to, q), ys.last())
                // A clipped sky is many quantiles at one value: they are one knot, at the first of them.
                if (x > xs.last()) {
                    xs += x
                    ys += y
                }
            }
            if (xs.last() < 255) {
                val gain = ys.last().toFloat() / xs.last()
                val end = ys.last() + (255 - xs.last()) * gain
                xs += 255
                ys += end.roundToInt().coerceIn(ys.last(), 255)
            }
            val table = ByteArray(256)
            var knot = 0
            for (value in 0..255) {
                while (knot < xs.size - 2 && value > xs[knot + 1]) knot++
                val span = xs[knot + 1] - xs[knot]
                val t = if (span == 0) 0f else (value - xs[knot]).toFloat() / span
                table[value] = (ys[knot] + (ys[knot + 1] - ys[knot]) * t).roundToInt().coerceIn(0, 255).toByte()
            }
            return table
        }

        /**
         * [image] without the black bars a 4:3 thumbnail puts around a 3:2 photo; they are not shadows of it.
         * Bars come in equal pairs, which a night sky over a dark foreground does not. Null when nothing else is left.
         */
        internal fun withoutBars(image: PixelBuffer): PixelBuffer? {
            val width = image.width
            val height = image.height
            fun isBar(start: Int, step: Int, count: Int): Boolean {
                var brightest = 0
                var sum = 0L
                var squares = 0L
                for (i in 0 until count) {
                    val p = image.pixels[start + i * step]
                    val value = ((p ushr 16 and 255) + (p ushr 8 and 255) + (p and 255)) / 3
                    brightest = max(brightest, value)
                    sum += value
                    squares += value.toLong() * value
                }
                val mean = sum.toFloat() / count
                return brightest <= BAR_BRIGHTEST && squares.toFloat() / count - mean * mean <= BAR_VARIANCE
            }
            var top = 0
            while (top < height && isBar(top * width, 1, width)) top++
            if (top == height) return null
            var bottom = height
            while (bottom > top && isBar((bottom - 1) * width, 1, width)) bottom--
            var left = 0
            while (left < width && isBar(left, width, height)) left++
            var right = width
            while (right > left && isBar(right - 1, width, height)) right--
            if (abs(top - (height - bottom)) > BAR_PAIR_SLACK) {
                top = 0
                bottom = height
            }
            if (abs(left - (width - right)) > BAR_PAIR_SLACK) {
                left = 0
                right = width
            }
            val keptWidth = right - left
            val keptHeight = bottom - top
            if (keptWidth == width && keptHeight == height) return image
            return PixelBuffer(keptWidth, keptHeight, IntArray(keptWidth * keptHeight) { i ->
                image.pixels[(top + i / keptWidth) * width + left + i % keptWidth]
            })
        }

        /** A JPEG's bar is black with a little ringing; a dark photo's edge has noise and light in it. */
        private const val BAR_BRIGHTEST = 15
        private const val BAR_VARIANCE = 6.5f

        /** A bar one row taller than its pair: the height of a 3:2 photo in a 4:3 frame need not be even. */
        private const val BAR_PAIR_SLACK = 2
    }
}

/** [curve] over the server's image, under the recipe if the request has one. */
class CameraToneTransformation(private val curve: CameraToneCurve) : Transformation() {
    override val cacheKey: String = "cameraTone:${curve.id}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        input.toPixelBuffer().also(curve::applyTo).toPlatformBitmap()
    }
}

/**
 * The JPEGs a RAW carries near its start, as far as the bytes that were read go.
 *
 * Almost every format keeps a small one in its first kilobytes, whether in a TIFF structure or inside the
 * EXIF of a bigger JPEG. Looking for the JPEG itself needs no reader per format.
 */
internal object EmbeddedPreview {
    /** The largest JPEG that is whole within [bytes], or null. One that ends beyond them cannot be used. */
    fun largestWhole(bytes: ByteArray): ByteArray? {
        var best: ByteArray? = null
        var bestArea = 0
        var position = 0
        while (position + 3 < bytes.size) {
            if (bytes[position] == MARK && bytes[position + 1] == SOI && bytes[position + 2] == MARK) {
                val found = wholeJpeg(bytes, position)
                if (found != null && found.area > bestArea) {
                    bestArea = found.area
                    best = bytes.copyOfRange(position, found.end)
                }
            }
            position++
        }
        return best
    }

    private class Found(val end: Int, val area: Int)

    /** Walks the segments from [start]: a JPEG inside another's EXIF is met on the way past, not mistaken for its end. */
    private fun wholeJpeg(bytes: ByteArray, start: Int): Found? {
        var position = start + 2
        var area = 0
        while (position + 4 <= bytes.size) {
            if (bytes[position] != MARK) return null
            val marker = bytes[position + 1].toInt() and 255
            val length = (bytes[position + 2].toInt() and 255 shl 8) or (bytes[position + 3].toInt() and 255)
            if (marker == SOS) {
                val end = endOfScan(bytes, position + 2 + length) ?: return null
                return if (area >= MIN_AREA) Found(end, area) else null
            }
            if (marker in SOF_MARKERS && position + 9 <= bytes.size) {
                val height = (bytes[position + 5].toInt() and 255 shl 8) or (bytes[position + 6].toInt() and 255)
                val width = (bytes[position + 7].toInt() and 255 shl 8) or (bytes[position + 8].toInt() and 255)
                area = width * height
            }
            position += 2 + length
        }
        return null
    }

    /** Past the compressed data, in which an 0xFF is a stuffed byte or a restart unless it ends the image. */
    private fun endOfScan(bytes: ByteArray, from: Int): Int? {
        var position = from
        while (position + 1 < bytes.size) {
            if (bytes[position] == MARK) {
                val next = bytes[position + 1].toInt() and 255
                if (next == EOI) return position + 2
                if (next != 0 && next !in RESTART_MARKERS && next != 0xFF) return null
            }
            position++
        }
        return null
    }

    private const val MARK = 0xFF.toByte()
    private const val SOI = 0xD8.toByte()
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private val SOF_MARKERS = 0xC0..0xC2
    private val RESTART_MARKERS = 0xD0..0xD7

    /** Under 100 × 60 it is an icon, not a picture of the photo. */
    private const val MIN_AREA = 6_000
}
