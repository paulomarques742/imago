package eu.studio742.imago.core.render

import java.util.stream.IntStream

/**
 * An ARGB image the CPU processor reads and writes row by row.
 *
 * It has the shape of Android's `Bitmap` — the same `getPixels`/`setPixels` signatures —, so the same
 * processor serves the export on the phone and the preview on desktop.
 */
interface PixelSurface {
    val width: Int
    val height: Int
    fun getPixels(target: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int)
    fun setPixels(source: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int)
}

/** ARGB pixels in an array, with no platform classes. */
class PixelBuffer(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray = IntArray(Math.multiplyExact(width, height)),
) : PixelSurface {
    init { require(width > 0 && height > 0 && pixels.size == Math.multiplyExact(width, height)) }

    fun copy() = PixelBuffer(width, height, pixels.copyOf())

    override fun getPixels(target: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height)
        repeat(h) { pixels.copyInto(target, offset + it * stride, (y + it) * width + x, (y + it) * width + x + w) }
    }

    override fun setPixels(source: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height)
        repeat(h) { source.copyInto(pixels, (y + it) * width + x, offset + it * stride, offset + it * stride + w) }
    }
}

/**
 * Runs [band] over strips of rows that together cover the image.
 *
 * Only a [PixelBuffer] is split across cores: different rows are different zones of the same array.
 * Android's `Bitmap` does not promise concurrent writes, and export on the phone already runs off the
 * interface — there it stays a single strip, the whole image, as it always was.
 */
internal fun forEachRowBand(surface: PixelSurface, band: (fromRow: Int, untilRow: Int) -> Unit) {
    if (!rowsRunInParallel(surface)) return band(0, surface.height)
    val bands = minOf(Runtime.getRuntime().availableProcessors(), surface.height / MIN_ROWS_PER_BAND)
    val rows = (surface.height + bands - 1) / bands
    IntStream.range(0, bands).parallel().forEach { index ->
        val from = index * rows
        if (from < surface.height) band(from, minOf(from + rows, surface.height))
    }
}

internal fun rowsRunInParallel(surface: PixelSurface): Boolean =
    surface is PixelBuffer && Runtime.getRuntime().availableProcessors() > 1 && surface.height >= 2 * MIN_ROWS_PER_BAND

/** Below this, splitting costs more than it saves. */
private const val MIN_ROWS_PER_BAND = 32
