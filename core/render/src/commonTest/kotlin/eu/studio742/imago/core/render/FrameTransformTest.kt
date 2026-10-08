package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTransformTest {
    private val aspects = listOf(1.5f, 0.75f, 1f, 3f, 1f / 3f)
    private val perspectives = buildList {
        for (vertical in listOf(-100f, -40f, 0f, 25f, 100f)) {
            for (horizontal in listOf(-100f, 0f, 60f)) {
                for (aspect in listOf(-60f, 0f, 80f)) {
                    add(PerspectiveParameters(vertical = vertical, horizontal = horizontal, aspect = aspect))
                }
            }
        }
        add(PerspectiveParameters(vertical = -30f, offsetX = 100f, offsetY = -100f))
        add(PerspectiveParameters(horizontal = 50f, scale = 40f, offsetX = -60f))
    }

    /** With the perspective neutral the matrix is the straighten as it always was: rotation and cover. */
    @Test
    fun aNeutralPerspectiveIsTheStraightenOfBefore() {
        val out = FloatArray(2)
        for (aspect in aspects) {
            for (degrees in listOf(-30f, -5f, 0f, 7f, 45f)) {
                val radians = Math.toRadians(degrees.toDouble())
                val scale = straightenCoverScale(aspect, degrees)
                frameTransform(aspect, degrees, PerspectiveParameters()).map(0.3f, -0.2f, out)
                assertEquals((scale * (kotlin.math.cos(radians) * 0.3 + kotlin.math.sin(radians) * 0.2)).toFloat(), out[0], 1e-5f)
                assertEquals((scale * (kotlin.math.sin(radians) * 0.3 - kotlin.math.cos(radians) * 0.2)).toFloat(), out[1], 1e-5f)
            }
        }
    }

    /**
     * The point of the tool. Lines that converge on the vanishing point a camera tilted by an angle
     * makes come out vertical when the slider turns the virtual camera back by that angle.
     */
    @Test
    fun theVerticalSliderMakesConvergingLinesParallel() {
        val aspect = 1.5f
        val focal = 28.0 / 43.266615 * sqrt(aspect * aspect + 1.0)
        val degrees = 18.0
        // Shot from below: the verticals meet above the photo.
        val vanishingY = -focal / tan(Math.toRadians(degrees))
        val transform = frameTransform(aspect, 0f, PerspectiveParameters(vertical = (-degrees / 45.0 * 100).toFloat()))
        val top = FloatArray(2)
        val bottom = FloatArray(2)
        for (bottomX in listOf(-0.6, -0.2, 0.35, 0.7)) {
            // A point a third of the way from the bottom edge to the vanishing point, on the same line.
            val t = 0.3
            val upperX = bottomX * (1 - t)
            val upperY = 0.5 + (vanishingY - 0.5) * t
            transform.map(bottomX.toFloat(), 0.5f, bottom)
            transform.map(upperX.toFloat(), upperY.toFloat(), top)
            assertEquals("line from x = $bottomX", bottom[0], top[0], 1e-4f)
        }
    }

    @Test
    fun theHorizontalSliderMakesConvergingLinesParallel() {
        val aspect = 1.5f
        val focal = 28.0 / 43.266615 * sqrt(aspect * aspect + 1.0)
        val degrees = 12.0
        // The horizontals meet to the right of the photo: the facade recedes that way, and positive
        // widens the right to bring it back.
        val vanishingX = focal / tan(Math.toRadians(degrees))
        val transform = frameTransform(aspect, 0f, PerspectiveParameters(horizontal = (degrees / 45.0 * 100).toFloat()))
        val near = FloatArray(2)
        val far = FloatArray(2)
        for (leftY in listOf(-0.4, -0.1, 0.25, 0.45)) {
            val t = 0.3
            val farX = -0.75 + (vanishingX + 0.75) * t
            val farY = leftY * (1 - t)
            transform.map(-0.75f, leftY.toFloat(), near)
            transform.map(farX.toFloat(), farY.toFloat(), far)
            assertEquals("line from y = $leftY", near[1], far[1], 1e-4f)
        }
    }

    /**
     * Negative widens the top: a building shot from below is narrower at the top, and the slider
     * that straightens it goes left.
     */
    @Test
    fun negativeVerticalWidensTheTop() {
        val transform = frameTransform(1.5f, 0f, PerspectiveParameters(vertical = -50f))
        val left = FloatArray(2)
        val right = FloatArray(2)
        transform.map(-0.75f, -0.5f, left)
        transform.map(0.75f, -0.5f, right)
        val top = right[0] - left[0]
        transform.map(-0.75f, 0.5f, left)
        transform.map(0.75f, 0.5f, right)
        val bottom = right[0] - left[0]
        assertTrue("top $top, bottom $bottom", top > bottom)
    }

    /**
     * With the crop constrained, every corner of the frame lands on the photo — nothing beyond it
     * shows — and at least one lands on its edge, or the cover would be zooming in more than needed.
     */
    @Test
    fun theConstrainedFrameIsCoveredAndNoMore() {
        val point = FloatArray(2)
        for (aspect in aspects) {
            for (perspective in perspectives) {
                val geometry = FrameGeometry(
                    sourceWidth = (3000 * aspect).toInt(),
                    sourceHeight = 3000,
                    straighten = 6f,
                    perspective = perspective,
                )
                var closest = Float.MAX_VALUE
                for ((u, v) in listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)) {
                    geometry.imageFromFramed(u, v, point)
                    val case = "aspect $aspect, $perspective, corner ($u, $v): (${point[0]}, ${point[1]})"
                    assertTrue(case, point[0] in -1e-4f..1.0001f && point[1] in -1e-4f..1.0001f)
                    closest = minOf(closest, point[0], point[1], 1f - point[0], 1f - point[1])
                }
                if (perspective.scale == 0f) assertEquals("aspect $aspect, $perspective", 0f, closest, 2e-3f)
            }
        }
    }

    /** Both sliders at their ends never take a corner of the photo behind the camera. */
    @Test
    fun theExtremesKeepEveryCornerInFrontOfTheCamera() {
        val point = FloatArray(2)
        for (aspect in aspects) {
            for (vertical in listOf(-100f, 100f)) {
                for (horizontal in listOf(-100f, 100f)) {
                    val transform = frameTransform(aspect, 45f, PerspectiveParameters(vertical = vertical, horizontal = horizontal))
                    for (x in listOf(-aspect / 2, aspect / 2)) {
                        for (y in listOf(-0.5f, 0.5f)) {
                            assertTrue("aspect $aspect, $vertical, $horizontal", transform.map(x, y, point))
                            assertTrue(point[0].isFinite() && point[1].isFinite())
                        }
                    }
                }
            }
        }
    }

    /** Without the constraint the frame keeps the photo's size, and a negative scale shows past it. */
    @Test
    fun anUnconstrainedFrameShowsWhatLiesBeyond() {
        val geometry = FrameGeometry(
            sourceWidth = 4500,
            sourceHeight = 3000,
            perspective = PerspectiveParameters(scale = -30f, constrainCrop = false),
        )
        val point = FloatArray(2)
        geometry.imageFromFramed(0f, 0f, point)
        assertTrue(geometry.showsOutside)
        assertTrue(geometry.isOutside(point))
        geometry.imageFromFramed(0.5f, 0.5f, point)
        assertTrue(!geometry.isOutside(point))
    }

    /** The matrix an Android `Canvas` draws with is the same as the frame's, only in pixels. */
    @Test
    fun thePixelMatrixAgreesWithTheFrame() {
        val width = 4000
        val height = 3000
        val perspective = PerspectiveParameters(vertical = -35f, horizontal = 20f, aspect = 15f, offsetY = 30f)
        val pixels = frameTransformInPixels(width, height, 4f, perspective)
        val frame = FrameGeometry(width, height, straighten = 4f, perspective = perspective)
        val viaPixels = FloatArray(2)
        val viaFrame = FloatArray(2)
        for ((x, y) in listOf(0.2f to 0.3f, 0.5f to 0.5f, 0.9f to 0.1f, 0.4f to 0.95f)) {
            pixels.map(x * width, y * height, viaPixels)
            frame.framedFromImage(x, y, viaFrame)
            assertEquals(viaFrame[0], viaPixels[0] / width, 1e-4f)
            assertEquals(viaFrame[1], viaPixels[1] / height, 1e-4f)
        }
    }

    @Test
    fun theInverseUndoesTheMatrix() {
        val there = FloatArray(2)
        val back = FloatArray(2)
        for (perspective in perspectives) {
            val transform = frameTransform(1.5f, -8f, perspective)
            val inverse = transform.inverse()
            transform.map(0.31f, -0.27f, there)
            inverse.map(there[0], there[1], back)
            assertTrue(abs(back[0] - 0.31f) < 1e-4f && abs(back[1] + 0.27f) < 1e-4f)
        }
    }
}
