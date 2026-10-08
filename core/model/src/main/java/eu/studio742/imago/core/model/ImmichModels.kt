package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ImmichConnection(
    val serverUrl: String,
    val apiKey: String,
    val libraryId: String? = null,
)

@Serializable
data class ImmichAsset(
    val id: String,
    val checksum: String,
    val originalFileName: String,
    val fileCreatedAt: String,
    val localDateTime: String,
    val width: Long?,
    val height: Long?,
    val isFavorite: Boolean,
    val isEdited: Boolean,
    val hasLocalRecipe: Boolean = false,
    val type: AssetType,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    /** In the unified library: this photo is also on the server (or only there). Nowhere else set. */
    val isOnServer: Boolean = false,
    /** Out of the timeline without being deleted: Immich's archive, or this app's for the device. */
    val isArchived: Boolean = false,
    /**
     * The Immich stack this photo is the cover of, and how many photos it holds. Only covers carry
     * it: the timeline returns one photo per stack, and the others are reached from the cover.
     */
    val stackId: String? = null,
    val stackCount: Int? = null,
)

@Serializable
enum class AssetType { IMAGE, VIDEO, AUDIO, OTHER }

/** An Immich stack: the photos it holds, and which of them is the cover the timeline shows. */
data class ImmichStack(val id: String, val primaryAssetId: String, val assets: List<ImmichAsset>)

/**
 * The EXIF fields the detail screen's strip shows.
 *
 * All are optional because a photo without metadata is normal — a scan, an export without EXIF or
 * a file edited in another tool. The strip shows only what exists.
 */
@Serializable
data class AssetExif(
    val fNumber: Float? = null,
    val exposureTime: String? = null,
    val iso: Int? = null,
    val focalLength: Float? = null,
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val description: String? = null,
    /** As stored in the file, before the orientation turns it. */
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    /** Immich's text: `1`..`8`, or `90` and `-90` from some sources. */
    val orientation: String? = null,
    val fileSizeBytes: Long? = null,
    /** Exposure compensation, in stops. */
    val exposureBias: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Where the server placed it; the device has to ask the system for an address. */
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
) {
    /**
     * The size Immich checks a crop against: the stored one, swapped when the orientation turns the
     * photo a quarter. The list of orientations that swap is the server's own (`isFlipped`), so that
     * a crop computed here is never refused as out of bounds there.
     */
    fun serverImageSize(): ImageSize? {
        val width = imageWidth?.takeIf { it > 0 } ?: return null
        val height = imageHeight?.takeIf { it > 0 } ?: return null
        val swaps = orientation?.toIntOrNull() in setOf(5, 6, 7, 8, -90, 90)
        return if (swaps) ImageSize(height, width) else ImageSize(width, height)
    }
}

data class ImageSize(val width: Int, val height: Int)

/**
 * One of Immich's own edits, which the server applies when it makes thumbnails and previews.
 *
 * The crop is in pixels of the image already turned by its EXIF orientation and always comes first;
 * the rotation, clockwise and a multiple of 90°, and the mirrors follow in the order of the list.
 */
sealed interface ImmichEdit {
    data class Crop(val x: Int, val y: Int, val width: Int, val height: Int) : ImmichEdit
    data class Rotate(val angle: Int) : ImmichEdit

    /** [MirrorAxis.VERTICAL] swaps left and right; [MirrorAxis.HORIZONTAL] swaps top and bottom. */
    data class Mirror(val axis: MirrorAxis) : ImmichEdit

    enum class MirrorAxis { HORIZONTAL, VERTICAL }
}

/**
 * The photo with the detail only `getAssetInfo` brings.
 *
 * The paginated search returns `ImmichAsset` without EXIF; this model is what the detail screen
 * asks for when opening a specific photo.
 */
@Serializable
data class ImmichAssetDetail(
    val asset: ImmichAsset,
    val exif: AssetExif = AssetExif(),
    /** The folder the file is in, as the person knows it ("DCIM/Camera"); null on a server. */
    val folder: String? = null,
    /** Who the server recognises in it. */
    val people: List<ImmichPerson> = emptyList(),
)

data class ServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ServerVersion> {
    override fun compareTo(other: ServerVersion): Int =
        compareValuesBy(this, other, ServerVersion::major, ServerVersion::minor, ServerVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

data class AssetPage(
    val items: List<ImmichAsset>,
    val nextPage: Int?,
)

data class ImmichAlbum(
    val id: String,
    val name: String,
    val description: String,
    val thumbnailAssetId: String?,
    val assetCount: Int,
    val startDate: String?,
    val endDate: String?,
    val shared: Boolean,
    /** Photos can be added and taken out: the owner's album, or one shared with this person as editor. */
    val canEditContent: Boolean = false,
    /** Renaming and deleting belong to the owner only. */
    val isOwned: Boolean = false,
    /**
     * An album that is a folder of the device. A photo lives in exactly one folder, so it is never
     * "taken out" of one: it is moved to another, or copied.
     */
    val isFolder: Boolean = false,
)

/** The photos of this day in an earlier [year], the most recent first. */
data class DayMemory(val year: Int, val assetIds: List<String>)

/** Where a photo was taken. [city] when the server knows it. */
data class MapMarker(val assetId: String, val latitude: Double, val longitude: Double, val city: String? = null)

/**
 * What a library can put on the map now. [reading] is how far it is in reading the locations of
 * its photos, while it still is; [needsLocationAccess] when Android hides them until it is allowed.
 */
data class MapContents(
    val markers: List<MapMarker>,
    val reading: MapReading? = null,
    val needsLocationAccess: Boolean = false,
)

data class MapReading(val done: Int, val total: Int)

/** Someone the server recognises in the photos. [name] is empty while nobody named them. */
@Serializable
data class ImmichPerson(val id: String, val name: String)

/** How photos go into a folder album: moved out of where they were, or copied and left there too. */
enum class FolderTransfer { MOVE, COPY }

/**
 * Where a new folder album can be made, when there is more than one place: one of the folders
 * chosen on a computer. [suggested] is the one already holding the first of the photos.
 */
data class AlbumPlace(val id: String, val name: String, val path: String, val suggested: Boolean = false)

/** What adding photos to an album did with each one. */
data class AlbumAddition(val added: Int, val alreadyThere: Int, val failed: Int)

data class ImmichTimeBucket(
    val month: String,
    val assetCount: Int,
)

/**
 * The library chips.
 *
 * `FAVORITES` and `RECENT` become Immich search parameters; `EDITED` does not, because the contract
 * does not expose `isEdited` as a criterion — that one is filtered over the local catalogue, which
 * is also the only place where the local recipe exists.
 */
enum class LibraryFilter { ALL, RECENT, FAVORITES, EDITED }

/** How many days back the "Recent" chip covers. */
const val RECENT_FILTER_DAYS = 30L
