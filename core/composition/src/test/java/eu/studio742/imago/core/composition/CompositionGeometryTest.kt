package eu.studio742.imago.core.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionGeometryTest {
    private val element = CompositionElement.Shape(
        id = "shape",
        transform = ElementTransform(NormalizedRect(.75f, .2f, .5f, .4f)),
        zIndex = 0,
        kind = ShapeKind.RECTANGLE,
    )

    @Test fun detectsAndSlicesElementsAcrossAPageBoundary() {
        assertEquals(setOf(1), element.crossedBoundaries())
        assertEquals(.25f, checkNotNull(element.transform.bounds.intersectionWithPage(0)).width)
        assertEquals(.25f, checkNotNull(element.transform.bounds.intersectionWithPage(1)).width)
    }

    @Test fun videoNeverCrossesABoundarySemantically() {
        val video = CompositionElement.Video(
            id = "video",
            transform = ElementTransform(NormalizedRect(.8f, 0f, .5f, 1f)),
            zIndex = 0,
            pageIndex = 0,
            media = MediaReference("id", "sum", "clip.mp4", durationMs = 2_000),
            timing = VideoTiming(trimEndMs = 2_000),
        )
        assertTrue(video.crossedBoundaries().isEmpty())
    }

    @Test fun spanningElementBlocksPageReorder() {
        val project = CompositionProject(
            id = "p",
            name = "P",
            format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("0", 0), CompositionPage("1", 1)),
            elements = listOf(element),
            createdAt = "now",
            updatedAt = "now",
        )
        assertFalse(project.canReorderPage(0))
    }

    @Test fun clampingAtTheFooterPushesTheElementUpInsteadOfThrowing() {
        // The case that crashed: at the footer, the old `coerceIn(.05f, 1f - y)` inverted the range.
        // Now the size is respected and it is the position that gives way — shrinking the user's
        // element would be an unnecessary loss.
        val clamped = NormalizedRect(.4f, .98f, .3f, .3f).clampedToCanvas(pageCount = 1)
        assertEquals(.3f, clamped.height, 1e-4f)
        assertEquals(.7f, clamped.y, 1e-4f)
        assertTrue(clamped.bottom <= 1f + 1e-4f)
    }

    @Test fun clampingGrowsADegenerateRectToTheMinimum() {
        // The low stroke a drawing at the footer produces: almost zero height and y almost 1.
        val clamped = NormalizedRect(.4f, .98f, .3f, .004f).clampedToCanvas(pageCount = 1)
        assertEquals(MIN_ELEMENT_SIZE, clamped.height, 1e-4f)
        assertEquals(.95f, clamped.y, 1e-4f)
        assertTrue(clamped.bottom <= 1f + 1e-4f)
    }

    @Test fun clampingAtTheRightEdgeOfTheLastPageKeepsTheMinimum() {
        val clamped = NormalizedRect(2.99f, .2f, .5f, .3f).clampedToCanvas(pageCount = 3)
        assertTrue(clamped.width >= MIN_ELEMENT_SIZE)
        assertTrue(clamped.right <= 3f + 1e-4f)
    }

    @Test fun clampingSurvivesNonFiniteInput() {
        val clamped = NormalizedRect(Float.NaN, .2f, .5f, .3f).clampedToCanvas(pageCount = 2)
        assertEquals(0f, clamped.x, 1e-4f)
    }

    @Test fun confiningKeepsAVideoInsideItsOwnPage() {
        val confined = NormalizedRect(.8f, 0f, .5f, 1f).confinedToPage(pageIndex = 0)
        assertTrue(confined.x >= 0f)
        assertTrue(confined.right <= 1f + 1e-4f)

        val secondPage = NormalizedRect(.4f, 0f, .5f, 1f).confinedToPage(pageIndex = 1)
        assertTrue(secondPage.x >= 1f)
        assertTrue(secondPage.right <= 2f + 1e-4f)
    }

    @Test fun resizingFromTheBottomRightAnchorsTheTopLeft() {
        val resized = NormalizedRect(.2f, .3f, .4f, .4f).resizedFrom(ResizeCorner.BOTTOM_RIGHT, .1f, .05f)
        assertEquals(.2f, resized.x, 1e-4f)
        assertEquals(.3f, resized.y, 1e-4f)
        assertEquals(.5f, resized.width, 1e-4f)
        assertEquals(.45f, resized.height, 1e-4f)
    }

    @Test fun resizingFromTheTopLeftAnchorsTheBottomRight() {
        val source = NormalizedRect(.2f, .3f, .4f, .4f)
        val resized = source.resizedFrom(ResizeCorner.TOP_LEFT, .1f, .1f)
        assertEquals(source.right, resized.right, 1e-4f)
        assertEquals(source.bottom, resized.bottom, 1e-4f)
        assertEquals(.3f, resized.x, 1e-4f)
    }

    @Test fun resizingNeverCollapsesThroughTheAnchor() {
        val resized = NormalizedRect(.2f, .3f, .4f, .4f).resizedFrom(ResizeCorner.TOP_LEFT, 10f, 10f)
        assertEquals(MIN_ELEMENT_SIZE, resized.width, 1e-4f)
        assertEquals(MIN_ELEMENT_SIZE, resized.height, 1e-4f)
    }

    @Test fun scalingAboutTheCentreKeepsTheCentreAndStaysPositive() {
        val source = NormalizedRect(.2f, .2f, .4f, .4f)
        val scaled = source.scaledAboutCentre(2f)
        assertEquals(source.x + source.width / 2f, scaled.x + scaled.width / 2f, 1e-4f)
        assertEquals(.8f, scaled.width, 1e-4f)

        val collapsed = source.scaledAboutCentre(0f)
        assertTrue(collapsed.width > 0f && collapsed.height > 0f)
    }

    @Test fun rotatingAVectorTurnsItByTheGivenAngle() {
        val (x, y) = rotateVector(dx = 10f, dy = 0f, degrees = 90f)
        assertEquals(0f, x, 1e-3f)
        assertEquals(10f, y, 1e-3f)
    }

    @Test fun counterRotatingUndoesTheElementRotation() {
        // It is this round trip that makes an element rotated 45° follow the finger's axis.
        val (localX, localY) = rotateVector(dx = 12f, dy = 5f, degrees = -45f)
        val (backX, backY) = rotateVector(localX, localY, 45f)
        assertEquals(12f, backX, 1e-3f)
        assertEquals(5f, backY, 1e-3f)
    }

    @Test fun rotationSnapsToCardinalsAndFifteenDegreeSteps() {
        assertEquals(90f, snapRotation(87f), 1e-4f)
        assertEquals(10f, snapRotation(370f), 1e-4f)
        assertEquals(45f, snapRotation(44f), 1e-4f)
        assertEquals(0f, snapRotation(357f), 1e-4f)
    }

    @Test fun blurDivisorGrowsWithTheRadiusAndNeverDividesByZero() {
        assertEquals(1, backgroundBlurDivisor(0f))
        assertEquals(1, backgroundBlurDivisor(1f))
        assertEquals(12, backgroundBlurDivisor(24f))
        assertEquals(24, backgroundBlurDivisor(MAX_BACKGROUND_BLUR))
        // Above the maximum it keeps saturating instead of producing a zero-pixel bitmap.
        assertEquals(24, backgroundBlurDivisor(500f))
    }

    @Test fun degreesNormaliseIntoASingleTurn() {
        assertEquals(10f, normalizeDegrees(730f), 1e-4f)
        assertEquals(350f, normalizeDegrees(-10f), 1e-4f)
        assertEquals(2f, angleDistance(359f, 1f), 1e-4f)
    }
}
