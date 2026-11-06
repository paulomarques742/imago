package eu.studio742.imago.core.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class CameraToneCurveTest {
    private fun grey(value: Int) = -0x1000000 or (value shl 16) or (value shl 8) or value

    /** Every grey from 0 to 255, spread down the picture and a little across it. */
    private fun ramp(width: Int = 96, height: Int = 64, tone: (Float) -> Float = { it }) =
        PixelBuffer(width, height, IntArray(width * height) { i ->
            val position = (i / width + (i % width) / width.toFloat()) / height
            grey((tone(position) * 255f).roundToInt().coerceIn(0, 255))
        })

    private fun CameraToneCurve.value(input: Int): Int =
        PixelBuffer(1, 1, intArrayOf(grey(input))).also(::applyTo).pixels[0] and 255

    @Test fun `a server image far brighter than the camera's comes down to it`() {
        val camera = ramp { it * it }
        val curve = requireNotNull(CameraToneCurve.between(ramp(), camera))
        for (value in listOf(32, 64, 128, 192)) {
            val expected = (value / 255f).pow(2) * 255f
            assertTrue("$value gave ${curve.value(value)}, not $expected", abs(curve.value(value) - expected) < 6f)
        }
    }

    @Test fun `the same tones change nothing`() {
        val curve = requireNotNull(CameraToneCurve.between(ramp(), ramp()))
        for (value in 0..255) assertTrue("$value", abs(curve.value(value) - value) <= 2)
    }

    @Test fun `size and rotation do not matter`() {
        val server = ramp()
        val small = ramp(160, 106) { it * it }
        val turned = PixelBuffer(small.height, small.width, IntArray(small.pixels.size) { i ->
            val x = i % small.height
            val y = i / small.height
            small.pixels[x * small.width + y]
        })
        val one = requireNotNull(CameraToneCurve.between(server, small))
        val other = requireNotNull(CameraToneCurve.between(server, turned))
        for (value in 0..255) assertEquals("$value", one.value(value), other.value(value))
    }

    @Test fun `the curve never turns tones around`() {
        val curve = requireNotNull(CameraToneCurve.between(ramp { 1f - (1f - it).pow(3) }, ramp { it.pow(2.2f) }))
        for (value in 1..255) assertTrue("$value", curve.value(value) >= curve.value(value - 1))
    }

    @Test fun `the bars of a 4 by 3 thumbnail are not taken for shadows`() {
        val photo = ramp(160, 104) { 0.3f + 0.5f * it }
        val barred = PixelBuffer(160, 120, IntArray(160 * 120) { i ->
            val y = i / 160
            if (y < 8 || y >= 112) grey(1) else photo.pixels[(y - 8) * 160 + i % 160]
        })
        val trimmed = requireNotNull(CameraToneCurve.withoutBars(barred))
        assertEquals(160, trimmed.width)
        assertEquals(104, trimmed.height)
        val server = ramp()
        val withBars = requireNotNull(CameraToneCurve.between(server, barred))
        val without = requireNotNull(CameraToneCurve.between(server, photo))
        for (value in 0..255) assertEquals("$value", without.value(value), withBars.value(value))
    }

    @Test fun `a dark photo keeps its dark edges`() {
        val dark = PixelBuffer(160, 104, IntArray(160 * 104) { i -> grey(4 + (i * 2654435761L ushr 7).toInt().and(31)) })
        val kept = requireNotNull(CameraToneCurve.withoutBars(dark))
        assertEquals(160, kept.width)
        assertEquals(104, kept.height)
    }

    @Test fun `a night sky over the photo is not a bar`() {
        val sky = ramp(160, 104) { if (it < 0.3f) 0f else it }
        val kept = requireNotNull(CameraToneCurve.withoutBars(sky))
        assertEquals(104, kept.height)
    }

    @Test fun `a picture that is all bars says nothing`() {
        assertNull(CameraToneCurve.between(ramp(), PixelBuffer(160, 120, IntArray(160 * 120) { grey(0) })))
    }

    @Test fun `past the last knot a dark photo's highlights do not race to white`() {
        // The server stretched a dark photo: its top tones are far above what the camera shows.
        val curve = requireNotNull(CameraToneCurve.between(ramp { it * 0.9f }, ramp { it * 0.3f }))
        val top = curve.value(255)
        assertTrue("255 gave $top", top < 110)
        assertTrue(curve.value(250) >= curve.value(230))
    }

    @Test fun `alpha stays`() {
        val curve = requireNotNull(CameraToneCurve.between(ramp(), ramp { it * it }))
        val buffer = PixelBuffer(1, 1, intArrayOf(0x40808080))
        curve.applyTo(buffer)
        assertEquals(0x40, buffer.pixels[0] ushr 24)
    }

    @Test fun `curves are told apart and come back from their table`() {
        val one = requireNotNull(CameraToneCurve.between(ramp(), ramp { it * it }))
        val other = requireNotNull(CameraToneCurve.between(ramp(), ramp()))
        assertNotEquals(one.id, other.id)
        assertEquals(one.id, requireNotNull(CameraToneCurve.of(one.table)).id)
        assertNull(CameraToneCurve.of(ByteArray(1)))
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    /** The shape of a JPEG: a header, a frame of [width] × [height], a scan and its end. */
    private fun jpeg(width: Int, height: Int, exif: ByteArray = ByteArray(0)): ByteArray {
        val frame = byteArrayOf(8, (height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(), 1, 1, 0x11, 0)
        val scan = byteArrayOf(0x12, 0xFF.toByte(), 0, 0x34, 0xFF.toByte(), 0xD3.toByte(), 0x56)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xE1, exif) + segment(0xC0, frame) +
            segment(0xDA, ByteArray(4)) + scan + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test fun `a small JPEG in the file's first bytes is found`() {
        val thumbnail = jpeg(160, 120)
        assertArrayEquals(thumbnail, EmbeddedPreview.largestWhole(ByteArray(300) + thumbnail + ByteArray(300)))
    }

    @Test fun `a JPEG inside the EXIF of one cut off by the read is found`() {
        // A RAF: the big JPEG starts the file and carries the small one in its EXIF.
        val thumbnail = jpeg(160, 120)
        val whole = jpeg(4416, 2944, exif = ByteArray(40) + thumbnail + ByteArray(40))
        val read = whole.copyOf(whole.size - 8)
        assertArrayEquals(thumbnail, EmbeddedPreview.largestWhole(read))
    }

    @Test fun `of two whole JPEGs the larger is taken`() {
        val small = jpeg(160, 120)
        val large = jpeg(1616, 1080)
        assertArrayEquals(large, EmbeddedPreview.largestWhole(small + ByteArray(64) + large))
    }

    @Test fun `no JPEG, a cut one or an icon find nothing`() {
        assertNull(EmbeddedPreview.largestWhole(ByteArray(4096)))
        val cut = jpeg(160, 120)
        assertNull(EmbeddedPreview.largestWhole(cut.copyOf(cut.size - 3)))
        assertNull(EmbeddedPreview.largestWhole(jpeg(32, 24)))
    }

    @Test fun `a stuffed byte in the data does not end the image`() {
        assertNotNull(EmbeddedPreview.largestWhole(jpeg(160, 120)))
    }
}
