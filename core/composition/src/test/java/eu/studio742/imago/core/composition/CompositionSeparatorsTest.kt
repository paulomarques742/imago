package eu.studio742.imago.core.composition

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CompositionSeparatorsTest {
    private val ids = List(7) { "member-$it" }
    private fun layout(kind: CompositionLayoutKind, count: Int = 4) = CompositionLayout("grid",
        ElementTransform(NormalizedRect(.1f, .2f, .8f, .6f)), layoutTree(ids.take(count), kind),
        kind = kind, margin = .1f, gap = .12f)

    @Test fun `row branches meet at the central separator instead of stopping at their media gaps`() {
        val geometry = layout(CompositionLayoutKind.GRID).geometry(1f)
        val horizontal = geometry.separators.single { it.splitId == "rows" }
        val upper = geometry.separators.single { it.splitId == "row-0" }
        val lower = geometry.separators.single { it.splitId == "row-1" }
        assertEquals(horizontal.y1, upper.y2, .00001f)
        assertEquals(horizontal.y1, lower.y1, .00001f)
        assertTrue(geometry.dividers.single { it.splitId == "row-0" }.y2 < upper.y2)
        assertEquals(geometry.area.y, upper.y1, .00001f)
        assertEquals(geometry.area.bottom, lower.y2, .00001f)
    }

    @Test fun `every separator endpoint meets another separator or the inner boundary for all variants and aspects`() {
        for (aspect in listOf(.5f, 1f, 2f)) for (kind in CompositionLayoutKind.entries) {
            val geometry = layout(kind, 7).let { it.copy(root = it.root.adjustDivider("rows", 0, .1f)) }.geometry(aspect)
            fun close(a: Float, b: Float) = abs(a - b) < .00001f
            for (line in geometry.separators) for ((x, y) in listOf(line.x1 to line.y1, line.x2 to line.y2)) {
                val boundary = close(x, geometry.area.x) || close(x, geometry.area.right) ||
                    close(y, geometry.area.y) || close(y, geometry.area.bottom)
                val joined = geometry.separators.any { other -> other !== line &&
                    x >= other.x1 - .00001f && x <= other.x2 + .00001f &&
                    y >= other.y1 - .00001f && y <= other.y2 + .00001f }
                assertTrue("Unconnected endpoint in $kind at $aspect: ($x, $y)", boundary || joined)
            }
        }
    }

    @Test fun `stroke thickness never changes the media boxes or the divider hit targets`() {
        val source = layout(CompositionLayoutKind.FEATURE_LEFT)
        val before = source.geometry(.5625f)
        for (width in listOf(.001f, .01f, .05f)) {
            val after = source.copy(separators = source.separators.copy(width = width)).geometry(.5625f)
            assertEquals(before.cells, after.cells)
            assertEquals(before.dividers, after.dividers)
            assertEquals(before.separators, after.separators)
        }
    }

    @Test fun `derived separator drawings use the inner frame without rewriting saved layouts or members`() {
        val grid = layout(CompositionLayoutKind.FEATURE_TOP).copy(frame =
            ElementTransform(NormalizedRect(.1f, .2f, .8f, .6f), rotationDegrees = 37f))
        val source = CompositionProject(id = "sample", name = "Grid", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = ids.take(4).mapIndexed { i, id ->
                CompositionElement.MediaPlaceholder(id, ElementTransform(NormalizedRect(.1f, .1f, .2f, .2f)), i, groupId = "grid")
            }, createdAt = "", updatedAt = "", layouts = listOf(grid))
        val geometry = grid.geometry(1f)
        val drawing = source.renderElements().filterIsInstance<CompositionElement.Drawing>().single()
        assertEquals(grid.frame.rotationDegrees, drawing.transform.rotationDegrees)
        assertEquals(grid.frame.bounds.width * geometry.area.width, drawing.transform.bounds.width, .00001f)
        assertEquals(grid.frame.bounds.height * geometry.area.height, drawing.transform.bounds.height, .00001f)
        assertEquals(grid.frame.bounds.x + grid.frame.bounds.width / 2f,
            drawing.transform.bounds.x + drawing.transform.bounds.width / 2f, .00001f)
        assertTrue(drawing.points.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(listOf(grid), source.layouts)
        assertFalse(source.elements.any { it.id.startsWith("layout-decoration:") })
    }
}
