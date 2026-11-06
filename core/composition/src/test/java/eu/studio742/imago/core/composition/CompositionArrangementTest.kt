package eu.studio742.imago.core.composition

import org.junit.Assert.*
import org.junit.Test

class CompositionArrangementTest {
    private fun shape(id: String, x: Float, y: Float = .2f, width: Float = .1f, height: Float = .15f,
        group: String? = null, rotation: Float = 0f) = CompositionElement.Shape(id,
        ElementTransform(NormalizedRect(x, y, width, height), rotationDegrees = rotation, opacity = .7f),
        id.first().code, ShapeKind.RECTANGLE, fillArgb = 0x804488AAL, strokeArgb = 0xFF337799, strokeWidth = 12f, groupId = group)
    private val page = NormalizedRect(0f, 0f, 1f, 1f)
    private fun assertContent(before: List<CompositionElement>, after: List<CompositionElement>) {
        assertEquals(before.map { it.id }, after.map { it.id })
        before.zip(after).forEach { (a, b) ->
            assertEquals(a, b.withTransform(a.transform))
            assertEquals(a.transform.bounds.width, b.transform.bounds.width, 0f)
            assertEquals(a.transform.bounds.height, b.transform.bounds.height, 0f)
            assertEquals(a.transform.rotationDegrees, b.transform.rotationDegrees, 0f)
        }
    }
    @Test fun `alignment treats a selected group as an indivisible unit`() {
        val before = listOf(shape("a", .1f, group = "g"), shape("b", .3f, group = "g"), shape("c", .8f))
        val after = before.alignedSelection(-1, null, page, 1f)
        assertEquals(.2f, after[1].transform.bounds.x - after[0].transform.bounds.x, .00001f)
        assertEquals(0f, after[0].transform.bounds.x, .00001f)
        assertEquals(0f, after[2].transform.bounds.x, .00001f)
        assertContent(before, after)
    }
    @Test fun `horizontal distribution uses uniform gaps between groups without changing their internal layout`() {
        val before = listOf(shape("a", .12f, group = "g"), shape("b", .27f, group = "g"),
            shape("c", .5f, width = .2f), shape("d", .8f, width = .1f))
        val after = before.distributedSelection(true, page, 1f)
        val units = after.selectionUnits().map { it.bounds(1f) }.sortedBy { it.x }
        assertEquals(0f, units.first().x, .00001f)
        assertEquals(1f, units.last().right, .00001f)
        assertEquals(units[1].x - units[0].right, units[2].x - units[1].right, .00001f)
        assertEquals(before[1].transform.bounds.x - before[0].transform.bounds.x,
            after[1].transform.bounds.x - after[0].transform.bounds.x, .00001f)
        assertContent(before, after)
    }
    @Test fun `editing members explicitly allows alignment inside a group`() {
        val before = listOf(shape("a", .1f, group = "g"), shape("b", .4f, width = .2f, group = "g"))
        val after = before.alignedSelection(0, null, page, 1f, editingGroupId = "g")
        after.forEach { assertEquals(.5f, it.transform.bounds.x + it.transform.bounds.width / 2f, .00001f) }
        assertContent(before, after)
    }
    @Test fun `rotated artwork aligns its visible edge on a portrait page`() {
        val before = listOf(shape("a", .2f, width = .3f, height = .2f, rotation = 37f))
        val after = before.alignedSelection(-1, 1, page, 9f / 16f)
        assertEquals(0f, after.single().visualBounds(aspect = 9f / 16f).x, .00001f)
        assertEquals(1f, after.single().visualBounds(aspect = 9f / 16f).bottom, .00001f)
        assertContent(before, after)
    }
    @Test fun `vertical distribution preserves order and geometry with mixed sizes and rotations`() {
        val before = listOf(shape("c", .2f, .7f, height = .1f), shape("a", .1f, .1f, height = .2f, rotation = 20f),
            shape("b", .3f, .45f, height = .15f))
        val reference = NormalizedRect(0f, .1f, 1f, .8f)
        val after = before.distributedSelection(false, reference, 1f)
        val boxes = after.map { it.visualBounds(aspect = 1f) }.sortedBy { it.y }
        assertEquals(.1f, boxes.first().y, .00001f)
        assertEquals(.9f, boxes.last().bottom, .00001f)
        assertEquals(boxes[1].y - boxes[0].bottom, boxes[2].y - boxes[1].bottom, .00001f)
        assertContent(before, after)
    }
    @Test fun `an element ID equal to a group ID never merges their selection units`() {
        val before = listOf(shape("a", .1f, group = "g"), shape("b", .3f, group = "g"), shape("g", .7f))
        assertEquals(2, before.selectionUnits().size)
        assertEquals(3, before.selectionUnits(editingGroupId = "g").size)
    }
}
