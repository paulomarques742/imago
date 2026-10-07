package eu.studio742.imago.feature.library

import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionsTest {
    private val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "")

    private fun asset(
        libraryId: String,
        localId: String = "42",
        isVideo: Boolean = false,
        isFavorite: Boolean = false,
        recipe: EditRecipe? = null,
    ) = AssetUiModel(
        id = AssetReference(libraryId, localId).encode(),
        checksum = "",
        fileName = "IMG_$localId.JPG",
        date = "",
        fileCreatedAt = "",
        width = null,
        height = null,
        isFavorite = isFavorite,
        isEdited = false,
        hasLocalRecipe = recipe != null,
        thumbnailUrl = "",
        previewUrl = "",
        apiKey = "",
        isVideo = isVideo,
        recipe = recipe,
    )

    @Test
    fun serverPhotosGoAsOriginalsUnlessTheEditIsChosen() {
        val plain = asset("server", "1")
        val edited = asset("server", "2", recipe = recipe)

        assertEquals(
            listOf(plain to CopyKind.ORIGINAL, edited to CopyKind.EDITED),
            deviceCopyPlan(listOf(plain, edited), edited = true),
        )
        assertEquals(
            listOf(plain to CopyKind.ORIGINAL, edited to CopyKind.ORIGINAL),
            deviceCopyPlan(listOf(plain, edited), edited = false),
        )
    }

    @Test
    fun aDevicePhotoOnlyAddsItsEdit() {
        val plain = asset(DEVICE_LIBRARY_ID, "1")
        val edited = asset(DEVICE_LIBRARY_ID, "2", recipe = recipe)

        assertEquals(listOf(edited to CopyKind.EDITED), deviceCopyPlan(listOf(plain, edited), edited = true))
        // The original is already there: choosing originals leaves nothing to save.
        assertEquals(emptyList<Pair<AssetUiModel, CopyKind>>(), deviceCopyPlan(listOf(plain, edited), edited = false))
    }

    @Test
    fun aVideoNeverCarriesAnEdit() {
        val video = asset("server", isVideo = true, recipe = recipe)

        assertEquals(listOf(video to CopyKind.ORIGINAL), deviceCopyPlan(listOf(video), edited = true))
        assertFalse(deviceCopyNeedsChoice(listOf(video)))
    }

    @Test
    fun theQuestionIsOnlyForEditedServerPhotos() {
        assertTrue(deviceCopyNeedsChoice(listOf(asset("server"), asset("server", "2", recipe = recipe))))
        assertFalse(deviceCopyNeedsChoice(listOf(asset("server"))))
        assertFalse(deviceCopyNeedsChoice(listOf(asset(DEVICE_LIBRARY_ID, recipe = recipe))))
    }

    @Test
    fun theHeartMarksAllUnlessAllAreMarked() {
        assertTrue(favoriteTarget(listOf(asset("s", "1", isFavorite = true), asset("s", "2"))))
        assertFalse(favoriteTarget(listOf(asset("s", "1", isFavorite = true), asset("s", "2", isFavorite = true))))
    }

    @Test
    fun aMixOfPhotosAndVideosIsSharedAsAnything() {
        assertEquals("image/*", sharedMimeType(listOf(asset("s"))))
        assertEquals("video/*", sharedMimeType(listOf(asset("s", isVideo = true))))
        assertEquals("*/*", sharedMimeType(listOf(asset("s"), asset("s", "2", isVideo = true))))
    }

    @Test
    fun repeatedNamesAndFilesAlreadyThereGetANumber() {
        val directory = Files.createTempDirectory("selection").toFile()
        try {
            directory.resolve("IMG_0001.JPG").writeText("someone else’s")
            val taken = mutableSetOf<String>()

            assertEquals("IMG_0001 (2).JPG", uniqueFileName("IMG_0001.JPG", directory, taken, "fallback"))
            assertEquals("IMG_0001 (3).JPG", uniqueFileName("IMG_0001.JPG", directory, taken, "fallback"))
            assertEquals("IMAGO-3", uniqueFileName("", directory, taken, "IMAGO-3"))
            assertEquals("photo.heic", uniqueFileName("DCIM/photo.heic", directory, taken, "fallback"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
