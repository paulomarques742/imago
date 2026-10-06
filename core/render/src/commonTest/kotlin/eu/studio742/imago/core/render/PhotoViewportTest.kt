package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoViewportTest {
    @Test
    fun `fit keeps a landscape image centred inside a portrait surface`() {
        val viewport = calculatePhotoViewport(
            surfaceWidth = 1_000,
            surfaceHeight = 2_000,
            imageWidth = 2_000,
            imageHeight = 1_000,
            zoom = 1f,
            panX = 300f,
            panY = 300f,
        )

        assertEquals(PhotoViewport(left = 0, bottom = 750, width = 1_000, height = 500), viewport)
    }

    @Test
    fun `zoom expands from the centre and clamps horizontal pan`() {
        val viewport = calculatePhotoViewport(
            surfaceWidth = 1_000,
            surfaceHeight = 2_000,
            imageWidth = 2_000,
            imageHeight = 1_000,
            zoom = 2f,
            panX = 900f,
            panY = 900f,
        )

        assertEquals(PhotoViewport(left = 0, bottom = 500, width = 2_000, height = 1_000), viewport)
    }

    @Test
    fun `portrait zoom clamps vertical pan without exposing extra canvas`() {
        val viewport = calculatePhotoViewport(
            surfaceWidth = 1_000,
            surfaceHeight = 1_000,
            imageWidth = 1_000,
            imageHeight = 2_000,
            zoom = 2f,
            panX = 400f,
            panY = 900f,
        )

        assertEquals(PhotoViewport(left = 0, bottom = -1_000, width = 1_000, height = 2_000), viewport)
    }
}
