package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone screen with a 3:2 landscape — the case that exposed the gesture's two bugs.
 */
private const val SURFACE_W = 1_080
private const val SURFACE_H = 2_100
private const val IMAGE_W = 3_000
private const val IMAGE_H = 2_000

private fun PhotoTransform.pinchFrame(
    zoomChange: Float,
    centroidX: Float = SURFACE_W / 2f,
    centroidY: Float = SURFACE_H / 2f,
    panChangeX: Float = 0f,
    panChangeY: Float = 0f,
) = pinch(
    zoomChange = zoomChange,
    panChangeX = panChangeX,
    panChangeY = panChangeY,
    centroidX = centroidX,
    centroidY = centroidY,
    surfaceWidth = SURFACE_W,
    surfaceHeight = SURFACE_H,
    imageWidth = IMAGE_W,
    imageHeight = IMAGE_H,
)

class PhotoTransformTest {
    /**
     * The regression of the bug that killed the pinch: `calculateZoom` returns each frame's ratio, of
     * the order of 1.005. If the accumulated zoom is tested against a threshold on every frame, a
     * whole pinch sequence returns 1× and the gesture never starts.
     */
    @Test
    fun `realistic pinch frames accumulate instead of going back to the fit`() {
        val frames = listOf(1.004f, 1.006f, 1.005f, 1.008f, 1.003f, 1.007f, 1.004f, 1.009f)
        var transform = PhotoTransform()
        repeat(6) { frames.forEach { transform = transform.pinchFrame(it) } }

        assertTrue(
            "The zoom should accumulate along the gesture, but it ended at ${transform.zoom}",
            transform.zoom > 1.3f,
        )
    }

    @Test
    fun `a single slow frame already leaves the fit`() {
        val transform = PhotoTransform().pinchFrame(1.004f)

        assertNotEquals(MIN_PHOTO_ZOOM, transform.zoom)
    }

    /**
     * At 4× the photo already spills off the screen on both axes, so the pan limit does not interfere
     * and only the focal point can be measured. Near the fit the limit is what rules — see the next test.
     */
    @Test
    fun `the point under the centroid stays fixed when zooming in`() {
        val centroidX = 300f
        val centroidY = 1_500f
        val before = PhotoTransform(zoom = 4f)
        val after = before.pinchFrame(zoomChange = 1.5f, centroidX = centroidX, centroidY = centroidY)

        // The photo's point under the centroid, measured from the centre of the surface.
        fun anchor(transform: PhotoTransform, axis: (PhotoTransform) -> Float, centroid: Float, half: Float) =
            (centroid - half - axis(transform)) / transform.zoom

        assertEquals(
            anchor(before, PhotoTransform::panX, centroidX, SURFACE_W / 2f),
            anchor(after, PhotoTransform::panX, centroidX, SURFACE_W / 2f),
            0.01f,
        )
        assertEquals(
            anchor(before, PhotoTransform::panY, centroidY, SURFACE_H / 2f),
            anchor(after, PhotoTransform::panY, centroidY, SURFACE_H / 2f),
            0.01f,
        )
    }

    @Test
    fun `the photo's limit overrides the focal point`() {
        // At 3× a 3:2 landscape only spills 30 px vertically on this screen: zooming in with the
        // fingers at the bottom cannot reveal empty canvas above, however much the focus asked for it.
        val after = PhotoTransform(zoom = 2f)
            .pinchFrame(zoomChange = 1.5f, centroidX = 300f, centroidY = 1_500f)
        val (_, maxY) = maxPhotoPan(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H, zoom = after.zoom)

        assertEquals(-maxY, after.panY, 0.001f)
        assertTrue("The vertical should be almost closed at 3×", maxY < 40f)
    }

    /**
     * The regression of the second bug: the gesture limited the pan by the surface and the renderer by
     * the fitted photo. For a landscape on a phone screen, the vertical axis only opens much later —
     * dragging up before that could not move anything.
     */
    @Test
    fun `the gesture limits are the same the renderer applies`() {
        listOf(
            Triple(SURFACE_W, SURFACE_H, IMAGE_W to IMAGE_H),
            Triple(SURFACE_H, SURFACE_W, IMAGE_W to IMAGE_H),
            Triple(SURFACE_W, SURFACE_H, IMAGE_H to IMAGE_W),
        ).forEach { (surfaceW, surfaceH, image) ->
            listOf(1f, 1.5f, 2f, 4f, 6f).forEach { zoom ->
                val (maxX, maxY) = maxPhotoPan(surfaceW, surfaceH, image.first, image.second, zoom)
                // A pan far beyond the limit has to settle exactly on what the viewport accepts.
                val clamped = PhotoTransform(zoom = zoom).drag(
                    deltaX = 100_000f,
                    deltaY = 100_000f,
                    surfaceWidth = surfaceW,
                    surfaceHeight = surfaceH,
                    imageWidth = image.first,
                    imageHeight = image.second,
                )
                val viewport = calculatePhotoViewport(
                    surfaceWidth = surfaceW,
                    surfaceHeight = surfaceH,
                    imageWidth = image.first,
                    imageHeight = image.second,
                    zoom = zoom,
                    panX = clamped.panX,
                    panY = clamped.panY,
                )
                assertEquals(maxX, clamped.panX, 0.001f)
                assertEquals(maxY, clamped.panY, 0.001f)
                // At the limit, the photo's left edge coincides with the surface's.
                assertTrue(
                    "Viewport outside the limits at $zoom×: $viewport",
                    viewport.left <= 0 && viewport.left + viewport.width >= surfaceW ||
                        viewport.width <= surfaceW,
                )
            }
        }
    }

