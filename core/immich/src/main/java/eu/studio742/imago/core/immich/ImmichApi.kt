package eu.studio742.imago.core.immich

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.AssetPage
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.AlbumAddition
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichEdit
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.ServerVersion
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.immich.generated.ImmichContract
import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions
import java.io.File

interface ImmichApi {
    suspend fun currentUserId(connection: ImmichConnection): String = error("User identity unavailable")
    suspend fun validateConnection(connection: ImmichConnection): ServerVersion

    /**
     * The permissions the key was created with, as Immich names them; [ImmichKeyPermissions.ALL]
     * stands for every one. A tap waits for it, so it gives up quickly on a slow server. A fake that
     * does not care about permissions grants them all.
     */
    suspend fun keyPermissions(connection: ImmichConnection): Set<String> = setOf(ImmichKeyPermissions.ALL)

    /**
     * Whether an Immich server is answering at this address, without a key and with a short timeout.
     *
     * It is used to choose between a library's several addresses — the home network one and the
     * external one. Never throws: an address that does not answer in time is just an unavailable
     * address.
     */
    suspend fun ping(serverUrl: String, timeoutMillis: Long = 1_500): Boolean

    suspend fun searchAssets(
        connection: ImmichConnection,
        page: Int,
        pageSize: Int,
        filter: LibraryFilter,
        month: String? = null,
        albumId: String? = null,
        query: String? = null,
    ): AssetPage

    /** Search dedicated to the composer; it does not change the main library, which stays photographic. */
    suspend fun searchMedia(
        connection: ImmichConnection,
        page: Int,
        pageSize: Int,
        types: Set<AssetType> = setOf(AssetType.IMAGE, AssetType.VIDEO),
        query: String? = null,
    ): AssetPage

    suspend fun getAlbums(connection: ImmichConnection): List<ImmichAlbum>

    /** A new album of this account, with [assetIds] already in it. */
    suspend fun createAlbum(connection: ImmichConnection, name: String, assetIds: List<String>): ImmichAlbum =
        error("Albums cannot be created here")

    /** A photo already in the album counts in [AlbumAddition.alreadyThere], not as a failure. */
    suspend fun addToAlbum(connection: ImmichConnection, albumId: String, assetIds: List<String>): AlbumAddition =
        error("Albums cannot be changed here")

    /** Takes them out of the album only; they stay in the library. Returns how many came out. */
    suspend fun removeFromAlbum(connection: ImmichConnection, albumId: String, assetIds: List<String>): Int =
        error("Albums cannot be changed here")

    suspend fun renameAlbum(connection: ImmichConnection, albumId: String, name: String): Unit =
        error("Albums cannot be changed here")

    /** The album goes; its photos stay in the library. */
    suspend fun deleteAlbum(connection: ImmichConnection, albumId: String): Unit =
        error("Albums cannot be deleted here")

    /**
     * Photos and videos by what is in them ("beach", "birthday cake"), the closest first. The
     * filters are the grid's: an album, favourites only, a date range. [language] is the query's.
     */
    suspend fun smartSearch(
        connection: ImmichConnection,
        page: Int,
        pageSize: Int,
        query: String,
        albumId: String? = null,
        favoritesOnly: Boolean = false,
        takenAfter: String? = null,
        takenBefore: String? = null,
        language: String? = null,
    ): AssetPage = error("This server cannot search by content here")

    /** Whether the server has searching by content on; false when it cannot be asked. */
    suspend fun smartSearchAvailable(connection: ImmichConnection): Boolean = false

    /** What is in the trash, photos and videos, the most recently trashed first. */
    suspend fun trashedAssets(connection: ImmichConnection, page: Int, pageSize: Int): AssetPage =
        error("This server has no trash here")

    /** How many days the server keeps the trash before deleting it; null when it does not say. */
    suspend fun trashDays(connection: ImmichConnection): Int? = null

    suspend fun restoreFromTrash(connection: ImmichConnection, assetIds: List<String>): Unit =
        error("This server has no trash here")

    /** Everything in the trash, deleted for good. */
    suspend fun emptyTrash(connection: ImmichConnection): Unit = error("This server has no trash here")
    suspend fun getTimeBuckets(connection: ImmichConnection): List<ImmichTimeBucket>

    /**
     * All the photos of a month, in a single request.
     *
     * The response comes in columns — one vector of ids, another of dates, another of favourites —
     * which is why it fits in about a tenth of what the paginated search costs for the same
     * content. In exchange it has no file name or checksum: it is for knowing *which* photos exist
     * and where they are in the timeline, not for describing them in full.
     */
    suspend fun getTimeBucketAssets(connection: ImmichConnection, timeBucket: String): List<ImmichAsset>

    /** The photo with EXIF, which the paginated search does not bring. */
    suspend fun getAssetDetail(connection: ImmichConnection, assetId: String): ImmichAssetDetail

