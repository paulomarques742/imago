package eu.studio742.imago.core.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class EmbeddedLensCorrectionTest {
    /** Strong enough to move pixels of a 64×48 sensor, with the counts Sony's tags lead with. */
    private val sony = mapOf(
        0x7032 to intArrayOf(4, 0, 1000, 3000, 6000),
        0x7035 to intArrayOf(8, 0, 6000, 12000, 20000, 0, -6000, -12000, -20000),
        0x7037 to intArrayOf(4, 0, -800, -2000, -3500),
    )

    /** The distortion and vignetting alone, which the development applies after demosaicing, exactly. */
    private val geometryOnly = sony + (0x7035 to IntArray(9).also { it[0] = 8 })

    /** The aberration alone, which moves the sensor's red and blue sites before demosaicing. */
    private val aberrationOnly = sony + (0x7032 to intArrayOf(4, 0, 0, 0, 0)) + (0x7037 to intArrayOf(4, 0, 0, 0, 0))

    @Test fun `the corrections are read from the directories without the image data`() {
        withFile(syntheticDng(orientation = 1, signedShortTags = sony)) { file ->
            val correction = requireNotNull(EmbeddedLensCorrection.read(file))
            assertEquals(4, correction.knots)
            assertArrayEquals(intArrayOf(0, -800, -2000, -3500), correction.distortion)
            assertArrayEquals(intArrayOf(0, 6000, 12000, 20000), correction.chromaRed)
            assertArrayEquals(intArrayOf(0, -6000, -12000, -20000), correction.chromaBlue)
            assertArrayEquals(intArrayOf(0, 1000, 3000, 6000), correction.vignetting)
        }
    }

    @Test fun `a file without the three tags, or with counts that disagree, carries none`() {
        withFile(syntheticDng(orientation = 1)) { assertNull(EmbeddedLensCorrection.read(it)) }
        withFile(syntheticDng(orientation = 1, signedShortTags = sony - 0x7032)) { assertNull(EmbeddedLensCorrection.read(it)) }
        val wrongAberration = sony + (0x7035 to intArrayOf(4, 0, 1, 2, 3))
        withFile(syntheticDng(orientation = 1, signedShortTags = wrongAberration)) { assertNull(EmbeddedLensCorrection.read(it)) }
        val oneKnot = mapOf(0x7032 to intArrayOf(1, 0), 0x7035 to intArrayOf(2, 0, 0), 0x7037 to intArrayOf(1, 0))
        withFile(syntheticDng(orientation = 1, signedShortTags = oneKnot)) { assertNull(EmbeddedLensCorrection.read(it)) }
        withFile(byteArrayOf(1, 2, 3)) { assertNull(EmbeddedLensCorrection.read(it)) }
    }

    @Test fun `zero values change nothing, and Sony's units are those of the reference implementation`() {
        val flat = EmbeddedLensCorrection(IntArray(3), IntArray(3), IntArray(3), IntArray(3))
        for (i in 0 until 3) {
            assertEquals(1f, flat.gain(i), 0f)
            for (channel in 0..2) assertEquals(1f, flat.factor(channel, i), 0f)
        }
        assertEquals(1f, flat.scale(6000, 4000), 0f)
        val knots = EmbeddedLensCorrection(intArrayOf(0, -16384 / 10), intArrayOf(0, 2097), intArrayOf(0, 0), intArrayOf(0, 8192))
        // 2^-14 per unit of distortion, 2^-21 of aberration on top of it, and the vignetting through 2^(1/2 − 2^(v·2^-13 − 1)).
        assertEquals(0.9f, knots.factor(1, 1), 1e-4f)
        assertEquals(knots.factor(1, 1) * (1f + 2097f / 2097152f), knots.factor(0, 1), 1e-6f)
        assertEquals(Math.pow(2.0, -0.5).toFloat(), knots.gain(1), 1e-6f)
        assertEquals(0.5f, knots.knot(0), 0f)
        assertEquals(1.5f, knots.knot(1), 0f)
    }

    @Test fun `the camera's corrections are only applied when asked, and the file says it has them`() {
        withFile(syntheticDng(orientation = 1, signedShortTags = sony)) { file ->
            val plain = requireNotNull(RawDecoder.decode(file))
            assertTrue(plain.fileLensCorrection)
            val corrected = requireNotNull(RawDecoder.decode(file, lensCorrection = true))
            assertTrue(corrected.fileLensCorrection)
            assertFalse(plain.rgb.contentEquals(corrected.rgb))
        }
        withFile(syntheticDng(orientation = 1)) { file ->
            val plain = requireNotNull(RawDecoder.decode(file))
            assertFalse(plain.fileLensCorrection)
            assertArrayEquals(plain.rgb, requireNotNull(RawDecoder.decode(file, lensCorrection = true)).rgb)
        }
    }

    @Test fun `the corrected development is the original sampled along its radius`() {
        for (orientation in listOf(1, 6)) withFile(syntheticDng(orientation, signedShortTags = geometryOnly)) { file ->
            val correction = requireNotNull(EmbeddedLensCorrection.read(file))
            val original = requireNotNull(RawDecoder.decode(file))
            val corrected = requireNotNull(RawDecoder.decode(file, lensCorrection = true))
            assertEquals(original.width to original.height, corrected.width to corrected.height)
            assertMatchesModel(correction, original, corrected, outputScale = 1f)
        }
    }

    @Test fun `a bounded preview applies the same corrections to the half-size development`() {
        withFile(syntheticDng(orientation = 1, signedShortTags = geometryOnly)) { file ->
            val correction = requireNotNull(EmbeddedLensCorrection.read(file))
            val half = requireNotNull(RawDecoder.decode(file, halfSize = true))
            val preview = requireNotNull(RawDecoder.decodePreview(file, maxSide = 16, lensCorrection = true))
            assertEquals(16 to 12, preview.width to preview.height)
            assertMatchesModel(correction, half, preview, outputScale = 16f / half.width)
        }
    }

    @Test fun `the aberration moves red and blue on the sensor and leaves green where it was`() {
        withFile(syntheticDng(orientation = 1, signedShortTags = aberrationOnly)) { file ->
            val plain = requireNotNull(RawDecoder.decode(file))
            val corrected = requireNotNull(RawDecoder.decode(file, lensCorrection = true))
            // The sensor ramps up to the right and down the rows. Red, magnified by the lens, is read from
            // further out than green: smaller values towards the top-left corner, larger towards the
            // bottom-right; blue, shrunk, the other way. Green moves only by the zoom that keeps the frame
            // filled, which counts the red that reaches past the edge.
            fun shift(channel: Int, xs: IntRange, ys: IntRange): Float {
                var total = 0f
                var count = 0
                for (y in ys) for (x in xs) {
                    val at = (y * plain.width + x) * 3 + channel
                    total += corrected.sample(at) - plain.sample(at)
                    count++
                }
                return total / count
            }
            val topLeft = 2..9
            val bottomRight = (plain.width - 10)..(plain.width - 3)
            val topRows = 2..9
            val bottomRows = (plain.height - 10)..(plain.height - 3)
            val greenTop = shift(1, topLeft, topRows)
            val greenBottom = shift(1, bottomRight, bottomRows)
            val redTop = shift(0, topLeft, topRows) - greenTop
            val redBottom = shift(0, bottomRight, bottomRows) - greenBottom
            val blueTop = shift(2, topLeft, topRows) - greenTop
            val blueBottom = shift(2, bottomRight, bottomRows) - greenBottom
            assertTrue("red against green $redTop", redTop < -5e-4f)
            assertTrue("red against green $redBottom", redBottom > 5e-4f)
            assertTrue("blue against green $blueTop", blueTop > 5e-4f)
            assertTrue("blue against green $blueBottom", blueBottom < -5e-4f)
        }
    }

    private fun assertMatchesModel(correction: EmbeddedLensCorrection, original: RawImage, corrected: RawImage, outputScale: Float) {
        val width = original.width
        val height = original.height
        val scale = correction.scale(width, height)
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val diagonal = hypot(halfWidth, halfHeight)
        var moved = 0f
        for (y in 0 until corrected.height) for (x in 0 until corrected.width) for (channel in 0..2) {
            val dx = (x + 0.5f) / outputScale - halfWidth
            val dy = (y + 0.5f) / outputScale - halfHeight
            val radius = hypot(dx, dy) / diagonal
            val factor = correction.sampledFactor(channel, radius, scale)
            val expected = bilinear(original, halfWidth + dx * factor, halfHeight + dy * factor, channel) /
                correction.gainAt(radius * factor)
            val actual = corrected.sample((y * corrected.width + x) * 3 + channel)
            assertEquals("($x, $y) channel $channel", expected, actual, 2e-3f + abs(expected) * 2e-3f)
            if (outputScale == 1f) moved = max(moved, abs(actual - original.sample((y * width + x) * 3 + channel)))
        }
        if (outputScale == 1f) assertTrue("The test corrections must move the image: $moved", moved > 0.02f)
    }

    private fun bilinear(image: RawImage, centreX: Float, centreY: Float, channel: Int): Float {
        val sx = (centreX - 0.5f).coerceIn(0f, image.width - 1f)
        val sy = (centreY - 0.5f).coerceIn(0f, image.height - 1f)
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val x1 = minOf(x0 + 1, image.width - 1)
        val y1 = minOf(y0 + 1, image.height - 1)
        val fx = sx - x0
        val fy = sy - y0
        fun at(x: Int, y: Int) = image.sample((y * image.width + x) * 3 + channel)
        val top = at(x0, y0) * (1f - fx) + at(x1, y0) * fx
        val bottom = at(x0, y1) * (1f - fx) + at(x1, y1) * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun withFile(bytes: ByteArray, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("imago-lens-")
        val file = directory.resolve("photo.raw").toFile()
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            assertTrue("The reader must not retain an open file handle.", file.delete())
            Files.delete(directory)
        }
    }
}
