package eu.studio742.imago.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finding a Motion Photo's video inside the photo's file.
 *
 * The files are built here with the layout a Galaxy S23 writes (seen on 29/10/2026): Google's XMP
 * container whose video length runs to the end of the file, Samsung's trailer after the video, with
 * its `MotionPhoto_Data` block holding exactly the MP4.
 */
class MotionPhotosTest {
    private val mp4 = mp4(mdat = 5_000)

    @Test fun aGalaxyFileGivesExactlyTheMp4() {
        val file = MotionFile(xmp = google(videoAndAfter = mp4.size + SEF_SIZE), samsung = true).bytes()

        val found = MotionPhotos.find(file.size.toLong(), reader(file))!!

        assertEquals(mp4.toList(), file.copyOfRange(found.start.toInt(), (found.start + found.length).toInt()).toList())
    }

    @Test fun googlesLayoutAloneIsEnough() {
        val file = MotionFile(xmp = google(videoAndAfter = mp4.size), samsung = false).bytes()

        assertEquals(mp4.size.toLong(), MotionPhotos.find(file.size.toLong(), reader(file))?.length)
    }

    @Test fun samsungsTrailerAloneIsEnough() {
        val file = MotionFile(xmp = null, samsung = true).bytes()

        val found = MotionPhotos.find(file.size.toLong(), reader(file))!!

        assertEquals(mp4.toList(), file.copyOfRange(found.start.toInt(), (found.start + found.length).toInt()).toList())
    }

    @Test fun theOlderMicroVideoIsReadToo() {
        val xmp = """<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="${mp4.size}"/>"""
        val file = MotionFile(xmp = xmp, samsung = false).bytes()

        assertEquals(mp4.size.toLong(), MotionPhotos.find(file.size.toLong(), reader(file))?.length)
    }

    @Test fun aPhotoWithOnlySamsungsOtherBlocksHasNone() {
        // What a Galaxy writes for a photo taken with the movement off: the trailer, no video.
        val file = MotionFile(xmp = null, samsung = true, withVideo = false).bytes()

        assertNull(MotionPhotos.find(file.size.toLong(), reader(file)))
    }

    @Test fun aLengthThatDoesNotLandOnAnMp4IsNotTrusted() {
        val file = MotionFile(xmp = google(videoAndAfter = mp4.size + 7), samsung = false).bytes()

        assertNull(MotionPhotos.find(file.size.toLong(), reader(file)))
    }

    @Test fun aCutFileHasNone() {
        val whole = MotionFile(xmp = google(videoAndAfter = mp4.size + SEF_SIZE), samsung = true).bytes()
        val cut = whole.copyOf(whole.size - 3_000)

        assertNull(MotionPhotos.find(cut.size.toLong(), reader(cut)))
    }

    @Test fun aPlainPhotoAndNotAJpegHaveNone() {
        val plain = MotionFile(xmp = null, samsung = false, withVideo = false).bytes()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(200)

        assertNull(MotionPhotos.find(plain.size.toLong(), reader(plain)))
        assertNull(MotionPhotos.find(png.size.toLong(), reader(png)))
    }

    private fun reader(bytes: ByteArray) = ByteReader { position, length ->
        if (position >= bytes.size) ByteArray(0)
        else bytes.copyOfRange(position.toInt(), minOf(bytes.size, position.toInt() + length))
    }

    private fun google(videoAndAfter: Int) = """
        <rdf:Description GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="0">
        <Container:Directory><rdf:Seq>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Padding="0"/></rdf:li>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoAndAfter" Item:Padding="0"/></rdf:li>
        </rdf:Seq></Container:Directory></rdf:Description>
    """.trimIndent()

    private inner class MotionFile(val xmp: String?, val samsung: Boolean, val withVideo: Boolean = true) {
        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            segment(out, 0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(2_000))
            xmp?.let { segment(out, 0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + it.toByteArray()) }
            out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2))
            out.write(ByteArray(3_000) { 0x11 })
            out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            // Samsung's blocks: the other ones first, then the video's, then the directory.
            val blocks = mutableListOf<Triple<Int, String, ByteArray>>()
            val withBlocks = samsung
            if (withBlocks) {
                blocks += Triple(0x0a01, "Image_UTC_Data", "1793283926743".toByteArray())
                if (withVideo) blocks += Triple(0x0a30, "MotionPhoto_Data", mp4)
            } else if (withVideo) {
                out.write(mp4)
            }
            val positions = mutableListOf<Pair<Int, Int>>()
            for ((type, name, payload) in blocks) {
                val at = out.size()
                out.write(le16(0) + le16(type) + le32(name.length) + name.toByteArray() + payload)
                positions += at to (out.size() - at)
            }
            if (withBlocks) {
                val directory = out.size()
                out.write("SEFH".toByteArray() + le32(107) + le32(blocks.size))
                blocks.forEachIndexed { index, (type, _, _) ->
                    out.write(le16(0) + le16(type) + le32(directory - positions[index].first) + le32(positions[index].second))
                }
                out.write(le32(12 + 12 * blocks.size) + "SEFT".toByteArray())
            }
            return out.toByteArray()
        }

        private fun segment(out: ByteArrayOutputStream, marker: Int, payload: ByteArray) {
            out.write(byteArrayOf(0xFF.toByte(), marker.toByte()))
            out.write(byteArrayOf(((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()))
            out.write(payload)
        }
    }

    private companion object {
        /** What Samsung's trailer adds after the video in [MotionFile]: its directory of two blocks and its end. */
        const val SEF_SIZE = 12 + 12 * 2 + 8

        fun mp4(mdat: Int): ByteArray {
            val ftyp = be32(24) + "ftypmp42".toByteArray() + be32(0) + "isommp42".toByteArray()
            val data = be32(8 + mdat) + "mdat".toByteArray() + ByteArray(mdat) { (it % 251).toByte() }
            val moov = be32(8 + 100) + "moov".toByteArray() + ByteArray(100)
            return ftyp + data + moov
        }

        fun be32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        fun le16(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    }
}
