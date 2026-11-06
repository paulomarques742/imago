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
 * A [PixelBuffer] and a [FloatPixelBuffer] are split across cores: different rows are different zones of
 * the same array. Android's `Bitmap` does not promise concurrent writes: a JPEG's export on the phone
 * stays a single strip, the whole image, as it always was.
 */
internal fun forEachRowBand(surface: PixelSurface, band: (fromRow: Int, untilRow: Int) -> Unit) {
    val bands = rowBands(surface)
    if (bands.size == 1) return band(0, surface.height)
    IntStream.range(0, bands.size).parallel().forEach { index -> band(bands[index].first, bands[index].last + 1) }
}

/** The strips [forEachRowBand] runs over. */
internal fun rowBands(surface: PixelSurface): List<IntRange> {
    if (!rowsRunInParallel(surface)) return listOf(0 until surface.height)
    // Many more strips than cores: a phone's cores are not equal, and with one strip each the whole image
    // waits for the slowest.
    val bands = minOf(Runtime.getRuntime().availableProcessors() * STRIPS_PER_CORE, surface.height / MIN_ROWS_PER_BAND)
    val rows = (surface.height + bands - 1) / bands
    return (0 until bands).map { it * rows }.filter { it < surface.height }.map { it until minOf(it + rows, surface.height) }
}

internal fun rowsRunInParallel(surface: PixelSurface): Boolean =
    parallelRows && (surface is PixelBuffer || surface is FloatPixelBuffer) &&
        Runtime.getRuntime().availableProcessors() > 1 && surface.height >= 2 * MIN_ROWS_PER_BAND

/** Off for a test to compare the strips with one pass over the image. */
internal var parallelRows = true

/** Below this, splitting costs more than it saves. */
private const val MIN_ROWS_PER_BAND = 32
private const val STRIPS_PER_CORE = 6
