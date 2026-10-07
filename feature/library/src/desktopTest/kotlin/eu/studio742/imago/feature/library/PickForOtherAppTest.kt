package eu.studio742.imago.feature.library

import eu.studio742.imago.core.model.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickForOtherAppTest {
    @Test
    fun theTypesAskedForSayWhatCanBeChosen() {
        assertEquals(PickerMedia(photos = true, videos = false), pickerAccepts(listOf("image/*")))
        assertEquals(PickerMedia(photos = true, videos = false), pickerAccepts(listOf("image/jpeg")))
        assertEquals(PickerMedia(photos = false, videos = true), pickerAccepts(listOf("video/*")))
        assertEquals(PickerMedia(photos = true, videos = true), pickerAccepts(listOf("image/*", "video/mp4")))
        // A pick over MediaStore's folders.
        assertEquals(PickerMedia(photos = true, videos = false), pickerAccepts(listOf("vnd.android.cursor.dir/image")))
    }

    @Test
    fun anAppThatDoesNotSayIsShownEverything() {
        assertEquals(PickerMedia(photos = true, videos = true), pickerAccepts(listOf("*/*")))
        assertEquals(PickerMedia(photos = true, videos = true), pickerAccepts(listOf(null)))
        assertEquals(PickerMedia(photos = true, videos = true), pickerAccepts(listOf("application/pdf")))
    }

    @Test
    fun onlyAnEditedPhotoAsksWhichVersion() {
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "")
        fun asset(isVideo: Boolean = false, recipe: EditRecipe? = null) = AssetUiModel(
            id = "imago:device:1", checksum = "", fileName = "a.jpg", date = "", fileCreatedAt = "",
            width = null, height = null, isFavorite = false, isEdited = false, hasLocalRecipe = recipe != null,
            thumbnailUrl = "", previewUrl = "", apiKey = "", isVideo = isVideo, recipe = recipe,
        )

        assertTrue(pickNeedsChoice(listOf(asset(), asset(recipe = recipe))))
        assertFalse(pickNeedsChoice(listOf(asset())))
        assertFalse(pickNeedsChoice(listOf(asset(isVideo = true, recipe = recipe))))
    }
}
