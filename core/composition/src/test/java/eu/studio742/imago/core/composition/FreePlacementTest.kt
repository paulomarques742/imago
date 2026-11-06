package eu.studio742.imago.core.composition

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FreePlacementTest {
    private val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.2f, .3f, .4f, .2f)),
        0, ShapeKind.RECTANGLE)

    @Test fun `position is free while dimensions and finite coordinates remain guarded`() {
        val exterior = NormalizedRect(-200f, 200f, 30f, 30f)
        val bounds = shape.editableBounds(exterior, 2)
        assertEquals(-200f, bounds.x, 0f)
        assertEquals(200f, bounds.y, 0f)
        assertEquals(2f, bounds.width, 0f)
        assertEquals(1f, bounds.height, 0f)
        val invalid = shape.editableBounds(NormalizedRect(Float.NaN, Float.POSITIVE_INFINITY, .001f, .001f), 2)
        assertEquals(shape.transform.bounds.x, invalid.x, 0f)
        assertEquals(shape.transform.bounds.y, invalid.y, 0f)
        assertEquals(MIN_ELEMENT_SIZE, invalid.width, 0f)
        assertEquals(MIN_ELEMENT_SIZE, invalid.height, 0f)
    }

    @Test fun `limited corner resize retains its opposite anchor outside the page`() {
        val original = NormalizedRect(-2f, -3f, .4f, .2f)
        val resized = original.resizedFrom(ResizeCorner.TOP_LEFT, -20f, -20f, maxWidth = 1f, maxHeight = 1f)
        assertEquals(original.right, resized.right, .0001f)
        assertEquals(original.bottom, resized.bottom, .0001f)
        assertEquals(1f, resized.width, .0001f)
    }

    @Test fun `limited scaling retains aspect and centre outside the page`() {
        val original = NormalizedRect(-2f, 3f, .4f, .2f)
        val scaled = original.scaledAboutCentre(100f, maxWidth = 1f, maxHeight = 1f)
        assertEquals(2f, scaled.width / scaled.height, .0001f)
        assertEquals(original.x + original.width / 2, scaled.x + scaled.width / 2, .0001f)
        assertEquals(original.y + original.height / 2, scaled.y + scaled.height / 2, .0001f)
    }

    @Test fun `legacy repeats acquire their original page before moving across a boundary`() {
        val original = shape.copy(transform = shape.transform.copy(bounds = NormalizedRect(1.2f, .3f, .4f, .2f)),
            repeatOnPages = setOf(0, 1))
        val moved = original.withTransform(original.transform.copy(bounds = original.transform.bounds.copy(x = -.4f)))
        val restored = Json.decodeFromString<CompositionElement>(Json.encodeToString(moved))
        assertEquals(1, restored.repeatOriginPage())
        assertEquals(-1.4f, restored.boundsOnPage(0).x, .0001f)
        assertEquals(-.4f, restored.boundsOnPage(1).x, .0001f)
    }

    @Test fun `rotated coverage uses the physical aspect of the page`() {
        val rotated = shape.copy(transform = shape.transform.copy(rotationDegrees = 90f))
        val bounds = rotated.visualBounds(aspect = .5f)
        assertEquals(.4f, bounds.width, .0001f)
        assertEquals(.2f, bounds.height, .0001f)
    }
}
