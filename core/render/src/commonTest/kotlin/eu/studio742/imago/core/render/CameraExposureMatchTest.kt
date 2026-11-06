package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

class CameraExposureMatchTest {
    /** A linear development: a ramp of greys from deep shadow to past white, row by row. */
    private fun developed(width: Int = 64, height: Int = 48) = FloatPixelBuffer(width, height, FloatArray(width * height * 3) { i ->
        val pixel = i / 3
        0.002f * 2f.pow(9f * pixel / (width * height))
    })

    /** What a camera that placed grey [stops] brighter than the profile would have written, as a JPEG's codes. */
    private fun cameraOf(source: FloatPixelBuffer, stops: Float, transpose: Boolean = false): PixelBuffer {
        val width = if (transpose) source.height else source.width
        val height = if (transpose) source.width else source.height
        return PixelBuffer(width, height, IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val from = if (transpose) x * source.width + y else y * source.width + x
            val shown = RawProfile.curve(source.rgb[from * 3] * 2f.pow(stops)).coerceIn(0f, 1f)
            val code = (shown * 255f).roundToInt()
            -0x1000000 or (code shl 16) or (code shl 8) or code
        })
    }

    @Test fun `the exposure a camera's JPEG asks for is found from the mid-tones`() {
        val source = developed()
        for (stops in listOf(-0.8f, -0.3f, 0f, 0.4f, 0.9f)) {
            assertEquals("$stops", stops, CameraExposureMatch.estimate(source, cameraOf(source, stops)), 0.05f)
        }
    }

    @Test fun `a rotated or smaller JPEG gives the same answer`() {
        val source = developed()
        assertEquals(0.5f, CameraExposureMatch.estimate(source, cameraOf(source, 0.5f, transpose = true)), 0.05f)
        val reduced = RecipePixels.reduce(cameraOf(developed(128, 96), 0.5f), 40)
        assertEquals(0.5f, CameraExposureMatch.estimate(source, reduced), 0.08f)
    }

    @Test fun `a JPEG one exposure can repeat is followed past a stop`() {
        val source = developed()
        assertEquals(2.5f, CameraExposureMatch.estimate(source, cameraOf(source, 2.5f)), 0.05f)
        assertEquals(-2.5f, CameraExposureMatch.estimate(source, cameraOf(source, -2.5f)), 0.05f)
    }

    @Test fun `even a steady answer has a bound, and a JPEG with no mid-tones changes nothing`() {
        val source = developed()
        assertEquals(CameraExposureMatch.STEADY_LIMIT, CameraExposureMatch.estimate(source, cameraOf(source, 3.5f)), 0f)
        assertEquals(-CameraExposureMatch.STEADY_LIMIT, CameraExposureMatch.estimate(source, cameraOf(source, -3.5f)), 0f)
        val black = PixelBuffer(16, 16, IntArray(256) { -0x1000000 })
        assertEquals(0f, CameraExposureMatch.estimate(source, black), 0f)
    }

    @Test fun `a JPEG whose tones disagree stops at a stop`() {
        val source = developed()
        // What a phone does to a backlit window: the darker the tone, the more it is lifted.
        val lifted = PixelBuffer(source.width, source.height, IntArray(source.width * source.height) { i ->
            val light = source.rgb[i * 3]
            val stops = 2.2f - 0.5f * log2(light / 0.05f)
            val code = (RawProfile.curve(light * 2f.pow(stops)).coerceIn(0f, 1f) * 255f).roundToInt()
            -0x1000000 or (code shl 16) or (code shl 8) or code
        })
        assertEquals(CameraExposureMatch.LIMIT, CameraExposureMatch.estimate(source, lifted), 0f)
        assertEquals(-CameraExposureMatch.LIMIT, CameraExposureMatch.estimate(source, PixelBuffer(source.width, source.height, IntArray(lifted.pixels.size) { i ->
            val light = source.rgb[i * 3]
            val stops = -2.2f + 0.5f * log2(light / 0.05f)
            val code = (RawProfile.curve(light * 2f.pow(stops)).coerceIn(0f, 1f) * 255f).roundToInt()
            -0x1000000 or (code shl 16) or (code shl 8) or code
        })), 0f)
    }

    /** A development whose every tone stays under the mid-tones once [stops] are added. */
    private fun dark(stops: Float, width: Int = 64, height: Int = 48): FloatPixelBuffer {
        val top = RawProfile.inverse(CameraExposureMatch.LOW - 0.01f) / 2f.pow(stops)
        return FloatPixelBuffer(width, height, FloatArray(width * height * 3) { i -> top / 2f.pow(5f - 5f * (i / 3) / (width * height)) })
    }

    @Test fun `a JPEG too dark for mid-tones is read from its shadows`() {
        val source = dark(0.8f)
        assertEquals(0.8f, CameraExposureMatch.estimate(source, cameraOf(source, 0.8f)), 0.08f)
    }

    @Test fun `shadows that disagree are not believed`() {
        val source = dark(0.8f)
        val top = source.rgb[source.width * source.height * 3 - 1]
        val lifted = PixelBuffer(source.width, source.height, IntArray(source.width * source.height) { i ->
            val light = source.rgb[i * 3]
            val stops = 0.8f - 0.8f * log2(light / top)
            val code = (RawProfile.curve(light * 2f.pow(stops)).coerceIn(0f, 1f) * 255f).roundToInt()
            -0x1000000 or (code shl 16) or (code shl 8) or code
        })
        assertEquals(0f, CameraExposureMatch.estimate(source, lifted), 0f)
    }

    @Test fun `the profile's inverse gives back the light`() {
        for (light in listOf(0f, 0.01f, 0.09f, 0.5f, 0.99f, 1.5f)) {
            assertEquals(light, RawProfile.inverse(RawProfile.curve(light)), 1e-4f)
        }
    }
}
