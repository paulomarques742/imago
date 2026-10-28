package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {
    private fun solid(rgb: Int, width: Int = 8, height: Int = 4) =
        PixelBuffer(width, height).also { it.pixels.fill((0xFF shl 24) or rgb) }

    @Test fun `a flat grey is one bin in every channel`() {
        val histogram = Histogram.of(solid(0x808080))
        val bin = 128 * Histogram.BINS / 256
        assertEquals(32, histogram.red[bin])
        assertEquals(32, histogram.green[bin])
        assertEquals(32, histogram.blue[bin])
        assertEquals(32, histogram.luminance[bin])
        assertEquals(32, histogram.peak)
        assertEquals(0f, histogram.shadowsClipped, 0f)
        assertEquals(0f, histogram.highlightsClipped, 0f)
    }

    @Test fun `what touches an end of the scale is counted by the test the warning paints with`() {
        val half = PixelBuffer(10, 1)
        for (x in 0 until 10) half.pixels[x] = (0xFF shl 24) or when {
            x < 3 -> 0x000000
            x < 5 -> 0xFFFFFF
            x == 5 -> 0xFF8040 // only the red is cut
            x == 6 -> 0x010101 // the warning's `<= 1`
            else -> 0x404040
        }
        val histogram = Histogram.of(half)
        assertEquals(4 / 10f, histogram.shadowsClipped, 1e-6f)
        assertEquals(3 / 10f, histogram.highlightsClipped, 1e-6f)
        assertTrue(Histogram.LOW == 1 && Histogram.HIGH == 254)
    }

    @Test fun `the clipping warning is off unless asked for`() {
        assertTrue(!ClippingWarning.NONE.isOn)
        assertTrue(ClippingWarning(highlights = true).isOn)
    }

    @Test fun `the composite paints with the thresholds the histogram counts with`() {
        // 254/255 and 1/255, to four digits.
        assertTrue(PhotoShaders.COMPOSITE.contains("greaterThanEqual(color, vec3(0.9961))"))
        assertTrue(PhotoShaders.COMPOSITE.contains("lessThanEqual(color, vec3(0.0039))"))
    }
}
