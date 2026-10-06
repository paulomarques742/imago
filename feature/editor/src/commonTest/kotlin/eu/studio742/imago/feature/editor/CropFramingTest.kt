package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.render.MIN_CROP_ZOOM
import eu.studio742.imago.core.render.PhotoTransform
import eu.studio742.imago.core.render.photoBounds

/** The crop mode's stage: the screen minus the top and bottom chrome. */
private const val STAGE_W = 1_080
private const val STAGE_H = 1_400
private const val IMAGE_W = 3_000
private const val IMAGE_H = 2_000

private fun boundsOf(transform: PhotoTransform, quarterTurns: Int = 0) = photoBounds(
    surfaceWidth = STAGE_W,
    surfaceHeight = STAGE_H,
    imageWidth = IMAGE_W,
    imageHeight = IMAGE_H,
    transform = transform,
    quarterTurns = quarterTurns,
    minZoom = MIN_CROP_ZOOM,
)

private fun framingOf(crop: CropRect, quarterTurns: Int = 0) = cropFramingTransform(
    stageWidth = STAGE_W,
    stageHeight = STAGE_H,
    imageWidth = IMAGE_W,
    imageHeight = IMAGE_H,
    quarterTurns = quarterTurns,
    crop = crop,
)

class CropFramingTest {
    /**
     * The central invariant of the photo drag gesture: the frame is frozen in pixels, the photo moves,
     * and the crop is read back from the frame. If the forward and backward readings are not exactly
     * inverse, the crop drifts a little with each drag and after a dozen of them the photo moves on
     * its own.
     */
    @Test
    fun `reading the frame and reading it back returns the same crop`() {
        val transforms = listOf(
            PhotoTransform(),
            PhotoTransform(zoom = 0.5f),
            PhotoTransform(zoom = 2.4f, panX = 130f, panY = -70f),
            PhotoTransform(zoom = 0.9f, panX = -220f, panY = 45f),
        )
        val crops = listOf(
            CropRect(),
            CropRect(x = 0.1f, y = 0.2f, w = 0.5f, h = 0.6f),
            CropRect(x = 0.42f, y = 0.05f, w = 0.3f, h = 0.9f),
            CropRect(x = 0f, y = 0.55f, w = 0.25f, h = 0.4f),
        )
        transforms.forEach { transform ->
            val bounds = boundsOf(transform)
            crops.forEach { crop ->
                val readBack = cropRectOf(bounds, cropFrameOf(bounds, crop))
                assertEquals("x with $transform / $crop", crop.x, readBack.x, 0.0005f)
                assertEquals("y with $transform / $crop", crop.y, readBack.y, 0.0005f)
                assertEquals("w with $transform / $crop", crop.w, readBack.w, 0.0005f)
                assertEquals("h with $transform / $crop", crop.h, readBack.h, 0.0005f)
            }
        }
    }

    /**
     * Reframing has to settle the first time. If applying it to its own result gave something else,
     * every released gesture would push the view a little further and it would never stop.
     */
    @Test
    fun `framing is idempotent`() {
        listOf(
            CropRect(),
            CropRect(x = 0.1f, y = 0.2f, w = 0.5f, h = 0.6f),
            CropRect(x = 0.7f, y = 0.7f, w = 0.3f, h = 0.3f),
        ).forEach { crop ->
            val firstFraming = framingOf(crop)
            val bounds = boundsOf(firstFraming)
            // Reframing the crop the first framing describes cannot move anything.
            val secondFraming = framingOf(cropRectOf(bounds, cropFrameOf(bounds, crop)))
            assertEquals("zoom with $crop", firstFraming.zoom, secondFraming.zoom, 0.001f)
            assertEquals("panX with $crop", firstFraming.panX, secondFraming.panX, 0.5f)
            assertEquals("panY with $crop", firstFraming.panY, secondFraming.panY, 0.5f)
        }
    }

    /** Whatever the crop, framing brings its centre to the centre of the stage. */
    @Test
    fun `framing centres the frame on the stage`() {
        listOf(
            CropRect(),
            CropRect(x = 0.6f, y = 0.05f, w = 0.35f, h = 0.4f),
            CropRect(x = 0f, y = 0.7f, w = 0.2f, h = 0.3f),
        ).forEach { crop ->
            val frame = cropFrameOf(boundsOf(framingOf(crop)), crop)
            assertEquals("centre on x with $crop", STAGE_W / 2f, (frame.left + frame.right) / 2f, 1f)
            assertEquals("centre on y with $crop", STAGE_H / 2f, (frame.top + frame.bottom) / 2f, 1f)
        }
    }

    /**
     * The frame takes the requested fraction of the side that tightens first, and there is margin left
     * on the other — that margin is what lets one see what is left out of the crop.
     */
    @Test
    fun `framing fits the shorter side and leaves a margin`() {
        val crop = CropRect()
        val frame = cropFrameOf(boundsOf(framingOf(crop)), crop)
        val fractionX = frame.width / STAGE_W
        val fractionY = frame.height / STAGE_H
        assertEquals("one of the sides touches the fraction", CROP_FRAME_FILL, maxOf(fractionX, fractionY), 0.01f)
        assertTrue("the other has to leave a margin", minOf(fractionX, fractionY) <= CROP_FRAME_FILL + 0.01f)
        assertTrue("it can never overflow", fractionX <= 1f && fractionY <= 1f)
    }

