package eu.studio742.imago.core.data

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.paging.PagingData
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Files another app opens with IMAGO that are not in the phone's gallery: an attachment, a file
 * another app serves. Each one is a library of its own, with the `Uri` as its id, and readable
 * while the permission the other app gave lasts — while the viewer that received it is open.
 *
 * It is never browsed, marked or deleted: the file is not IMAGO's. It is seen, edited, shared and
 * copied, which only needs the original.
 */
class OpenedFileLibrary(context: Context) : LibraryRepository {
    private val resolver = context.contentResolver
    private val described = ConcurrentHashMap<String, ImmichAsset>()

    /** What the other app says about the file; null when it is not a photo or a video, or cannot be read. */
    suspend fun describe(uri: Uri): ImmichAsset? = withContext(Dispatchers.IO) {
        val id = uri.toString()
        described[id]?.let { return@withContext it }
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        val type = when {
            mimeType?.startsWith("image/") == true -> AssetType.IMAGE
            mimeType?.startsWith("video/") == true -> AssetType.VIDEO
            else -> return@withContext null
        }
        var name: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        }
        val taken = if (type == AssetType.IMAGE) takenAt(uri) else null
        val instant = taken ?: Instant.now()
        val asset = ImmichAsset(
            id = id,
            checksum = "",
            originalFileName = name ?: uri.lastPathSegment.orEmpty(),
            fileCreatedAt = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant),
            localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime().toString(),
            width = null,
            height = null,
            isFavorite = false,
            isEdited = false,
            type = type,
            mimeType = mimeType,
        )
        described[id] = asset
        asset
    }

    /** The EXIF date, read as local time, as the camera wrote it. */
    private fun takenAt(uri: Uri): Instant? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")) }
                ?.atZone(ZoneId.systemDefault())?.toInstant()
        }
    }.getOrNull()

    override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?): Flow<PagingData<ImmichAsset>> =
        flowOf(PagingData.empty())
    override suspend fun albums(): List<ImmichAlbum> = emptyList()
    override suspend fun timeBuckets(): List<ImmichTimeBucket> = emptyList()
    override val catalogSync: StateFlow<CatalogSyncState> = MutableStateFlow(CatalogSyncState())
    override suspend fun syncCatalog() = Unit
    override suspend fun loadMonth(month: String) = Unit
    override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? = null
    override suspend fun indexOfDate(date: java.time.LocalDate, filter: LibraryFilter, month: String?, query: String?): Int? = null

    // Coil, the video player and the editor read a content Uri directly.
    override fun thumbnailUrl(assetId: String) = assetId
    override fun previewUrl(assetId: String) = assetId
    override fun videoPlaybackUrl(assetId: String) = assetId
    override fun apiKey(assetId: String) = ""

    override suspend fun assetDetail(assetId: String): ImmichAssetDetail = withContext(Dispatchers.IO) {
        val uri = Uri.parse(assetId)
        val asset = describe(uri) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        val exif = if (asset.type != AssetType.IMAGE) AssetExif() else runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val data = ExifInterface(stream)
                AssetExif(
                    fNumber = data.getAttribute(ExifInterface.TAG_F_NUMBER)?.toFloatOrNull(),
                    exposureTime = data.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                    iso = data.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.toIntOrNull(),
                    make = data.getAttribute(ExifInterface.TAG_MAKE),
                    model = data.getAttribute(ExifInterface.TAG_MODEL),
                )
            }
        }.getOrNull() ?: AssetExif()
        ImmichAssetDetail(asset, exif)
    }

    override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
    override suspend fun deleteAsset(assetId: String) = throw UserMessageException(UserMessage.FILE_UNAVAILABLE)

    override suspend fun downloadOriginal(assetId: String, destination: File) = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(Uri.parse(assetId)) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
        Unit
    }
}
