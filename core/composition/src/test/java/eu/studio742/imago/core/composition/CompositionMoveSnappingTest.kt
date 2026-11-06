package eu.studio742.imago.core.composition

import org.junit.Assert.*
import org.junit.Test

class CompositionMoveSnappingTest {
    private fun shape(id: String, bounds: NormalizedRect) = CompositionElement.Shape(
        id, ElementTransform(bounds), 0, ShapeKind.RECTANGLE)
    private val moving = shape("moving", NormalizedRect(.23f, .31f, .13f, .17f))
    private fun project(vararg elements: CompositionElement, pages: Int = 1, aspect: Float = 1f) = CompositionProject(
        id = "guides", name = "Guides", format = PageFormat.custom((600 * aspect).toInt(), 600),
        pages = List(pages) { CompositionPage("page-$it", it) }, elements = elements.toList(),
        background = CompositionBackground.Solid(0xFF000000), createdAt = "", updatedAt = "")

    @Test fun `each page margin and centre snaps the corresponding artwork anchor on either axis`() {
        for ((x, coordinateX) in listOf(0f to 0f, .435f to .5f, .87f to 1f)) {
            for ((y, coordinateY) in listOf(0f to 0f, .415f to .5f, .83f to 1f)) {
                val result = CompositionMoveSnapping(project(moving), setOf(moving.id))
                    .moveBy(x + .004f - .23f, y + .004f - .31f, .01f, .01f)
                assertEquals(x, result.bounds.x, .00001f)
                assertEquals(y, result.bounds.y, .00001f)
                val guides = result.guides.filterIsInstance<CompositionGuide.Alignment>()
                assertTrue(guides.any { it.axis == GuideAxis.X && abs(it.coordinate - coordinateX) < .00001f })
                assertTrue(guides.any { it.axis == GuideAxis.Y && abs(it.coordinate - coordinateY) < .00001f })
            }
        }
    }

    @Test fun `visible locked artwork is a reference while hidden or transparent artwork is ignored`() {
        val reference = shape("reference", NormalizedRect(.617f, .1f, .13f, .09f))
        for ((element, expected) in listOf(reference.copy(locked = true) to .617f,
            reference.copy(visible = false) to .613f,
            reference.copy(transform = reference.transform.copy(opacity = 0f)) to .613f)) {
            val result = CompositionMoveSnapping(project(moving, element), setOf(moving.id))
                .moveBy(.613f - .23f, 0f, .005f, .005f)
            assertEquals(expected, result.bounds.x, .00001f)
        }
    }

    @Test fun `vertical alignment does not use an element on an unrelated page`() {
        val reference = shape("reference", NormalizedRect(1.2f, .371f, .11f, .09f))
        val result = CompositionMoveSnapping(project(moving, reference, pages = 2), setOf(moving.id))
            .moveBy(0f, .367f - .31f, .005f, .005f)
        assertEquals(.367f, result.bounds.y, .00001f)
        assertTrue(result.guides.none { it is CompositionGuide.Alignment && it.axis == GuideAxis.Y })
    }

    @Test fun `rotation uses visible coverage and the physical aspect ratio of the page`() {
        val rotated = moving.copy(transform = ElementTransform(NormalizedRect(.32f, .31f, .1f, .2f), 90f))
        val coverage = rotated.visualBounds(aspect = .5f)
        val result = CompositionMoveSnapping(project(rotated, aspect = .5f), setOf(moving.id))
            .moveBy(.996f - coverage.right, 0f, .01f, .01f)
        assertEquals(1f, result.bounds.right, .00001f)
        assertEquals(.4f, result.bounds.width, .00001f)
        assertEquals(.05f, result.bounds.height, .00001f)
    }

    @Test fun `equal gaps snap between neighbours horizontally and vertically`() {
        for (axis in GuideAxis.entries) {
            fun rect(start: Float, length: Float) = if (axis == GuideAxis.X) NormalizedRect(start, .34f, length, .12f)
                else NormalizedRect(.34f, start, .12f, length)
            val a = shape("a", rect(.1f, .1f))
            val b = shape("b", rect(.74f, .1f))
            val m = shape("moving", rect(.31f, .11f))
            val result = CompositionMoveSnapping(project(m, a, b), setOf(m.id)).moveBy(
                if (axis == GuideAxis.X) .109f else 0f, if (axis == GuideAxis.Y) .109f else 0f, .005f, .005f)
            assertEquals(.415f, if (axis == GuideAxis.X) result.bounds.x else result.bounds.y, .00001f)
            val gaps = result.guides.filterIsInstance<CompositionGuide.Spacing>().filter { it.axis == axis }
            assertEquals(2, gaps.size)
            gaps.forEach { assertEquals(.215f, it.to - it.from, .00001f) }
        }
    }