    suspend fun setFavorite(connection: ImmichConnection, assetId: String, isFavorite: Boolean)

    /** Several in a single request — a selection of two hundred is not two hundred requests. */
    suspend fun setFavorites(connection: ImmichConnection, assetIds: List<String>, isFavorite: Boolean) {
        assetIds.forEach { setFavorite(connection, it, isFavorite) }
    }

    /** Moves to Immich's trash; `force` deletes for good. */
    suspend fun deleteAsset(connection: ImmichConnection, assetId: String, force: Boolean = false)

    suspend fun deleteAssets(connection: ImmichConnection, assetIds: List<String>, force: Boolean = false) {
        assetIds.forEach { deleteAsset(connection, it, force) }
    }

    fun thumbnailUrl(connection: ImmichConnection, assetId: String): String
    fun previewUrl(connection: ImmichConnection, assetId: String): String
    fun videoPlaybackUrl(connection: ImmichConnection, assetId: String): String

    suspend fun downloadOriginal(connection: ImmichConnection, assetId: String, destination: File)

    /** The server's own edits of the photo — crop, rotation, mirror — in the order it applies them. */
    suspend fun getAssetEdits(connection: ImmichConnection, assetId: String): List<ImmichEdit> =
        error("Immich edits unavailable")

    /** Replaces all of the photo's edits; an empty list removes them. */
    suspend fun replaceAssetEdits(connection: ImmichConnection, assetId: String, edits: List<ImmichEdit>): Unit =
        error("Immich edits unavailable")

    suspend fun exportEditedAsset(
        connection: ImmichConnection,
        originalAssetId: String,
        jpeg: File,
        fileName: String,
        fileCreatedAt: String,
    ): ImmichExportResult

    /**
     * Standalone upload of a file — a composition, an exported copy, a photo received from another
     * app. It never creates a stack with an original. [ImmichUploadResult.status] is `duplicate`
     * when the server already had a file with the same content.
     */
    suspend fun uploadAsset(
        connection: ImmichConnection,
        file: File,
        fileName: String,
        mimeType: String,
        fileCreatedAt: String,
    ): ImmichUploadResult
}

/**
 * Fails now with [ImmichApiException.MissingPermission] if the key lacks [permission].
 *
 * It is for what costs work before the request — a confirmation, rendering an export: whoever
 * left the permission out hears it on the tap, not at the end. When the key's permissions cannot be
 * read it lets through, and the request itself answers.
 */
suspend fun ImmichApi.requirePermission(connection: ImmichConnection, permission: String) {
    val granted = try {
        keyPermissions(connection)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        return
    }
    if (!ImmichKeyPermissions.grants(granted, permission)) throw ImmichApiException.MissingPermission(listOf(permission))
}

/**
 * The header the API key goes in, for whoever requests images from the server outside this client
 * — the Android image loader, the desktop thumbnail cache. It comes from the generated contract.
 */
const val IMMICH_API_KEY_HEADER: String = ImmichContract.API_KEY_HEADER

data class ImmichUploadResult(val assetId: String, val status: String)

data class ImmichExportResult(
    val assetId: String,
    val status: String,
    val stackedWithOriginal: Boolean,
    val stackingFailed: Boolean,
)

object ImmichCompatibility {
    val minimum = ServerVersion(2, 6, 0)
    val latestContractValidated = ServerVersion(3, 1, 0)
}

/**
 * The Immich client's failures, each with the sentence for the person ([UserMessage]). The
 * technical detail — the body of an error response, for example — goes in [detail], for the logs.
 */
sealed class ImmichApiException(
    message: UserMessage,
    args: List<Any> = emptyList(),
    cause: Throwable? = null,
    val detail: String = "",
) : UserMessageException(message, args, cause) {
    class InvalidUrl : ImmichApiException(UserMessage.IMMICH_INVALID_URL)
    class Authentication : ImmichApiException(UserMessage.IMMICH_KEY_REJECTED)

    /** The key is valid but was created without [permissions]. */
    class MissingPermission(val permissions: List<String>, detail: String = "") : ImmichApiException(
        UserMessage.IMMICH_PERMISSION_MISSING,
        listOf(permissions.joinToString(", ")),
        detail = detail,
    )
    class UnsupportedVersion(val actual: ServerVersion) : ImmichApiException(
        UserMessage.IMMICH_VERSION_UNSUPPORTED,
        listOf(actual.toString(), ImmichCompatibility.minimum.toString()),
    )
    class Server(val status: Int, detail: String) : ImmichApiException(
        UserMessage.IMMICH_SERVER_ERROR,
        listOf(status),
        detail = detail,
    )
    class Connection(cause: Throwable) : ImmichApiException(UserMessage.IMMICH_UNREACHABLE, cause = cause)
}
