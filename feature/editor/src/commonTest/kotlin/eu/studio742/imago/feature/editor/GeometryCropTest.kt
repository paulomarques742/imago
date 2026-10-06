package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryCropTest {
    @Test
    fun `landscape source crops its width for a square`() {
        val crop = centeredCropRect(
            imageWidth = 6_000,
            imageHeight = 4_000,
            rotation = 0,
            aspectRatio = 1f,
        )

        assertEquals(1f / 6f, crop.x, 0.0001f)
        assertEquals(0f, crop.y, 0.0001f)
        assertEquals(2f / 3f, crop.w, 0.0001f)
        assertEquals(1f, crop.h, 0.0001f)
    }

    @Test
    fun `portrait ratio crops height from a landscape source`() {
        val crop = centeredCropRect(
            imageWidth = 4_000,
            imageHeight = 3_000,
            rotation = 0,
            aspectRatio = 4f / 5f,
        )

        assertEquals(0.6f, crop.w, 0.0001f)
        assertEquals(1f, crop.h, 0.0001f)
        assertEquals(0.2f, crop.x, 0.0001f)
    }

    @Test
    fun `rotation swaps the dimensions before calculating the crop`() {
        val crop = centeredCropRect(
            imageWidth = 6_000,
            imageHeight = 4_000,
            rotation = 90,
            aspectRatio = 2f / 3f,
        )

        assertEquals(0f, crop.x, 0.0001f)
        assertEquals(0f, crop.y, 0.0001f)
        assertEquals(1f, crop.w, 0.0001f)
        assertEquals(1f, crop.h, 0.0001f)
    }

    @Test
    fun `common list contains paired portrait and landscape ratios`() {
        assertEquals(
            listOf("1:1", "3:2", "2:3", "4:3", "3:4", "5:4", "4:5", "16:9", "9:16"),
            commonCropAspects.map { it.id },
        )
    }

    @Test
    fun `free crop can move anywhere while staying inside the image`() {
        val moved = eu.studio742.imago.core.model.CropRect(0.2f, 0.2f, 0.5f, 0.4f)
            .dragged(CropHandle.MOVE, 0.6f, -0.4f, null, 1.5f)

        assertEquals(0.5f, moved.x, 0.0001f)
        assertEquals(0f, moved.y, 0.0001f)
        assertEquals(0.5f, moved.w, 0.0001f)
        assertEquals(0.4f, moved.h, 0.0001f)
    }

    @Test
    fun `free corner changes width and height independently`() {
        val resized = eu.studio742.imago.core.model.CropRect(0.2f, 0.2f, 0.5f, 0.4f)
            .dragged(CropHandle.TOP_LEFT, 0.1f, -0.1f, null, 1.5f)

        assertEquals(0.3f, resized.x, 0.0001f)
        assertEquals(0.1f, resized.y, 0.0001f)
        assertEquals(0.4f, resized.w, 0.0001f)
        assertEquals(0.5f, resized.h, 0.0001f)
    }

    @Test
    fun `locked corner preserves the selected pixel ratio`() {
        val sourceRatio = 3f / 2f
        val resized = eu.studio742.imago.core.model.CropRect(1f / 6f, 0f, 2f / 3f, 1f)
            .dragged(CropHandle.BOTTOM_RIGHT, -0.2f, -0.2f, 1f, sourceRatio)

        assertEquals(1f, resized.w * sourceRatio / resized.h, 0.0001f)
    }

    @Test
    fun `clockwise rotation carries the crop and swaps a paired aspect`() {
        val rotated = eu.studio742.imago.core.model.CropRect(0.1f, 0.2f, 0.5f, 0.6f)
            .rotatedClockwise()

        assertEquals(0.2f, rotated.x, 0.0001f)
        assertEquals(0.1f, rotated.y, 0.0001f)
        assertEquals(0.6f, rotated.w, 0.0001f)
        assertEquals(0.5f, rotated.h, 0.0001f)
        assertEquals("2:3", reciprocalCropAspectId("3:2"))
        assertEquals(ORIGINAL_CROP_ASPECT, reciprocalCropAspectId(ORIGINAL_CROP_ASPECT))
    }
}