    /**
     * The limit that replaces the surface's when the zoom-out goes past it: however much one drags,
     * the frame does not leave the photo. And when zooming out further would make it leave, it is the
     * zoom that stops — one cannot choose a crop the photo does not have to give.
     */
    @Test
    fun `the frame never leaves the photo`() {
        val crop = CropRect(x = 0.2f, y = 0.2f, w = 0.5f, h = 0.5f)
        val frame = cropFrameOf(boundsOf(framingOf(crop)), crop)
        listOf(
            PhotoTransform(zoom = 0.05f),
            PhotoTransform(zoom = 1f, panX = 100_000f, panY = 100_000f),
            PhotoTransform(zoom = 1f, panX = -100_000f, panY = -100_000f),
            PhotoTransform(zoom = 3f, panX = 9_000f, panY = -9_000f),
        ).forEach { requested ->
            val preso = requested.clampedToCropFrame(
                stageWidth = STAGE_W,
                stageHeight = STAGE_H,
                imageWidth = IMAGE_W,
                imageHeight = IMAGE_H,
                quarterTurns = 0,
                frame = frame,
            )
            val bounds = boundsOf(preso)
            assertTrue("left with $requested: $bounds vs $frame", bounds.left <= frame.left + 0.5f)
            assertTrue("top with $requested: $bounds vs $frame", bounds.top <= frame.top + 0.5f)
            assertTrue("right with $requested: $bounds vs $frame", bounds.right >= frame.right - 0.5f)
            assertTrue("bottom with $requested: $bounds vs $frame", bounds.bottom >= frame.bottom - 0.5f)
        }
    }

    /**
     * The requirement that rules the whole crop mode: the photo appears **whole**, and not one edge of
     * it is outside the stage. Cropping is deciding what to drop, and one does not decide what to drop
     * without having it in front of one.
     *
     * The stage is already the screen minus the measured chrome, so fitting here is fitting in view.
     */
    @Test
    fun `at rest the whole photo appears inside the stage`() {
        listOf(
            Triple(STAGE_W, STAGE_H, IMAGE_W to IMAGE_H),
            Triple(STAGE_W, STAGE_H, IMAGE_H to IMAGE_W),
            Triple(STAGE_H, STAGE_W, IMAGE_W to IMAGE_H),
        ).forEach { (stageW, stageH, image) ->
            listOf(0, 1, 2, 3).forEach { quarterTurns ->
                val repouso = cropRestingTransform(
                    stageWidth = stageW,
                    stageHeight = stageH,
                    imageWidth = image.first,
                    imageHeight = image.second,
                    quarterTurns = quarterTurns,
                )
                val bounds = photoBounds(
                    surfaceWidth = stageW,
                    surfaceHeight = stageH,
                    imageWidth = image.first,
                    imageHeight = image.second,
                    transform = repouso,
                    quarterTurns = quarterTurns,
                    minZoom = MIN_CROP_ZOOM,
                )
                val where = "$image at $quarterTurns quarter turns on ${stageW}x$stageH"
                assertTrue("left clipped on $where: $bounds", bounds.left >= -0.5f)
                assertTrue("top clipped on $where: $bounds", bounds.top >= -0.5f)
                assertTrue("right clipped on $where: $bounds", bounds.right <= stageW + 0.5f)
                assertTrue("bottom clipped on $where: $bounds", bounds.bottom <= stageH + 0.5f)
                // And there is margin left, or the corner handles would stick to the screen's edge.
                assertTrue(
                    "no margin for the handles on $where: $bounds",
                    bounds.left >= 1f || bounds.top >= 1f,
                )
            }
        }
    }

    /**
     * A crop already made cannot shrink the view. The frame lives inside the photo, so showing the
     * whole photo shows it too — and also shows what was left out, which is what lets the decision be
     * corrected.
     */
    @Test
    fun `a tight crop does not take the photo off the screen`() {
        val apertado = CropRect(x = 0.72f, y = 0.05f, w = 0.2f, h = 0.25f)
        val repouso = cropRestingTransform(STAGE_W, STAGE_H, IMAGE_W, IMAGE_H, quarterTurns = 0)
        val bounds = boundsOf(repouso)
        assertTrue("the photo has to fit", bounds.left >= -0.5f && bounds.right <= STAGE_W + 0.5f)
        val frame = cropFrameOf(bounds, apertado)
        assertTrue(
            "and the frame has to be visible inside it: $frame",
            frame.left >= bounds.left - 0.5f && frame.right <= bounds.right + 0.5f &&
                frame.top >= bounds.top - 0.5f && frame.bottom <= bounds.bottom + 0.5f,
        )
    }

    /** Zooming the photo out makes the same frame cover more image — it is the user's request. */
    @Test
    fun `zooming the photo out widens the crop the frame describes`() {
        val crop = CropRect(x = 0.3f, y = 0.3f, w = 0.4f, h = 0.4f)
        val perto = PhotoTransform(zoom = 1.6f)
        val frame = cropFrameOf(boundsOf(perto), crop)
        val longe = PhotoTransform(zoom = 0.8f)
        val widened = cropRectOf(boundsOf(longe), frame)
        assertTrue(
            "the frame should come to cover more: ${crop.w} -> ${widened.w}",
            widened.w > crop.w && widened.h > crop.h,
        )
    }

    /** A minimal touch-up cannot shake the view; a real reframe has to get through. */
    @Test
    fun `the dead zone stops jitter but not reframing`() {
        val base = PhotoTransform(zoom = 1.5f, panX = 100f, panY = 100f)
        assertTrue("two pixels are not a reframe", !base.needsReframing(base.copy(panX = 102f)))
        assertTrue("half a screen is", base.needsReframing(base.copy(panX = 500f)))
        assertTrue("a real zoom too", base.needsReframing(base.copy(zoom = 2.5f)))
    }
}
