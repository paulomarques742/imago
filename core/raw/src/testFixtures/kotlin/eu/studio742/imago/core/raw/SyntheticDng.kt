package eu.studio742.imago.core.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/** A tiny, uncompressed Bayer DNG with enough distinct sensor values to detect 8-bit truncation. */
internal fun syntheticDng(
    orientation: Int,
    compressed: Boolean = false,
    monochrome: Boolean = false,
    warmWhiteBalance: Boolean = false,
    baselineExposure: Int? = null,
): ByteArray {
    data class Tag(val id: Int, val type: Int, val count: Int, val data: ByteArray)
    fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putShort(it.toShort()) } }.array()
    fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putInt(it) } }.array()
    fun ascii(value: String) = (value + '\u0000').toByteArray(Charsets.US_ASCII)
    val sensor = ByteBuffer.allocate(64 * 48 * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { repeat(64 * 48) { putShort((256 + it).toShort()) } }.array()
    val payload = if (compressed) {
        // TIFF floating-point predictor: byte planes in big-endian order, then horizontal deltas.
        val predicted = ByteArray(64 * 48 * 4)
        repeat(48) { y ->
            val row = ByteBuffer.allocate(64 * 4).order(ByteOrder.BIG_ENDIAN)
                .apply { repeat(64) { putFloat((256 + y * 64 + it).toFloat()) } }.array()
            repeat(4) { plane -> repeat(64) { x -> predicted[y * 256 + plane * 64 + x] = row[x * 4 + plane] } }
            for (i in 255 downTo 1) {
                val offset = y * 256 + i
                predicted[offset] = (predicted[offset].toInt() - predicted[offset - 1].toInt()).toByte()
            }
        }
        ByteArrayOutputStream().apply { DeflaterOutputStream(this).use { it.write(predicted) } }.toByteArray()
    } else sensor
    val tags = mutableListOf<Tag>()
    fun short(id: Int, value: Int) { tags += Tag(id, 3, 1, shorts(value)) }
    fun long(id: Int, value: Int) { tags += Tag(id, 4, 1, longs(value)) }
    fun text(id: Int, value: String) { val bytes = ascii(value); tags += Tag(id, 2, bytes.size, bytes) }
    long(256, 64); long(257, 48); short(258, if (compressed) 32 else 16)
    short(259, if (compressed) 8 else 1); short(262, if (monochrome) 34892 else 32803)
    text(271, "IMAGO"); text(272, "Synthetic Bayer")
    long(273, 0); short(274, orientation); short(277, 1); long(278, 48); long(279, payload.size)
    short(284, 1)
    if (compressed) { short(317, 3); short(339, 3) }
    if (!monochrome) {
        tags += Tag(33421, 3, 2, shorts(2, 2))
        tags += Tag(33422, 1, 4, byteArrayOf(0, 1, 1, 2))
    }
    tags += Tag(50706, 1, 4, byteArrayOf(1, 4, 0, 0))
    tags += Tag(50707, 1, 4, byteArrayOf(1, 1, 0, 0))
    text(50708, "IMAGO Synthetic Bayer")
    short(50714, 0); short(50717, 4095)
    // XYZ to linear sRGB, as the camera's calibration matrix.
    val matrix = intArrayOf(3240454, -1537138, -498531, -969266, 1876011, 41556, 55643, -204026, 1057225)
    tags += Tag(50721, 10, 9, longs(*matrix.flatMap { listOf(it, 1000000) }.toIntArray()))
    tags += Tag(50728, 5, 3, if (warmWhiteBalance) longs(1, 4, 1, 1, 1, 2) else longs(1, 1, 1, 1, 1, 1))
    baselineExposure?.let { tags += Tag(50730, 10, 1, longs(it, 1)) }
    short(50778, 21)
    tags.sortBy { it.id }
    val directoryEnd = 8 + 2 + tags.size * 12 + 4
    val pixelOffset = directoryEnd + tags.filter { it.data.size > 4 }.sumOf { (it.data.size + 1) / 2 * 2 }
    val buffer = ByteBuffer.allocate(pixelOffset + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(tags.size.toShort())
    var external = directoryEnd
    for (tag in tags) {
        buffer.putShort(tag.id.toShort()).putShort(tag.type.toShort()).putInt(tag.count)
        val data = if (tag.id == 273) longs(pixelOffset) else tag.data
        if (data.size <= 4) buffer.put(data.copyOf(4)) else {
            buffer.putInt(external)
            val position = buffer.position()
            buffer.position(external); buffer.put(data); buffer.position(position)
            external += (data.size + 1) / 2 * 2
        }
    }
    buffer.putInt(0)
    buffer.position(pixelOffset)
    buffer.put(payload)
    return buffer.array()
}
