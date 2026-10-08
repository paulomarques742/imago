package eu.studio742.imago.core.data

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichPerson
import eu.studio742.imago.core.model.MapContents
import eu.studio742.imago.core.model.DayMemory
import eu.studio742.imago.core.model.AlbumAddition
import eu.studio742.imago.core.model.AlbumPlace
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

    /**
     * The photos of the stack [assetId] is in, the cover first; empty when it is in none, or the
     * library has no stacks. Only Immich has them, and reading them asks the key for `stack.read`.
     */
    suspend fun stackMembers(assetId: String): List<ImmichAsset> = emptyList()

    /** The photo [assetId] was exported from by this app; null when it is not one of its exports. */
    suspend fun exportOriginal(assetId: String): String? = null

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

    /** The album's id afterwards: a server's stays, a folder's changes with its name. */
    suspend fun renameAlbum(albumId: String, name: String): String = error("This library cannot change albums")

    /** The album goes. A server's keeps its photos; a folder's go to the device's trash. */
    suspend fun deleteAlbum(albumId: String): Unit = error("This library cannot delete albums")

    /**
     * The same photo on the other side, in the unified library: the server's copy of a phone photo,
     * or the phone's of a server one. Null outside it, or when there is none.
     */
    suspend fun counterpartOf(assetId: String): String? = null

    /** Whether this library searches by what is in the photos; a server with it on does. */
    suspend fun contentSearchAvailable(): Boolean = false

    /**
     * Photos and videos by what is in them ("beach"), the closest first, within the grid's slice:
     * the chip, the month, the album. Empty where the library cannot.
     */
    fun searchByContent(query: String, filter: LibraryFilter, month: String? = null, albumId: String? = null): Flow<PagingData<ImmichAsset>> =
        kotlinx.coroutines.flow.flowOf(PagingData.empty())

    /** The photos of [today] in earlier years, one memory per year, the most recent year first. */
    suspend fun onThisDay(today: java.time.LocalDate): List<DayMemory> = emptyList()

    /** Whether this photo's file can be renamed: the device's can; Immich keeps the original's name. */
    fun canRename(assetId: String): Boolean = false

    /**
     * Gives the file a new name, keeping its extension. Returns the photo's id afterwards: on a
     * computer it is the file's path, which the name is part of.
     */
    suspend fun renameAsset(assetId: String, name: String): String = error("This library cannot rename files")

    /** Whether this library has an archive: a server's, or this device's own. */
    val canArchive: Boolean get() = false

    /** Out of the timeline into the archive, or back. */
    suspend fun setArchived(assetIds: List<String>, archived: Boolean): Unit = error("This library has no archive")

    /** The archived photos and videos, the newest first. */
    fun archivedAssets(): Flow<PagingData<ImmichAsset>> = kotlinx.coroutines.flow.flowOf(PagingData.empty())

    /** Brings the server's archive into the catalogue, where the archive views read it. */
    suspend fun refreshArchive() = Unit

    /** Whether this library can put its photos on a map. */
    val hasMap: Boolean get() = false

    /**
     * The photos with a place, as they become known: a server says them at once, this device reads
     * them from its files the first time, a batch at a time.
     */
    fun mapContents(): Flow<MapContents> = kotlinx.coroutines.flow.flowOf(MapContents(emptyList()))

    /** These photos, the newest first, from the catalogue: the ones of a place on the map. */
    fun assetsWithIds(ids: List<String>): Flow<PagingData<ImmichAsset>> = kotlinx.coroutines.flow.flowOf(PagingData.empty())

    /** Whether this library knows who is in its photos: a server does, this device does not. */
    val hasPeople: Boolean get() = false

    /** The people in this library's photos, the named ones first. */
    suspend fun people(): List<ImmichPerson> = emptyList()

    /** The face that stands for [personId]. */
    fun personThumbnailUrl(personId: String): String = ""

    /** The photos and videos [personId] is in, the newest first. */
    fun personAssets(personId: String, filter: LibraryFilter): Flow<PagingData<ImmichAsset>> =
        kotlinx.coroutines.flow.flowOf(PagingData.empty())

    /** Whether this library has a trash IMAGO can show; the computer's folders do not. */
    val hasTrash: Boolean get() = false

    suspend fun trash(): TrashContents = error("This library has no trash here")

    suspend fun restoreFromTrash(assetIds: List<String>): Unit = error("This library has no trash here")

    /** Out of the trash, for good. */
    suspend fun deleteForever(assetIds: List<String>): Unit = error("This library has no trash here")

    suspend fun emptyTrash(): Unit = error("This library has no trash here")

    /** Into a folder album, moved or copied. */
    suspend fun fileIntoAlbum(albumId: String, assetIds: List<String>, transfer: FolderTransfer): AlbumAddition =
        error("This library has no folder albums")

    /**
     * A new folder album, born with [assetIds] in it: a folder cannot exist empty. [place] is one of
     * [albumPlaces]; null leaves it with the photos.
     */
    suspend fun createFolderAlbum(name: String, assetIds: List<String>, transfer: FolderTransfer, place: String? = null): ImmichAlbum =
        error("This library has no folder albums")

    /** Where a new folder album for [assetIds] may go; fewer than two leave nothing to choose. */
    suspend fun albumPlaces(assetIds: List<String>): List<AlbumPlace> = emptyList()

    /**
     * Fails with the permission the library's key lacks to delete this asset, before the
     * confirmation is asked. Libraries without a key can always delete.
     */
    suspend fun checkCanDelete(assetId: String) = Unit

    /** The original file, for sharing. */
    suspend fun downloadOriginal(assetId: String, destination: File)
}
