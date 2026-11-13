package eu.studio742.imago.feature.detail

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4DatesTest {
    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = content.fold(ByteArray(0)) { all, part -> all + part }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }

    /** A full box with the two dates and some fields after them that must not change. */
    private fun timed(type: String, version: Int, creation: Long, modification: Long): ByteArray {
        val buffer = if (version == 1) {
            ByteBuffer.allocate(4 + 16 + 4).put(1).put(ByteArray(3)).putLong(creation).putLong(modification)
        } else {
            ByteBuffer.allocate(4 + 8 + 4).put(0).put(ByteArray(3)).putInt(creation.toInt()).putInt(modification.toInt())
        }
        return box(type, buffer.putInt(0x0BADF00D).array())
    }

    /** The shape a muxer writes: the media first, the movie box at the end. */
    private fun movie(creation: Long, trackVersion: Int = 0): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(box("ftyp", "isom".toByteArray()))
        output.write(box("mdat", ByteArray(64) { it.toByte() }))
        output.write(
            box(
                "moov",
                timed("mvhd", 0, creation, creation),
                box("trak", timed("tkhd", trackVersion, creation, creation), box("mdia", timed("mdhd", 1, creation, creation))),
                box("trak", timed("tkhd", 0, creation, creation), box("mdia", timed("mdhd", 0, creation, creation))),
            ),
        )
        return output.toByteArray()
    }

    private fun file(bytes: ByteArray) = File.createTempFile("mp4-dates", ".mp4").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun theOriginalsDatesGoIntoEveryHeaderOfTheCut() {
        val recorded = mp4SecondsOf(1_700_000_000_000L)
        val original = file(movie(recorded))
        val cut = file(movie(mp4SecondsOf(1_762_000_000_000L), trackVersion = 1))

        Mp4Dates.write(cut, checkNotNull(Mp4Dates.read(original)))

        assertEquals(Mp4Times(recorded, recorded), Mp4Dates.read(cut))
        // Every header got them, and the bytes around the dates are the ones that were there.
        assertArrayEquals(movie(recorded, trackVersion = 1), cut.readBytes())
    }

    @Test
    fun aFileWithoutAMovieBoxHasNoDates() {
        assertNull(Mp4Dates.read(file(box("ftyp", "isom".toByteArray()))))
    }

    @Test
    fun mp4CountsFrom1904() {
        assertEquals(2_082_844_800L, mp4SecondsOf(0L))
    }
}
