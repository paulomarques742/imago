package eu.studio742.imago.feature.composer

import androidx.compose.ui.geometry.Offset
import eu.studio742.imago.core.composition.ShapeKind
import org.junit.Test
import org.junit.Assert.assertEquals

class ComposerShapePlacementTest {
    private val pageWidth = 400f
    private val pageHeight = 800f

    private fun place(kind: ShapeKind, start: Offset, end: Offset, pages: Int = 2) =
        shapePlacement(kind, start, end, pageWidth, pageHeight, pages, tapSlopPx = 8f)

    @Test
    fun `a drag makes a perfect square from the corner it started in`() {
        val placed = place(ShapeKind.RECTANGLE, Offset(100f, 200f), Offset(300f, 260f))
        val b = placed.bounds
        // The side is the longer move, 200 px, in both directions.
        assertEquals(200f, b.width * pageWidth, .01f)
        assertEquals(200f, b.height * pageHeight, .01f)
        assertEquals(100f, b.x * pageWidth, .01f)
        assertEquals(200f, b.y * pageHeight, .01f)
    }

    @Test
    fun `dragging up and left grows the circle that way`() {
        val b = place(ShapeKind.ELLIPSE, Offset(300f, 400f), Offset(250f, 200f)).bounds
        assertEquals(200f, b.width * pageWidth, .01f)
        assertEquals(100f, b.x * pageWidth, .01f)
        assertEquals(200f, b.y * pageHeight, .01f)
    }

    @Test
    fun `a tap centres a default square on the finger`() {
        val b = place(ShapeKind.RECTANGLE, Offset(300f, 400f), Offset(301f, 401f)).bounds
        assertEquals(120f, b.width * pageWidth, .01f)
        assertEquals(120f, b.height * pageHeight, .01f)
        assertEquals(300f, (b.x + b.width / 2) * pageWidth, .01f)
        assertEquals(400f, (b.y + b.height / 2) * pageHeight, .01f)
    }

    @Test
    fun `a shape started at the edge is pushed inside, not cut`() {
        val b = place(ShapeKind.RECTANGLE, Offset(795f, 790f), Offset(795f, 795f)).bounds
        assertEquals(120f, b.width * pageWidth, .01f)
        assertEquals(800f, (b.x + b.width) * pageWidth, .01f)
        assertEquals(800f, (b.y + b.height) * pageHeight, .01f)
    }

    @Test
    fun `a line runs between the two points and is turned to fit`() {
        val placed = place(ShapeKind.LINE, Offset(100f, 100f), Offset(100f, 300f))
        assertEquals(90f, placed.rotationDegrees, .01f)
        assertEquals(200f, placed.bounds.width * pageWidth, .01f)
        assertEquals(100f, (placed.bounds.x + placed.bounds.width / 2) * pageWidth, .01f)
        assertEquals(200f, (placed.bounds.y + placed.bounds.height / 2) * pageHeight, .01f)
    }
}
