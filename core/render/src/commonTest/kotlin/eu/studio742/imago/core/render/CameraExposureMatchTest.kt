package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Test
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

    @Test fun `past a stop the answer stops, and a JPEG with no mid-tones changes nothing`() {
        val source = developed()
        assertEquals(CameraExposureMatch.LIMIT, CameraExposureMatch.estimate(source, cameraOf(source, 2.5f)), 0f)
        assertEquals(-CameraExposureMatch.LIMIT, CameraExposureMatch.estimate(source, cameraOf(source, -2.5f)), 0f)
        val black = PixelBuffer(16, 16, IntArray(256) { -0x1000000 })
        assertEquals(0f, CameraExposureMatch.estimate(source, black), 0f)
    }

    @Test fun `the profile's inverse gives back the light`() {
        for (light in listOf(0f, 0.01f, 0.09f, 0.5f, 0.99f, 1.5f)) {
            assertEquals(light, RawProfile.inverse(RawProfile.curve(light)), 1e-4f)
        }
    }
}
