package eu.studio742.imago.feature.detail

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Seconds between 1904-01-01, where MP4 counts time from, and 1970-01-01. */
private const val MP4_EPOCH_OFFSET_S = 2_082_844_800L

/** When a video was recorded and last changed, as its MP4 headers count them: seconds since 1904, UTC. */
internal data class Mp4Times(val creation: Long, val modification: Long)

/** [epochMillis] counted the way MP4 headers count it. */
internal fun mp4SecondsOf(epochMillis: Long): Long = epochMillis / 1000 + MP4_EPOCH_OFFSET_S

/**
 * Reads and writes the dates in a video's headers.
 *
 * A muxer writes the time it ran into the file it makes, and the phone's gallery and Immich read the
 * date of a video from there, not from what the app tells them. The cut of a video recorded last year
 * showed as made today; copying the original's dates into the cut keeps it where it was in the timeline.
 */
internal object Mp4Dates {
    /** The movie header's dates, or null when the file has none. */
    fun read(file: File): Mp4Times? = RandomAccessFile(file, "r").use { access ->
        val moov = findTopLevel(access, "moov") ?: return null
        val bytes = ByteBuffer.wrap(readBox(access, moov))
        var found: Mp4Times? = null
        forEachChild(bytes, 8, bytes.limit()) { type, start, _ ->
            if (type == "mvhd" && found == null) found = readTimes(bytes, start)
        }
        found
    }

    /**
     * Writes [times] into the movie header and into every track's and media's header, in place: the
     * fields keep their size, so nothing else in the file moves.
     */
    fun write(file: File, times: Mp4Times) {
        RandomAccessFile(file, "rw").use { access -> write(access, times) }
    }

    private fun write(access: RandomAccessFile, times: Mp4Times) {
        val moov = findTopLevel(access, "moov") ?: return
        val bytes = ByteBuffer.wrap(readBox(access, moov))
        fun patch(from: Int, to: Int) {
            forEachChild(bytes, from, to) { type, start, end ->
                when (type) {
                    "mvhd", "tkhd", "mdhd" -> writeTimes(bytes, start, times)
                    "trak", "mdia" -> patch(start, end)
                }
            }
        }
        patch(8, bytes.limit())
        access.seek(moov.first)
        access.write(bytes.array())
    }

    /** Offset and size of the first top-level box of [type]. */
    private fun findTopLevel(access: RandomAccessFile, type: String): Pair<Long, Long>? {
        var offset = 0L
        val header = ByteArray(16)
        while (offset + 8 <= access.length()) {
            access.seek(offset)
            access.readFully(header, 0, 8)
            val buffer = ByteBuffer.wrap(header)
            var size = buffer.getInt(0).toLong() and 0xffffffffL
            val name = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                access.readFully(header, 8, 8)
                size = buffer.getLong(8)
            } else if (size == 0L) {
                size = access.length() - offset
            }
            if (size < 8) return null
            if (name == type) return offset to size
            offset += size
        }
        return null
    }

    private fun readBox(access: RandomAccessFile, box: Pair<Long, Long>): ByteArray {
        // The movie box holds headers and sample tables, never the media: it fits in memory.
        require(box.second <= Int.MAX_VALUE) { "The movie box is too large" }
        val bytes = ByteArray(box.second.toInt())
        access.seek(box.first)
        access.readFully(bytes)
        return bytes
    }

    /** Calls [onBox] with each box between [from] and [to]: its type, where its content starts and where it ends. */
    private fun forEachChild(bytes: ByteBuffer, from: Int, to: Int, onBox: (type: String, start: Int, end: Int) -> Unit) {
        var offset = from
        while (offset + 8 <= to) {
            var size = bytes.getInt(offset).toLong() and 0xffffffffL
            var header = 8
            if (size == 1L) {
                size = bytes.getLong(offset + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - offset).toLong()
            }
            if (size < header || offset + size > to) return
            val type = String(bytes.array(), offset + 4, 4, Charsets.ISO_8859_1)
            onBox(type, offset + header, (offset + size).toInt())
            offset += size.toInt()
        }
    }

    /** The two dates at the start of a full box: 32-bit in version 0, 64-bit in version 1. */
    private fun readTimes(bytes: ByteBuffer, start: Int): Mp4Times =
        if (bytes.get(start).toInt() == 1) {
            Mp4Times(bytes.getLong(start + 4), bytes.getLong(start + 12))
        } else {
            Mp4Times(bytes.getInt(start + 4).toLong() and 0xffffffffL, bytes.getInt(start + 8).toLong() and 0xffffffffL)
        }

    private fun writeTimes(bytes: ByteBuffer, start: Int, times: Mp4Times) {
        if (bytes.get(start).toInt() == 1) {
            bytes.putLong(start + 4, times.creation)
            bytes.putLong(start + 12, times.modification)
        } else {
            bytes.putInt(start + 4, times.creation.toInt())
            bytes.putInt(start + 8, times.modification.toInt())
        }
    }
}
