package eu.studio742.imago.core.composition

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CompositionResizeSnappingTest {
    private fun shape(id: String, bounds: NormalizedRect) = CompositionElement.Shape(id, ElementTransform(bounds), 0, ShapeKind.RECTANGLE)
    private val moving = shape("moving", NormalizedRect(.2f, .2f, .2f, .2f))
    private fun project(vararg elements: CompositionElement, aspect: Float = 1f, pages: Int = 1) = CompositionProject(
        id = "resize", name = "Resize guides", format = PageFormat.custom((600 * aspect).toInt(), 600),
        pages = List(pages) { CompositionPage("page-$it", it) }, elements = elements.toList(),
        background = CompositionBackground.Solid(0xFF000000), createdAt = "", updatedAt = "")
    private fun opposite(element: CompositionElement, corner: ResizeCorner, aspect: Float): Pair<Float, Float> {
        val b = element.transform.bounds
        val localX = (if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT) .5f else -.5f) * b.width
        val localY = (if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT) .5f else -.5f) * b.height
        val (x, y) = rotateVector(localX * aspect, localY, element.transform.rotationDegrees)
        return b.x + b.width / 2f + x / aspect to b.y + b.height / 2f + y
    }
    private fun assertAnchor(before: CompositionElement, after: CompositionElement, corner: ResizeCorner, aspect: Float = 1f) {
        val a = opposite(before, corner, aspect)
        val b = opposite(after, corner, aspect)
        assertEquals(a.first, b.first, .00002f)
        assertEquals(a.second, b.second, .00002f)
    }

    @Test fun `all four corners snap to page edges while their opposite corner stays fixed`() {
        for (corner in ResizeCorner.entries) {
            val left = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT
            val top = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT
            val result = CompositionResizeSnapping(project(moving), setOf(moving.id), corner)
                .resizeBy(if (left) -.196f else .596f, if (top) -.196f else .596f, .01f, .01f)
            val resized = result.elements.single()
            val b = resized.transform.bounds
            assertEquals(if (left) 0f else 1f, if (left) b.x else b.right, .00002f)
            assertEquals(if (top) 0f else 1f, if (top) b.y else b.bottom, .00002f)
            assertAnchor(moving, resized, corner)
            assertTrue(result.guides.any { it is CompositionGuide.Alignment && it.axis == GuideAxis.X })
            assertTrue(result.guides.any { it is CompositionGuide.Alignment && it.axis == GuideAxis.Y })
        }
    }

    @Test fun `the centre of the resized artwork can align without moving its fixed corner`() {
        val result = CompositionResizeSnapping(project(moving), setOf(moving.id), ResizeCorner.BOTTOM_RIGHT)
            .resizeBy(.398f, 0f, .005f, .005f)
        val element = result.elements.single()
        assertEquals(.5f, element.transform.bounds.x + element.transform.bounds.width / 2f, .00002f)
        assertAnchor(moving, element, ResizeCorner.BOTTOM_RIGHT)
        assertTrue(result.guides.filterIsInstance<CompositionGuide.Alignment>().any { it.axis == GuideAxis.X && it.coordinate == .5f })
    }

    @Test fun `locked visible references participate while hidden and transparent ones do not`() {
        val m = moving.copy(transform = ElementTransform(NormalizedRect(.23f, .31f, .13f, .17f)))
        val reference = shape("reference", NormalizedRect(.617f, .1f, .13f, .09f))
        for ((ref, expected) in listOf(reference.copy(locked = true) to .617f, reference.copy(visible = false) to .613f,
            reference.copy(transform = reference.transform.copy(opacity = 0f)) to .613f)) {
            val result = CompositionResizeSnapping(project(m, ref), setOf(m.id), ResizeCorner.BOTTOM_RIGHT)
                .resizeBy(.253f, 0f, .005f, .005f)
            assertEquals(expected, result.elements.single().transform.bounds.right, .00002f)
            assertAnchor(m, result.elements.single(), ResizeCorner.BOTTOM_RIGHT)
        }
    }

    @Test fun `rotated resize aligns its visible edge in physical portrait and landscape coordinates`() {
        for (aspect in listOf(.5f, 1.5f)) for (corner in ResizeCorner.entries) {
            val m = moving.copy(transform = moving.transform.copy(rotationDegrees = 37f))
            val raw = listOf(m).resizedSelectionFromCorner(corner, .08f, .04f, 1, aspect).single()
            val coverage = raw.visualBounds(aspect = aspect)
            val before = m.visualBounds(aspect = aspect)
            val rightMoves = abs(coverage.right - before.right) > .01f
            val target = (if (rightMoves) coverage.right else coverage.x) + .002f
            val reference = shape("reference", NormalizedRect(target, -.4f, .11f, .09f))
            val result = CompositionResizeSnapping(project(m, reference, aspect = aspect), setOf(m.id), corner)
                .resizeBy(.08f, .04f, .005f, .005f)
            val resized = result.elements.single()
            val shown = resized.visualBounds(aspect = aspect)
            assertEquals("Aspect $aspect, corner $corner", target, if (rightMoves) shown.right else shown.x, .0001f)
            assertAnchor(m, resized, corner, aspect)
            assertEquals(37f, resized.transform.rotationDegrees, 0f)
        }
    }

    @Test fun `thin rotated artwork uses its real visual width without applying a model size minimum to guides`() {
        val m = moving.copy(transform = ElementTransform(NormalizedRect(.2f, .2f, .2f, .05f), 90f))
        val raw = listOf(m).resizedSelectionFromCorner(ResizeCorner.BOTTOM_RIGHT, .04f, .02f, 1, 2f).single()
        val target = raw.visualBounds(aspect = 2f).x + .002f
        val reference = shape("reference", NormalizedRect(target, -.4f, .11f, .09f))
        val result = CompositionResizeSnapping(project(m, reference, aspect = 2f), setOf(m.id), ResizeCorner.BOTTOM_RIGHT)
            .resizeBy(.04f, .02f, .005f, .005f)
        val after = result.elements.single()
        assertEquals(target, after.visualBounds(aspect = 2f).x, .0001f)
        assertTrue(after.visualBounds(aspect = 2f).width < MIN_ELEMENT_SIZE)
        assertAnchor(m, after, ResizeCorner.BOTTOM_RIGHT, 2f)
    }

    @Test fun `group resize preserves the opposite frame corner and each members relative position`() {
        val a = moving.copy(groupId = "group", transform = ElementTransform(NormalizedRect(.2f, .25f, .12f, .17f)))
        val b = a.copy(id = "second", transform = ElementTransform(NormalizedRect(.52f, .55f, .16f, .17f)))
        val original = listOf(a, b).map { it.transform.bounds }.boundingBox()!!
        val result = CompositionResizeSnapping(project(a, b), setOf(a.id, b.id), ResizeCorner.BOTTOM_RIGHT)
            .resizeBy(.316f, .276f, .01f, .01f)
        val resized = result.elements.map { it.transform.bounds }.boundingBox()!!
        assertEquals(original.x, resized.x, .00002f)
        assertEquals(original.y, resized.y, .00002f)
        assertEquals(1f, resized.right, .00002f)
        assertEquals(1f, resized.bottom, .00002f)
        for ((old, next) in listOf(a, b).zip(result.elements)) {
            assertEquals((old.transform.bounds.x - original.x) / original.width,
                (next.transform.bounds.x - resized.x) / resized.width, .00002f)
            assertEquals((old.transform.bounds.y - original.y) / original.height,
                (next.transform.bounds.y - resized.y) / resized.height, .00002f)
            assertEquals(old.transform.bounds.width / original.width, next.transform.bounds.width / resized.width, .00002f)
        }
    }

    @Test fun `resize tolerance is constant in screen pixels at different zooms`() {
        for (width in listOf(240f, 960f)) for (pixels in listOf(5f, 11f)) {
            val result = CompositionResizeSnapping(project(moving), setOf(moving.id), ResizeCorner.BOTTOM_RIGHT)
                .resizeBy(.6f - pixels / width, 0f, 6f / width, 6f / width)
            assertEquals(if (pixels < 6f) 1f else 1f - pixels / width, result.elements.single().transform.bounds.right, .00002f)
            assertAnchor(moving, result.elements.single(), ResizeCorner.BOTTOM_RIGHT)
        }
    }

    @Test fun `resize accumulates small movements through a guide and permits a fine correction back`() {
        val snapping = CompositionResizeSnapping(project(moving), setOf(moving.id), ResizeCorner.BOTTOM_RIGHT)
        assertEquals(1f, snapping.resizeBy(.596f, 0f, .01f, .01f).elements.single().transform.bounds.right, .00002f)
        repeat(5) { assertEquals(1f, snapping.resizeBy(-.002f, 0f, .01f, .01f).elements.single().transform.bounds.right, .00002f) }
        assertEquals(.983f, snapping.resizeBy(-.003f, 0f, .01f, .01f).elements.single().transform.bounds.right, .00002f)
        val fine = snapping.resizeBy(.014f, 0f, .01f, .01f)
        assertEquals(.997f, fine.elements.single().transform.bounds.right, .00002f)
        assertTrue(fine.guides.none { it is CompositionGuide.Alignment && it.axis == GuideAxis.X })
    }

    @Test fun `resize can equalise both gaps between neighbours with the opposite edge fixed`() {
        val a = shape("left", NormalizedRect(.1f, .34f, .1f, .12f))
        val b = shape("right", NormalizedRect(.74f, .34f, .1f, .12f))
        val m = shape("moving", NormalizedRect(.3f, .34f, .11f, .12f))
        val result = CompositionResizeSnapping(project(a, m, b), setOf(m.id), ResizeCorner.BOTTOM_RIGHT)
            .resizeBy(.226f, 0f, .005f, .005f)
        assertEquals(.64f, result.elements.single().transform.bounds.right, .00002f)
        assertAnchor(m, result.elements.single(), ResizeCorner.BOTTOM_RIGHT)
        val gaps = result.guides.filterIsInstance<CompositionGuide.Spacing>().filter { it.axis == GuideAxis.X }
        assertEquals(2, gaps.size)
        gaps.forEach { assertEquals(.1f, it.to - it.from, .00002f) }
    }

    @Test fun `size limits stay enforced without false guides and video page timing remains intact`() {
        val video = CompositionElement.Video("clip", moving.transform, 0, 0,
            MediaReference("clip", "sum", "clip.mp4", durationMs = 2_000), VideoTiming(trimEndMs = 2_000))
        val result = CompositionResizeSnapping(project(video, pages = 2), setOf(video.id), ResizeCorner.BOTTOM_RIGHT)
            .resizeBy(10f, 10f, .01f, .01f)
        val resized = result.elements.single() as CompositionElement.Video
        assertEquals(1f, resized.transform.bounds.width, .00002f)
        assertEquals(1f, resized.transform.bounds.height, .00002f)
        assertEquals(video.pageIndex, resized.pageIndex)
        assertEquals(video.timing, resized.timing)
        assertAnchor(video, resized, ResizeCorner.BOTTOM_RIGHT)
        assertTrue(result.guides.isEmpty())
    }

    @Test fun `repeated artwork retains its origin when resized against a displayed page edge`() {
        val repeated = moving.copy(repeatOnPages = setOf(0, 1))
        val result = CompositionResizeSnapping(project(repeated, pages = 2), setOf(moving.id), ResizeCorner.BOTTOM_RIGHT, 1f)
            .resizeBy(.596f, 0f, .01f, .01f)
        val resized = result.elements.single()
        assertEquals(2f, resized.boundsOnPage(1).right, .00002f)
        assertEquals(0, resized.repeatOriginPage())
        assertAnchor(repeated, resized, ResizeCorner.BOTTOM_RIGHT)
        assertTrue(result.guides.filterIsInstance<CompositionGuide.Alignment>().any { it.axis == GuideAxis.X && it.coordinate == 2f })
    }
}
