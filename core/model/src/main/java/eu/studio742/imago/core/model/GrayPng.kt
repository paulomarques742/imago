package eu.studio742.imago.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 8-bit greyscale PNG, written and read here rather than by the platform: Android has no ImageIO and the
 * desktop no BitmapFactory, and a generated mask has to come back from a recipe byte for byte on both.
 *
 * It writes one IDAT with the Up filter, which suits smooth masks, and reads any of the five filters, but
 * only non-interlaced 8-bit greyscale — the one kind it writes.
 */
object GrayPng {
    fun encode(width: Int, height: Int, pixels: ByteArray): ByteArray {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Pixels do not match ${width}x$height" }
        val raw = ByteArray((width + 1) * height)
        for (y in 0 until height) {
            val row = y * (width + 1)
            raw[row] = if (y == 0) FILTER_NONE else FILTER_UP
            for (x in 0 until width) {
                val value = pixels[y * width + x].toInt()
                val above = if (y == 0) 0 else pixels[(y - 1) * width + x].toInt()
                raw[row + 1 + x] = (value - above).toByte()
            }
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val compressed = try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally {
            deflater.end()
        }
        val png = ByteArrayOutputStream()
        png.write(SIGNATURE)
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(width)
                writeInt(height)
                writeByte(8)
                writeByte(COLOUR_GREY)
                writeByte(0)
                writeByte(0)
                writeByte(0)
            }
        }.toByteArray()
        png.chunk("IHDR", header)
        png.chunk("IDAT", compressed)
        png.chunk("IEND", ByteArray(0))
        return png.toByteArray()
    }

    /** The image's size and its pixels, row by row; null for anything that is not a PNG this object writes. */
    fun decode(bytes: ByteArray): Triple<Int, Int, ByteArray>? {
        if (bytes.size < SIGNATURE.size || !SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }) return null
        var offset = SIGNATURE.size
        var width = 0
        var height = 0
        val data = ByteArrayOutputStream()
        while (offset + 8 <= bytes.size) {
            val length = bytes.int(offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val start = offset + 8
            if (length < 0 || start + length + 4 > bytes.size) return null
            when (type) {
                "IHDR" -> {
                    if (length != 13) return null
                    width = bytes.int(start)
                    height = bytes.int(start + 4)
                    val depth = bytes[start + 8].toInt()
                    val colour = bytes[start + 9].toInt()
                    val interlace = bytes[start + 12].toInt()
                    if (depth != 8 || colour != COLOUR_GREY || interlace != 0) return null
                    if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
                }
                "IDAT" -> data.write(bytes, start, length)
                "IEND" -> break
            }
            offset = start + length + 4
        }
        if (width == 0) return null
        val raw = ByteArray((width + 1) * height)
        val inflater = Inflater()
        try {
            inflater.setInput(data.toByteArray())
            var read = 0
            while (read < raw.size) {
                val n = inflater.inflate(raw, read, raw.size - read)
                if (n == 0 && (inflater.finished() || inflater.needsInput())) return null
                read += n
            }
        } catch (_: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            val filter = raw[y * (width + 1)].toInt()
            for (x in 0 until width) {
                val value = raw[y * (width + 1) + 1 + x].toInt() and 0xff
                val left = if (x == 0) 0 else pixels[y * width + x - 1].toInt() and 0xff
                val up = if (y == 0) 0 else pixels[(y - 1) * width + x].toInt() and 0xff
                val upLeft = if (x == 0 || y == 0) 0 else pixels[(y - 1) * width + x - 1].toInt() and 0xff
                val predicted = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> return null
                }
                pixels[y * width + x] = (value + predicted).toByte()
            }
        }
        return Triple(width, height, pixels)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun ByteArrayOutputStream.chunk(type: String, body: ByteArray) {
        val out = DataOutputStream(this)
        out.writeInt(body.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(body)
        val crc = CRC32().apply { update(typeBytes); update(body) }
        out.writeInt(crc.value.toInt())
    }

    private fun ByteArray.int(at: Int) =
        ((this[at].toInt() and 0xff) shl 24) or ((this[at + 1].toInt() and 0xff) shl 16) or
            ((this[at + 2].toInt() and 0xff) shl 8) or (this[at + 3].toInt() and 0xff)

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val COLOUR_GREY = 0
    private const val FILTER_NONE: Byte = 0
    private const val FILTER_UP: Byte = 2

    /** Far above any model's output; it keeps a hostile file from asking for gigabytes. */
    private const val MAX_SIDE = 4096
}
