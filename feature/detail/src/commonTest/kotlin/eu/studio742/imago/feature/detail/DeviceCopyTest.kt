package eu.studio742.imago.feature.detail

import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCopyTest {
    private val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "")

    private fun asset(libraryId: String, isVideo: Boolean = false) = DetailAsset(
        id = AssetReference(libraryId, "42").encode(),
        checksum = "",
        fileName = "IMG_0001.HEIC",
        thumbnailUrl = "",
        previewUrl = "",
        apiKey = "",
        fileCreatedAt = "",
        date = "",
        isFavorite = false,
        isVideo = isVideo,
    )

    @Test
    fun aServerPhotoWithoutEditsSavesTheOriginal() {
        assertEquals(DeviceCopy.ORIGINAL, deviceCopyFor(asset("server"), recipe = null))
    }

    @Test
    fun aServerVideoSavesTheOriginal() {
        assertEquals(DeviceCopy.ORIGINAL, deviceCopyFor(asset("server", isVideo = true), recipe = null))
    }

    @Test
    fun anEditedServerPhotoAsksWhichVersion() {
        assertEquals(DeviceCopy.ORIGINAL_OR_EDITED, deviceCopyFor(asset("server"), recipe))
    }

    @Test
    fun aRecipeOnAVideoIsIgnored() {
        assertEquals(DeviceCopy.ORIGINAL, deviceCopyFor(asset("server", isVideo = true), recipe))
    }

    @Test
    fun aDevicePhotoWithoutEditsOffersNothing() {
        assertEquals(DeviceCopy.NONE, deviceCopyFor(asset(DEVICE_LIBRARY_ID), recipe = null))
        assertEquals(DeviceCopy.NONE, deviceCopyFor(asset(DEVICE_LIBRARY_ID, isVideo = true), recipe))
    }

    @Test
    fun anEditedDevicePhotoSavesTheEditWithoutAsking() {
        assertEquals(DeviceCopy.EDITED, deviceCopyFor(asset(DEVICE_LIBRARY_ID), recipe))
    }
}
