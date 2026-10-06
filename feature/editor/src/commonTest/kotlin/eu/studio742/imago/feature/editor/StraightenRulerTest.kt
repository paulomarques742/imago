package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StraightenRulerTest {
    /** The value comes from the distance travelled, and so accumulates along the drag. */
    @Test
    fun `the drag accumulates from the current value`() {
        var graus = 0f
        repeat(10) { graus = straightenFromDrag(graus, deltaPx = 9f, pixelsPerDegree = 9f) }
        assertEquals(10f, graus, 0.0001f)
    }

    /** Dragging the other way undoes, and does not add in absolute value. */
    @Test
    fun `dragging back undoes`() {
        val ida = straightenFromDrag(0f, deltaPx = 90f, pixelsPerDegree = 9f)
        val volta = straightenFromDrag(ida, deltaPx = -45f, pixelsPerDegree = 9f)
        assertEquals(5f, volta, 0.0001f)
    }

    /** The ruler does not go past ±45°, however much one keeps dragging. */
    @Test
    fun `the ruler stops at the limits`() {
        assertEquals(45f, straightenFromDrag(40f, 100_000f, 9f), 0.0001f)
        assertEquals(-45f, straightenFromDrag(-40f, -100_000f, 9f), 0.0001f)
    }

    /**
     * The zero detent. It holds what passes close — hitting a straight horizon to a tenth of a degree
     * with the thumb is not possible — but it has to let through whoever really wants to go past, or
     * it would stop being a help and become a wall.
     */
    @Test
    fun `the detent holds at zero but can be crossed`() {
        // A touch-up that landed at 0.2° is pulled to zero.
        assertEquals(0f, straightenFromDrag(0.1f, deltaPx = 0.9f, pixelsPerDegree = 9f), 0.0001f)
        // A decided drag crosses it and comes out the other side.
        val atravessou = straightenFromDrag(2f, deltaPx = -27f, pixelsPerDegree = 9f)
        assertEquals(-1f, atravessou, 0.0001f)
        assertNotEquals(0f, atravessou)
    }

    /** Outside the detent zone, the value is exactly what the distance says. */
    @Test
    fun `outside the detent there is no snapping at all`() {
        assertEquals(-2.5f, straightenFromDrag(0f, deltaPx = -22.5f, pixelsPerDegree = 9f), 0.0001f)
        assertEquals(12.3f, straightenFromDrag(12f, deltaPx = 2.7f, pixelsPerDegree = 9f), 0.0001f)
    }
}