    @Test fun `an existing gap can be repeated before or after a row`() {
        val a = shape("a", NormalizedRect(.1f, .34f, .1f, .12f))
        val b = shape("b", NormalizedRect(.33f, .34f, .1f, .12f))
        val m = shape("moving", NormalizedRect(.31f, .34f, .11f, .12f))
        for (target in listOf(-.14f, .56f)) {
            val result = CompositionMoveSnapping(project(m, a, b), setOf(m.id))
                .moveBy(target + .004f - .31f, 0f, .005f, .005f)
            assertEquals(target, result.bounds.x, .00001f)
            val gaps = result.guides.filterIsInstance<CompositionGuide.Spacing>().filter { it.axis == GuideAxis.X }
            assertEquals(2, gaps.size)
            gaps.forEach { assertEquals(.13f, it.to - it.from, .00001f) }
        }
    }

    @Test fun `hidden or off row neighbours cannot produce spacing guides`() {
        val a = shape("a", NormalizedRect(.1f, .34f, .1f, .12f))
        val b = shape("b", NormalizedRect(.74f, .34f, .1f, .12f))
        val m = shape("moving", NormalizedRect(.31f, .34f, .11f, .12f))
        for (excluded in listOf(b.copy(visible = false), b.copy(transform = ElementTransform(b.transform.bounds.copy(y = .8f))))) {
            val result = CompositionMoveSnapping(project(m, a, excluded), setOf(m.id)).moveBy(.109f, 0f, .005f, .005f)
            assertTrue(result.guides.none { it is CompositionGuide.Spacing && it.axis == GuideAxis.X })
        }
    }

    @Test fun `small pointer deltas accumulate until the guide releases and preserve fine movement beyond it`() {
        val snapping = CompositionMoveSnapping(project(moving), setOf(moving.id))
        assertEquals(0f, snapping.moveBy(-.226f, 0f, .01f, .01f).bounds.x, .00001f)
        repeat(5) { assertEquals(0f, snapping.moveBy(.002f, 0f, .01f, .01f).bounds.x, .00001f) }
        val released = snapping.moveBy(.003f, 0f, .01f, .01f)
        assertEquals(.017f, released.bounds.x, .00001f)
        assertEquals(.017f, released.dx, .00001f)
        assertEquals(.018f, snapping.moveBy(.001f, 0f, .01f, .01f).bounds.x, .00001f)
        assertEquals(.003f, snapping.moveBy(-.015f, 0f, .01f, .01f).bounds.x, .00001f)
        assertEquals(.004f, snapping.moveBy(.001f, 0f, .01f, .01f).bounds.x, .00001f)
    }

    @Test fun `spacing candidates cannot place the moved artwork over a third neighbour`() {
        val a = shape("a", NormalizedRect(.1f, .34f, .1f, .12f))
        val b = shape("b", NormalizedRect(.33f, .34f, .1f, .12f))
        val obstacle = shape("obstacle", NormalizedRect(.52f, .34f, .15f, .12f))
        val m = shape("moving", NormalizedRect(.31f, .34f, .11f, .12f))
        val result = CompositionMoveSnapping(project(m, a, b, obstacle), setOf(m.id)).moveBy(.254f, 0f, .005f, .005f)
        assertTrue(result.guides.none { it is CompositionGuide.Spacing && it.axis == GuideAxis.X })
    }

    @Test fun `capture distance stays constant in screen pixels at different zooms on both axes`() {
        for (width in listOf(240f, 960f)) for (height in listOf(300f, 1200f)) {
            for (pixels in listOf(5f, 11f)) {
                val result = CompositionMoveSnapping(project(moving), setOf(moving.id))
                    .moveBy(pixels / width - .23f, pixels / height - .31f, 6f / width, 6f / height)
                assertEquals(if (pixels < 6f) 0f else pixels / width, result.bounds.x, .00001f)
                assertEquals(if (pixels < 6f) 0f else pixels / height, result.bounds.y, .00001f)
            }
        }
    }

    @Test fun `all selected members are excluded as references and their rotated union snaps as a block`() {
        val a = shape("a", NormalizedRect(.23f, .31f, .11f, .17f))
        val b = shape("b", NormalizedRect(.64f, .61f, .13f, .17f))
        val result = CompositionMoveSnapping(project(a, b), setOf(a.id, b.id)).moveBy(-.226f, 0f, .01f, .001f)
        assertEquals(0f, result.bounds.x, .00001f)
        assertEquals(.54f, result.bounds.width, .00001f)
        assertEquals(-.23f, result.dx, .00001f)
    }

    @Test fun `repeated artwork uses the grabbed page and other repeated copies can be references`() {
        val m = moving.copy(repeatOnPages = setOf(0, 1))
        val ref = shape("reference", NormalizedRect(.617f, .1f, .13f, .09f)).copy(repeatOnPages = setOf(0, 1))
        val source = project(m, ref, pages = 2)
        val result = CompositionMoveSnapping(source, setOf(m.id), instanceOffsetX = 1f)
            .moveBy(.613f - .23f, 0f, .005f, .005f)
        assertEquals(1.617f, result.bounds.x, .00001f)
        assertEquals(.387f, result.dx, .00001f)
        assertTrue(result.guides.filterIsInstance<CompositionGuide.Alignment>()
            .any { it.axis == GuideAxis.X && abs(it.coordinate - 1.617f) < .00001f })
    }

    private fun abs(value: Float) = kotlin.math.abs(value)
}