    @Test
    fun `rotation swaps the axes of the limits`() {
        val direto = maxPhotoPan(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H, zoom = 3f, quarterTurns = 0)
        val rodado = maxPhotoPan(SURFACE_W, SURFACE_H, IMAGE_H, IMAGE_W, zoom = 3f, quarterTurns = 1)

        assertEquals(direto.first, rodado.first, 0.001f)
        assertEquals(direto.second, rodado.second, 0.001f)
    }

    @Test
    fun `the pinch never leaves the limits after settling`() {
        var transform = PhotoTransform()
        repeat(400) { transform = transform.pinchFrame(1.05f) }
        val settled = transform.settle(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H)

        assertTrue("The elasticity should let the maximum through", transform.zoom > MAX_PHOTO_ZOOM)
        assertEquals(MAX_PHOTO_ZOOM, settled.zoom, 0.001f)
    }

    @Test
    fun `releasing near the fit resets the whole state`() {
        val quase = PhotoTransform(zoom = 1.005f, panX = 40f, panY = -12f)

        assertEquals(PhotoTransform(), quase.settle(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H))
    }

    @Test
    fun `double tap zooms to the tapped point and resets on the next`() {
        val zoomed = PhotoTransform().toggleDoubleTap(
            tapX = 120f,
            tapY = 400f,
            surfaceWidth = SURFACE_W,
            surfaceHeight = SURFACE_H,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
        )

        assertEquals(DOUBLE_TAP_PHOTO_ZOOM, zoomed.zoom, 0.001f)
        assertTrue("It should move towards the tapped corner", zoomed.panX > 0f)

        val restored = zoomed.toggleDoubleTap(
            tapX = 120f,
            tapY = 400f,
            surfaceWidth = SURFACE_W,
            surfaceHeight = SURFACE_H,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
        )
        assertEquals(PhotoTransform(), restored)
    }

    @Test
    fun `dragging a fitted landscape vertically moves nothing`() {
        val transform = PhotoTransform(zoom = 1.5f).drag(
            deltaX = 0f,
            deltaY = 500f,
            surfaceWidth = SURFACE_W,
            surfaceHeight = SURFACE_H,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
        )

        // At 1.5× the fitted height (720 × 1.5 = 1080) still fits in the screen's 2100.
        assertEquals(0f, transform.panY, 0.001f)
    }

    /**
     * Crop mode needs to zoom the photo out beyond the fit, and there the surface stops serving as the
     * limit: the photo fits inside it whole and the frame, which this module does not know, takes
     * over. Returning zero — which is what the fit calculation would give — made the renderer cancel
     * an offset the gesture had validated, exactly the bug
     * `the gesture limits are the same the renderer applies` exists to stop.
     */
    @Test
    fun `below the fit the surface stops being the limit`() {
        val (maxX, maxY) = maxPhotoPan(
            surfaceWidth = SURFACE_W,
            surfaceHeight = SURFACE_H,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
            zoom = 0.5f,
            minZoom = MIN_CROP_ZOOM,
        )
        assertEquals(Float.POSITIVE_INFINITY, maxX, 0f)
        assertEquals(Float.POSITIVE_INFINITY, maxY, 0f)
    }

    /**
     * The new parameter cannot change anything for whoever does not pass it. It is this equivalence
     * that supports the other tests in this file, which all keep calling without mentioning it.
     */
    @Test
    fun `asking for the default minimum is the same as asking for nothing`() {
        listOf(1f, 1.5f, 2f, 4f, 6f).forEach { zoom ->
            assertEquals(
                maxPhotoPan(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H, zoom),
                maxPhotoPan(SURFACE_W, SURFACE_H, IMAGE_W, IMAGE_H, zoom, minZoom = MIN_PHOTO_ZOOM),
            )
        }
    }

    /** The crop pinch goes below the fit, and stops where the mode says it stops. */
    @Test
    fun `the crop pinch goes below the fit and stops at the minimum`() {
        var transform = PhotoTransform()
        repeat(400) {
            transform = transform.pinch(
                zoomChange = 0.97f,
                panChangeX = 0f,
                panChangeY = 0f,
                centroidX = SURFACE_W / 2f,
                centroidY = SURFACE_H / 2f,
                surfaceWidth = SURFACE_W,
                surfaceHeight = SURFACE_H,
                imageWidth = IMAGE_W,
                imageHeight = IMAGE_H,
                minZoom = MIN_CROP_ZOOM,
            )
        }
        assertEquals(MIN_CROP_ZOOM, transform.zoom, 0.0001f)
        assertTrue("the pinch had to leave the fit", transform.zoom < MIN_PHOTO_ZOOM)
    }

    /**
     * Releasing the pinch halfway through a crop cannot throw the view back to the fit: there the
     * zoom-out is the requested framing, not a residue of the gesture.
     */
    @Test
    fun `settling in crop does not reset the zoom-out`() {
        val assentado = PhotoTransform(zoom = 0.6f, panX = 40f).settle(
            surfaceWidth = SURFACE_W,
            surfaceHeight = SURFACE_H,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
            minZoom = MIN_CROP_ZOOM,
        )
        assertEquals(0.6f, assentado.zoom, 0.0001f)
        assertEquals(40f, assentado.panX, 0.0001f)
    }
}
