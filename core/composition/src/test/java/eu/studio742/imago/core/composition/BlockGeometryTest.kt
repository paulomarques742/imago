package eu.studio742.imago.core.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The geometry of a multiple selection: the frame that wraps it and what gestures do to it. */
class BlockGeometryTest {

    private fun rect(x: Float, y: Float, w: Float, h: Float) = NormalizedRect(x, y, w, h)

    @Test
    fun boundingBoxWrapsEverything() {
        val box = listOf(rect(.1f, .2f, .2f, .1f), rect(.5f, .1f, .2f, .4f)).boundingBox()!!
        assertEquals(.1f, box.x, 1e-5f)
        assertEquals(.1f, box.y, 1e-5f)
        assertEquals(.6f, box.width, 1e-5f)
        assertEquals(.4f, box.height, 1e-5f)
    }

    @Test
    fun emptySelectionHasNoBox() {
        assertNull(emptyList<NormalizedRect>().boundingBox())
    }

    /**
     * What makes resizing "as a block": two cards side by side stay side by side, with the distance
     * between them growing in the same proportion as they do.
     */
    @Test
    fun blockResizeKeepsTheArrangement() {
        val left = rect(.1f, .1f, .2f, .2f)
        val right = rect(.5f, .1f, .2f, .2f)
        val box = listOf(left, right).boundingBox()!!
        val target = rect(box.x, box.y, box.width * 2f, box.height)

        val newLeft = left.mappedBetween(box, target)
        val newRight = right.mappedBetween(box, target)

        // Both double in width and the gap between them doubles with them.
        assertEquals(.4f, newLeft.width, 1e-5f)
        assertEquals(.4f, newRight.width, 1e-5f)
        assertEquals((right.x - left.right) * 2f, newRight.x - newLeft.right, 1e-5f)
        // The rectangle the handle shows is what the elements now take up.
        assertEquals(target.x, newLeft.x, 1e-5f)
        assertEquals(target.right, newRight.right, 1e-5f)
    }

    @Test
    fun blockResizeAnchorsTheOppositeCorner() {
        val left = rect(.2f, .2f, .2f, .2f)
        val right = rect(.6f, .2f, .2f, .2f)
        val box = listOf(left, right).boundingBox()!!
        // Dragging the top-left handle leaves the right edge still — and the element that was
        // touching it keeps touching it.
        val target = box.resizedFrom(ResizeCorner.TOP_LEFT, .2f, 0f)
        assertEquals(box.right, target.right, 1e-5f)
        assertEquals(box.right, right.mappedBetween(box, target).right, 1e-5f)
        assertEquals(target.x, left.mappedBetween(box, target).x, 1e-5f)
    }

    /**
     * A quarter turn on a 9:16 format.
     *
     * An element to the right of the centre has to end up **below** it, and the distance measured in
     * pixels has to be the same before and after. With the calculation in the normalised space, which
     * is not square, this distance changed and the arrangement came out tilted.
     */
    @Test
    fun blockRotationTravelsInPixels() {
        val aspect = 1080f / 1920f
        val element = rect(.7f, .45f, .1f, .1f)
        val centreX = .5f
        val centreY = .5f

        val turned = element.orbited(centreX, centreY, 90f, aspect)

        fun radiusPx(r: NormalizedRect): Float {
            val dx = (r.x + r.width / 2f - centreX) * 1080f
            val dy = (r.y + r.height / 2f - centreY) * 1920f
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        assertEquals(radiusPx(element), radiusPx(turned), .5f)
        // It was .25 to the right of the centre, at mid-height; it ends up below it, at the same
        // abscissa. The .25 of width is worth less in height, because the page is taller than wide.
        assertEquals(centreX, turned.x + turned.width / 2f, 1e-4f)
        assertEquals(centreY + .25f * aspect, turned.y + turned.height / 2f, 1e-4f)
        assertEquals(element.width, turned.width, 1e-5f)
    }

    @Test
    fun fourQuarterTurnsComeHome() {
        val aspect = 1080f / 1920f
        var moving = rect(.6f, .2f, .1f, .15f)
        repeat(4) { moving = moving.orbited(.5f, .5f, 90f, aspect) }
        assertEquals(.6f, moving.x, 1e-3f)
        assertEquals(.2f, moving.y, 1e-3f)
    }

    @Test
    fun rotatingTheCentreElementLeavesItAlone() {
        val centred = rect(.45f, .45f, .1f, .1f)
        val turned = centred.orbited(.5f, .5f, 37f, 1080f / 1920f)
        assertEquals(centred.x, turned.x, 1e-5f)
        assertEquals(centred.y, turned.y, 1e-5f)
    }
}
