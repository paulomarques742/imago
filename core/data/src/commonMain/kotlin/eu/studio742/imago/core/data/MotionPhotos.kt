package eu.studio742.imago.core.data

/** Up to [length] bytes of a file from [position]; fewer at its end. */
fun interface ByteReader {
    fun read(position: Long, length: Int): ByteArray
}

/** Where the MP4 of a Motion Photo sits inside the photo's file. */
data class MotionRange(val start: Long, val length: Long)

/**
 * Finds the video a Motion Photo carries inside its own file. MediaStore has no column for it, so the
 * file has to say: only its head (the XMP) and its tail (the Samsung trailer) are read, never the image.
 *
 * Two layouts, and a Galaxy S23 writes both in the same file:
 * - Google's: XMP `GCamera:MotionPhoto="1"` with a `Container:Directory` whose `MotionPhoto` item has
 *   a `Length` counted back from the end of the file; the older `GCamera:MicroVideo` with
 *   `MicroVideoOffset` says the same. Samsung's own trailer comes after the video and is counted in.
 * - Samsung's: a trailer ending in `SEFT`, whose directory (`SEFH`) lists named blocks; the one named
 *   `MotionPhoto_Data` is the MP4.
 *
 * Either way the start must be an MP4 (`ftyp`), and the length is what its boxes add up to, so a
 * trailer counted in by one layout never reaches the player.
 */
object MotionPhotos {
    fun find(size: Long, read: ByteReader): MotionRange? {
        if (size < MIN_SIZE) return null
        val start = googleStart(size, read) ?: samsungStart(size, read) ?: return null
        val length = mp4Length(start, size, read) ?: return null
        return MotionRange(start, length)
    }

    /** The start Google's XMP gives, counted back from the end of the file. */
    private fun googleStart(size: Long, read: ByteReader): Long? {
        val xmp = jpegXmp(read) ?: return null
        val motion = Regex("""GCamera:MotionPhoto\s*=\s*"1"|<GCamera:MotionPhoto>\s*1\s*<""").containsMatchIn(xmp)
        if (motion) {
            // Each item is one `Container:Item`; the video is the one whose semantic is "MotionPhoto".
            val item = Regex("""<Container:Item\b[^>]*>""").findAll(xmp).map { it.value }
                .firstOrNull { Regex("""Item:Semantic\s*=\s*"MotionPhoto"""").containsMatchIn(it) }
            val length = item?.let { Regex("""Item:Length\s*=\s*"(\d+)"""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            if (length != null && length in 1 until size) return size - length
        }
        val micro = Regex("""GCamera:MicroVideo\s*=\s*"1"""").containsMatchIn(xmp)
        val offset = Regex("""GCamera:MicroVideoOffset\s*=\s*"(\d+)"""").find(xmp)?.groupValues?.get(1)?.toLongOrNull()
        return if (micro && offset != null && offset in 1 until size) size - offset else null
    }

    /** The XMP of a JPEG: its APP1 segments up to the image data. Null for anything else. */
    private fun jpegXmp(read: ByteReader): String? {
        val head = read.read(0, HEAD_BYTES)
        if (head.size < 4 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
        val text = StringBuilder()
        var at = 2
        while (at + 4 <= head.size && head[at] == 0xFF.toByte()) {
            val marker = head[at + 1].toInt() and 0xFF
            if (marker == SOS) break
            val length = ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
            if (length < 2) break
            val payload = at + 4
            val end = minOf(at + 2 + length, head.size)
            if (marker == APP1 && startsWith(head, payload, XMP_ID)) {
                text.append(head.copyOfRange(payload + XMP_ID.size, end).decodeToString())
            }
            at += 2 + length
        }
        return text.toString().takeIf { it.isNotEmpty() }
    }

    /** The start of the `MotionPhoto_Data` block of Samsung's trailer. */
    private fun samsungStart(size: Long, read: ByteReader): Long? {
        val tail = read.read(size - 8, 8)
        if (tail.size != 8 || !startsWith(tail, 4, SEFT)) return null
        val directoryLength = littleInt(tail, 0)
        val directory = size - 8 - directoryLength
        if (directoryLength < 12 || directory < 0) return null
        val bytes = read.read(directory, directoryLength.toInt().coerceAtMost(MAX_DIRECTORY))
        if (bytes.size < 12 || !startsWith(bytes, 0, SEFH)) return null
        val count = littleInt(bytes, 8).toInt()
        for (index in 0 until count) {
            val entry = 12 + index * 12
            if (entry + 12 > bytes.size) break
            // Each entry: two reserved bytes, the type, how far back from the directory its block
            // starts, and the block's length. The block: reserved, type, the name's length, the name.
            val block = directory - littleInt(bytes, entry + 4)
            if (block < 0) continue
            val header = read.read(block, 8 + MOTION_DATA.size)
            if (header.size < 8 + MOTION_DATA.size) continue
            val nameLength = littleInt(header, 4)
            if (nameLength == MOTION_DATA.size.toLong() && startsWith(header, 8, MOTION_DATA)) return block + 8 + nameLength
        }
        return null
    }

    /** How long the MP4 at [start] is, by its top-level boxes; null when [start] is not one. */
    private fun mp4Length(start: Long, size: Long, read: ByteReader): Long? {
        var at = start
        var boxes = 0
        while (at + 8 <= size && boxes < MAX_BOXES) {
            val header = read.read(at, 16)
            if (header.size < 8) break
            var boxSize = bigInt(header, 0)
            val type = header.copyOfRange(4, 8)
            if (boxes == 0 && !type.contentEquals(FTYP)) return null
            if (!type.all { it in 0x20..0x7E }) break
            if (boxSize == 1L) {
                if (header.size < 16) break
                boxSize = (bigInt(header, 8) shl 32) or bigInt(header, 12)
            } else if (boxSize == 0L) {
                boxSize = size - at
            }
            if (boxSize < 8 || at + boxSize > size) break
            at += boxSize
            boxes++
        }
        return (at - start).takeIf { boxes >= 2 }
    }

    private fun startsWith(bytes: ByteArray, at: Int, prefix: ByteArray) =
        at >= 0 && at + prefix.size <= bytes.size && prefix.indices.all { bytes[at + it] == prefix[it] }

    private fun littleInt(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }

    private fun bigInt(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    /** The EXIF thumbnail sits before the XMP; 128 KiB reaches past it on every file seen. */
    private const val HEAD_BYTES = 128 * 1024
    private const val MAX_DIRECTORY = 64 * 1024
    private const val MAX_BOXES = 64
    private const val MIN_SIZE = 64L
    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
    private val SEFT = "SEFT".encodeToByteArray()
    private val SEFH = "SEFH".encodeToByteArray()
    private val FTYP = "ftyp".encodeToByteArray()
    private val MOTION_DATA = "MotionPhoto_Data".encodeToByteArray()
}
