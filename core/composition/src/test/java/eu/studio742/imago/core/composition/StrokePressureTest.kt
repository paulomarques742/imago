package eu.studio742.imago.core.composition

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokePressureTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The contract that keeps old projects readable: a `StrokePoint` saved before pressure existed
     * has to come back as a full-force stroke, not as a zero-force stroke.
     */
    @Test
    fun `points saved before pressure existed read back at full force`() {
        val point = json.decodeFromString<StrokePoint>("""{"x":0.25,"y":0.5}""")
        assertEquals(0.25f, point.x, 0f)
        assertEquals(0.5f, point.y, 0f)
        assertEquals(1f, point.pressure, 0f)
    }

    @Test
    fun `full pressure keeps the declared width`() {
        assertEquals(12f, strokeWidthAt(12f, 1f), 1e-4f)
    }

    /** Without pressure the stroke thins, but never disappears. */
    @Test
    fun `no pressure still leaves a visible stroke`() {
        val width = strokeWidthAt(12f, 0f)
        assertTrue("width $width should be positive", width > 0f)
        assertTrue("width $width should be smaller than the declared one", width < 12f)
    }

    @Test
    fun `pressure outside the unit range is clamped`() {
        assertEquals(strokeWidthAt(10f, 1f), strokeWidthAt(10f, 4f), 1e-4f)
        assertEquals(strokeWidthAt(10f, 0f), strokeWidthAt(10f, -3f), 1e-4f)
    }

    @Test
    fun `width grows with pressure`() {
        assertTrue(strokeWidthAt(10f, 0.2f) < strokeWidthAt(10f, 0.8f))
    }

    /**
     * What decides between drawing a single `Path` or a line per segment. A finger stroke, or an old
     * stroke, has constant pressure and should not pay for the expensive path.
     */
    @Test
    fun `constant pressure does not ask for the per-segment path`() {
        val finger = listOf(StrokePoint(0f, 0f), StrokePoint(1f, 1f), StrokePoint(0f, 1f))
        assertFalse(finger.hasVariablePressure())
    }

    @Test
    fun `varying pressure asks for the per-segment path`() {
        val stylus = listOf(StrokePoint(0f, 0f, 0.2f), StrokePoint(1f, 1f, 0.9f))
        assertTrue(stylus.hasVariablePressure())
    }

    @Test
    fun `a stroke too short to draw never asks for the per-segment path`() {
        assertFalse(emptyList<StrokePoint>().hasVariablePressure())
        assertFalse(listOf(StrokePoint(0f, 0f, 0.3f)).hasVariablePressure())
    }
}
