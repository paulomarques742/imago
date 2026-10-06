package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop frame is drawn in Compose and the photo in GL. As long as the two rectangles come out of
 * the same calculation they cannot drift apart — the same reason that already supports
 * `the gesture limits are the same the renderer applies`, applied now to the drawing.
 *
 * When one of these tests fails, the fix is almost never to touch the test: it is to make both sides
 * read [photoScaledSize] again.
 */
private const val SURFACE_W = 1_080
private const val SURFACE_H = 2_100
private const val IMAGE_W = 3_000
private const val IMAGE_H = 2_000

class PhotoBoundsTest {
    /**
     * The tolerance is one pixel and not zero because the viewport truncates to an integer and the
     * frame does not. One pixel is the truncation; two is already the formula diverging.
     */
    @Test
    fun `the frame is drawn where GL draws the photo`() {
        listOf(
            Triple(SURFACE_W, SURFACE_H, IMAGE_W to IMAGE_H),
            Triple(SURFACE_H, SURFACE_W, IMAGE_W to IMAGE_H),
            Triple(SURFACE_W, SURFACE_H, IMAGE_H to IMAGE_W),
        ).forEach { (surfaceW, surfaceH, image) ->
            listOf(1f, 1.5f, 2f, 4f, 6f).forEach { zoom ->
                listOf(0f, 120f, -400f, 100_000f).forEach { pan ->
                    val transform = PhotoTransform(zoom = zoom, panX = pan, panY = -pan)
                    val bounds = photoBounds(
                        surfaceWidth = surfaceW,
                        surfaceHeight = surfaceH,
                        imageWidth = image.first,
                        imageHeight = image.second,
                        transform = transform,
                    )
                    val viewport = calculatePhotoViewport(
                        surfaceWidth = surfaceW,
                        surfaceHeight = surfaceH,
                        imageWidth = image.first,
                        imageHeight = image.second,
                        zoom = transform.zoom,
                        panX = transform.panX,
                        panY = transform.panY,
                    )
                    val where = "at $zoom× with pan $pan on ${surfaceW}x$surfaceH"
                    assertEquals("width $where", viewport.width.toFloat(), bounds.width, 0.001f)
                    assertEquals("height $where", viewport.height.toFloat(), bounds.height, 0.001f)
                    assertEquals("left $where", viewport.left.toFloat(), bounds.left, 1f)
                    // The only difference between the two systems is the origin of the vertical axis.
                    assertEquals(
                        "top $where",
                        (surfaceH - viewport.bottom - viewport.height).toFloat(),
                        bounds.top,
                        1f,
                    )
                }
            }
        }
    }

    /**
     * Fitted and without offset, [photoBounds] has to give the same rectangle crop mode used before
     * there was zoom — that is what proves wiring up the gesture did not touch what was already there.
     */
    @Test
    fun `without zoom or offset it is the usual fit`() {
        listOf(0, 1).forEach { quarterTurns ->
            val bounds = photoBounds(
                surfaceWidth = SURFACE_W,
                surfaceHeight = SURFACE_H,
                imageWidth = IMAGE_W,
                imageHeight = IMAGE_H,
                transform = PhotoTransform(),
                quarterTurns = quarterTurns,
            )
            // The fit `fittedImageRect` used to compute, in floating point and without truncating.
            val swaps = quarterTurns % 2 == 1
            val ratio = (if (swaps) IMAGE_H else IMAGE_W).toFloat() / (if (swaps) IMAGE_W else IMAGE_H)
            val surfaceRatio = SURFACE_W.toFloat() / SURFACE_H
            val width = if (ratio > surfaceRatio) SURFACE_W.toFloat() else SURFACE_H * ratio
            val height = if (ratio > surfaceRatio) SURFACE_W / ratio else SURFACE_H.toFloat()
            assertEquals("width at $quarterTurns quarter turns", width, bounds.width, 1f)
            assertEquals("height at $quarterTurns quarter turns", height, bounds.height, 1f)
            assertEquals("left", (SURFACE_W - width) / 2f, bounds.left, 1f)
            assertEquals("top", (SURFACE_H - height) / 2f, bounds.top, 1f)
        }
    }

    /**
     * Below the fit the photo fits whole in the surface with a margin around it — that margin is what
     * gives the crop frame room to grow and the finger somewhere to grab an edge handle.
     */
    @Test
    fun `below the fit there is margin on both sides`() {
        val bounds = photoBounds(
            surfaceWidth = SURFACE_W,
            surfaceHeight = SURFACE_H,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
            transform = PhotoTransform(zoom = 0.6f),
            minZoom = MIN_CROP_ZOOM,
        )
        assertTrue("the photo should shrink, it ended at ${bounds.width}", bounds.width < SURFACE_W)
        assertTrue("there should be margin on the left, it ended at ${bounds.left}", bounds.left > 0f)
        assertTrue("there should be margin at the top, it ended at ${bounds.top}", bounds.top > 0f)
        assertEquals("the margin is symmetric", bounds.left, SURFACE_W - bounds.right, 0.001f)
        assertEquals("the margin is symmetric", bounds.top, SURFACE_H - bounds.bottom, 0.001f)
    }

    /** A lower minimum cannot change anything for whoever does not ask for it. */
    @Test
    fun `asking for the default minimum does not change the rectangle`() {
        listOf(1f, 2.5f, 6f).forEach { zoom ->
            val transform = PhotoTransform(zoom = zoom, panX = 90f, panY = 40f)
            assertEquals(
                photoBounds(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H, transform),
                photoBounds(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H, transform, minZoom = MIN_PHOTO_ZOOM),
            )
        }
    }
}
