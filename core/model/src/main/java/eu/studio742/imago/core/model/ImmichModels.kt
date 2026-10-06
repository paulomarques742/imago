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
)

@Serializable
enum class AssetType { IMAGE, VIDEO, AUDIO, OTHER }

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
)

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
)

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
