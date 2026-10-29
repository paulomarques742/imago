package eu.studio742.imago.feature.library

import eu.studio742.imago.core.data.CatalogSyncState
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * A Live Photo's tile: the photo's images, and the movement only to play.
 *
 * Immich does not make a thumbnail for the hidden video, so asking for one is a 404 for every Live
 * Photo in the grid; and in the unified library a phone copy plays the server's movement, with the
 * server's key.
 */
class LivePhotoTileTest {
    private val phone = "phone-photo"
    private val movement = AssetReference("home", "m1").encode()

    private fun asset(id: String, type: AssetType = AssetType.IMAGE, movement: String? = null) = ImmichAsset(
        id = id, checksum = "", originalFileName = "IMG_1.HEIC",
        fileCreatedAt = "2026-08-02T10:00:00.000Z", localDateTime = "2026-08-02T11:00",
        width = null, height = null, isFavorite = false, isEdited = false, type = type,
        livePhotoVideoId = movement, livePhotoKnown = true,
    )

    @Test fun thePhotosImagesAreItsOwnAndOnlyPlayingAsksForTheMovement() {
        val library = RoutedLibrary()
        val photo = AssetReference(DEVICE_LIBRARY_ID, phone).encode()

        val tile = asset(photo, movement = movement).toAssetUiModel(library, recipe = null)

        assertEquals("thumb:$photo", tile.thumbnailUrl)
        assertEquals("preview:$photo", tile.previewUrl)
        assertEquals("play:$movement", tile.motionUrl)
        // Each from its own library: the phone has no key, the server's movement needs the server's.
        assertEquals("", tile.apiKey)
        assertEquals("key-home", tile.motionApiKey)
        assertNull(tile.videoUrl)
        assertFalse(movement in library.imagesAskedFor)
    }

    @Test fun aPhotoWithoutAMovementAndAVideoHaveNone() {
        val library = RoutedLibrary()

        val still = asset(AssetReference("home", "p2").encode()).toAssetUiModel(library, recipe = null)
        val video = asset(AssetReference("home", "v1").encode(), AssetType.VIDEO, movement = movement).toAssetUiModel(library, recipe = null)

        assertNull(still.motionUrl)
        assertNull(video.motionUrl)
        assertEquals("", video.motionApiKey)
        assertEquals("play:${AssetReference("home", "v1").encode()}", video.videoUrl)
    }

    /** Answers by each id's own library, as the app's routing does. */
    private class RoutedLibrary : LibraryRepository {
        val imagesAskedFor = mutableListOf<String>()

        override fun thumbnailUrl(assetId: String): String { imagesAskedFor += assetId; return "thumb:$assetId" }
        override fun previewUrl(assetId: String): String { imagesAskedFor += assetId; return "preview:$assetId" }
        override fun videoPlaybackUrl(assetId: String) = "play:$assetId"
        override fun apiKey(assetId: String): String =
            AssetReference.parse(assetId).libraryId.takeIf { it != DEVICE_LIBRARY_ID }?.let { "key-$it" }.orEmpty()

        override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?) = error("not used")
        override suspend fun albums(): List<ImmichAlbum> = error("not used")
        override suspend fun timeBuckets(): List<ImmichTimeBucket> = error("not used")
        override val catalogSync: StateFlow<CatalogSyncState> = MutableStateFlow(CatalogSyncState())
        override suspend fun syncCatalog() = error("not used")
        override suspend fun loadMonth(month: String) = error("not used")
        override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override suspend fun indexOfDate(date: LocalDate, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = error("not used")
        override suspend fun deleteAsset(assetId: String) = error("not used")
        override suspend fun downloadOriginal(assetId: String, destination: File) = error("not used")
        override suspend fun assetDetail(assetId: String): eu.studio742.imago.core.model.ImmichAssetDetail = error("not used")
    }
}
