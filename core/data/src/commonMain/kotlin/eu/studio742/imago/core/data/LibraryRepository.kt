package eu.studio742.imago.core.data

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.AlbumAddition
import eu.studio742.imago.core.model.FolderTransfer
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import java.io.File

/**
 * How the sync of the timeline skeleton is going.
 *
 * It counts months, not photos, because the month is the unit of the request: each one arrives
 * whole in a single request.
 */
data class CatalogSyncState(
    val syncing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
)

interface LibraryRepository {
    fun assets(
        filter: LibraryFilter,
        month: String? = null,
        albumId: String? = null,
        query: String? = null,
    ): Flow<PagingData<ImmichAsset>>
    suspend fun albums(): List<ImmichAlbum>
    suspend fun timeBuckets(): List<ImmichTimeBucket>

    /** How the skeleton sync is going, so the library can say. */
    val catalogSync: StateFlow<CatalogSyncState>

    /**
     * Brings the whole timeline into the local catalogue, month by month.
     *
     * Metadata only — not a single image — and only the months whose count changed since last time.
     * This is what lets the grid have its true size and fast scrolling land on any year: without the
     * rows, a 2015 month is a number on the map and nothing else.
     */
    suspend fun syncCatalog()

    /**
     * Brings a single month, without waiting for the whole sync.
     *
     * It is what jumping to a date uses when the destination is not in the catalogue yet: one
     * request, the month is there, and the grid has somewhere to scroll to.
     */
    suspend fun loadMonth(month: String)

    /**
     * The index, in this view, of the first photo of this date or earlier.
     *
     * Null when there is none. It is a count in SQL and not a scan of the loaded list: with the whole
     * catalogue in Room, the loaded list is a window and the jump's destination is almost always
     * outside it.
     */
    /**
     * The index of a photo in this view, or null if it is not in the catalogue.
     *
     * It is what puts the grid back where it was when coming back from the detail.
     */
    suspend fun indexOfAsset(
        assetId: String,
        filter: LibraryFilter,
        month: String? = null,
        query: String? = null,
    ): Int?

    suspend fun indexOfDate(
        date: java.time.LocalDate,
        filter: LibraryFilter,
        month: String? = null,
        query: String? = null,
    ): Int?
    fun thumbnailUrl(assetId: String): String
    fun previewUrl(assetId: String): String

    /** The video stream, for the detail to play it without downloading the original. */
    fun videoPlaybackUrl(assetId: String): String
    fun apiKey(assetId: String): String

    /** The detail with EXIF, for the photo screen. */
    suspend fun assetDetail(assetId: String): ImmichAssetDetail

    /** Writes to Immich and to the local catalogue, so the grid reacts without a refetch. */
    suspend fun setFavorite(assetId: String, isFavorite: Boolean)
    suspend fun deleteAsset(assetId: String)

    /**
     * A whole selection at once. A library that can do it in one go overrides these: one request to
     * the server, and on the phone one system confirmation instead of one per photo.
     */
    suspend fun setFavorites(assetIds: List<String>, isFavorite: Boolean) {
        assetIds.forEach { setFavorite(it, isFavorite) }
    }

    suspend fun deleteAssets(assetIds: List<String>) {
        assetIds.forEach { deleteAsset(it) }
    }

    /*
     * Changing albums. Only a library whose albums say so ([ImmichAlbum.canEditContent],
     * [ImmichAlbum.isOwned]) is asked; the others keep these defaults and never show the actions.
     */

    /** A new album in the open library, with [assetIds] already in it. */
    suspend fun createAlbum(name: String, assetIds: List<String>): ImmichAlbum = error("This library cannot create albums")

    suspend fun addToAlbum(albumId: String, assetIds: List<String>): AlbumAddition = error("This library cannot change albums")

    /** Out of the album only; the photos stay in the library. How many came out. */
    suspend fun removeFromAlbum(albumId: String, assetIds: List<String>): Int = error("This library cannot change albums")

    suspend fun renameAlbum(albumId: String, name: String): Unit = error("This library cannot change albums")

    /** The album goes. A server's keeps its photos; a folder's go to the device's trash. */
    suspend fun deleteAlbum(albumId: String): Unit = error("This library cannot delete albums")

    /** Into a folder album, moved or copied. */
    suspend fun fileIntoAlbum(albumId: String, assetIds: List<String>, transfer: FolderTransfer): AlbumAddition =
        error("This library has no folder albums")

    /** A new folder album, born with [assetIds] in it: a folder cannot exist empty. */
    suspend fun createFolderAlbum(name: String, assetIds: List<String>, transfer: FolderTransfer): ImmichAlbum =
        error("This library has no folder albums")

    /**
     * Fails with the permission the library's key lacks to delete this asset, before the
     * confirmation is asked. Libraries without a key can always delete.
     */
    suspend fun checkCanDelete(assetId: String) = Unit

    /** The original file, for sharing. */
    suspend fun downloadOriginal(assetId: String, destination: File)
}
