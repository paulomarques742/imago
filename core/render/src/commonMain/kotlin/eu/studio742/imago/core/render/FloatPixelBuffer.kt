package eu.studio742.imago.core.render

import java.util.stream.IntStream
import kotlin.math.pow
import eu.studio742.imago.core.raw.HalfFloats
import eu.studio742.imago.core.raw.decodeHalf
import eu.studio742.imago.core.raw.encodeHalf

/** An opaque, high-precision photo. Linear sources retain camera highlights above display white. */
class FloatPixelBuffer private constructor(
    override val width: Int,
    override val height: Int,
    val rgb: HalfFloats,
    var linear: Boolean = true,
) : PixelSurface {
    constructor(width: Int, height: Int, rgb: FloatArray? = null, linear: Boolean = true) : this(width, height,
        HalfFloats(if (rgb == null) ShortArray(Math.multiplyExact(Math.multiplyExact(width, height), 3)) else ShortArray(rgb.size) { encodeHalf(rgb[it]) }), linear)
    constructor(width: Int, height: Int, rgb: ShortArray, linear: Boolean = true) : this(width, height, HalfFloats(rgb), linear)
    init { require(width > 0 && height > 0 && rgb.size == Math.multiplyExact(Math.multiplyExact(width, height), 3)) }

    /**
     * Pixels of this buffer per pixel of the original it was developed from: 1 for a full-resolution
     * development, less for a preview. Filters sized in the original's pixels, as the noise reduction's
     * are, use it so that a preview and an export filter the same detail.
     */
    var sourceScale: Float = 1f

    fun copy() = FloatPixelBuffer(width, height, rgb.copyOf(), linear).also { it.sourceScale = sourceScale }

    /** Quantization belongs at the display or encoder boundary, after the recipe. */
    fun toPixelBuffer(): PixelBuffer = PixelBuffer(width, height).also { output ->
        IntStream.range(0, height).parallel().forEach { y -> getPixels(output.pixels, y * width, width, 0, y, width, 1) }
    }

    override fun getPixels(target: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height)
        val values = rgb.values
        val bytes = if (linear) LINEAR_BYTES else DISPLAY_BYTES
        for (row in 0 until h) {
            var index = ((y + row) * width + x) * 3
            val out = offset + row * stride
            for (column in 0 until w) {
                val red = bytes[values[index].toInt() and 0xFFFF].toInt() and 0xFF
                val green = bytes[values[index + 1].toInt() and 0xFFFF].toInt() and 0xFF
                val blue = bytes[values[index + 2].toInt() and 0xFFFF].toInt() and 0xFF
                target[out + column] = -0x1000000 or (red shl 16) or (green shl 8) or blue
                index += 3
            }
        }
    }

    override fun setPixels(source: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height)
        repeat(h) { row -> repeat(w) { column ->
            val index = ((y + row) * width + x + column) * 3
            val pixel = source[offset + row * stride + column]
            repeat(3) { c ->
                val value = (pixel ushr (16 - c * 8) and 255) / 255f
                rgb[index + c] = if (linear) BitmapPhotoProcessor.srgbToLinear(value) else value
            }
        } }
    }
}

/**
 * The 8 bits of every binary16 value: a sample has 65 536 possible values, and a table of them is what
 * the last step of a 26 Mpx render, 78 million samples, wants instead of a power for each.
 */
private val DISPLAY_BYTES: ByteArray by lazy { ByteArray(65536) { eightBits(decodeHalf(it.toShort())) } }
private val LINEAR_BYTES: ByteArray by lazy { ByteArray(65536) { eightBits(encodeSrgb(decodeHalf(it.toShort()))) } }

private fun eightBits(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f + .5f).toInt().toByte()

/** The signed transfer preserves out-of-gamut camera values until the working tone stage. */
internal fun encodeSrgb(value: Float): Float =
    if (value < .0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - .055f

internal fun PixelSurface.readRgbaRow(y: Int, rgba: FloatArray, scratch: IntArray) {
    if (this is FloatPixelBuffer) {
        repeat(width) { x ->
            repeat(3) { c -> rgba[x * 4 + c] = rgb[(y * width + x) * 3 + c] }
            rgba[x * 4 + 3] = 1f
        }
    } else {
        getPixels(scratch, 0, width, 0, y, width, 1)
        repeat(width) { x -> repeat(4) { c -> rgba[x * 4 + c] = (scratch[x] ushr (if (c == 3) 24 else 16 - c * 8) and 255) / 255f } }
    }
}

internal fun PixelSurface.writeRgbaRow(y: Int, rgba: FloatArray, scratch: IntArray) {
    if (this is FloatPixelBuffer) {
        repeat(width) { x -> repeat(3) { c -> rgb[(y * width + x) * 3 + c] = rgba[x * 4 + c] } }
    } else {
        repeat(width) { x ->
            var pixel = 0
            repeat(4) { c -> pixel = pixel or ((rgba[x * 4 + c].coerceIn(0f, 1f) * 255f + .5f).toInt() shl (if (c == 3) 24 else 16 - c * 8)) }
            scratch[x] = pixel
        }
        setPixels(scratch, 0, width, 0, y, width, 1)
    }
}
