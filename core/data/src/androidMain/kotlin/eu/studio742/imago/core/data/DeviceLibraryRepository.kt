package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.UserText
import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.paging.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.studio742.imago.core.data.db.*
import eu.studio742.imago.core.model.*
import java.io.File
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton

class PendingMediaAction(val intent: PendingIntent, val result: CompletableDeferred<Boolean>)

@OptIn(FlowPreview::class)
@Singleton
class DeviceLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    database: ImmichRoomDatabase,
) : LocalCatalogLibrary(database) {
    private val resolver = context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actionChannel = Channel<PendingMediaAction>(Channel.BUFFERED)
    val actions = actionChannel.receiveAsFlow()
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val accessRevision = MutableStateFlow(0)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    override val rememberedTransfer = MutableStateFlow(
        preferences.getString(TRANSFER_KEY, null)?.let { saved -> FolderTransfer.entries.firstOrNull { it.name == saved } },
    )
    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refreshes.tryEmit(Unit) }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        scope.launch { refreshes.debounce(300).collect { runCatching { syncCatalog() } } }
    }
    override fun accessSummary(): UserText {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return UserText(
            when {
                Build.VERSION.SDK_INT <= 32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> UserMessage.DEVICE_ACCESS_FULL
                Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> UserMessage.DEVICE_ACCESS_FULL
                Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> UserMessage.DEVICE_ACCESS_SELECTED
                Build.VERSION.SDK_INT >= 33 && (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) -> UserMessage.DEVICE_ACCESS_ONE_TYPE
                else -> UserMessage.DEVICE_ACCESS_NONE
            },
        )
    }
    override fun refreshAccess() { accessRevision.value++; refreshes.tryEmit(Unit) }
    override suspend fun syncCatalog() = withContext(Dispatchers.IO) {
        refreshing {
            try {
                val rows = mutableListOf<AssetEntity>()
                for ((collection, type) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to AssetType.IMAGE,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI to AssetType.VIDEO)) {
                    val columns = arrayOf("_id", "_display_name", MediaStore.Images.ImageColumns.DATE_TAKEN, "date_added", "date_modified", "_size",
                        "width", "height", "mime_type", "is_favorite", "bucket_id", "bucket_display_name", "volume_name") +
                        if (type == AssetType.VIDEO) arrayOf("duration") else emptyArray()
                    val cursorResult = try { resolver.query(collection, columns, "is_trashed = 0 AND is_pending = 0", null, null) } catch (_: SecurityException) { null }
                    cursorResult?.use { cursor ->
                        fun str(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { cursor.getString(it) }
                        fun number(name: String) = str(name)?.toLongOrNull()
                        while (cursor.moveToNext()) {
                            val volume = str("volume_name") ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                            val itemCollection = if (type == AssetType.VIDEO) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
                            val id = ContentUris.withAppendedId(itemCollection, checkNotNull(number("_id"))).toString()
                            val time = (number(MediaStore.Images.ImageColumns.DATE_TAKEN)?.takeIf { it > 0 } ?: ((number("date_added") ?: 0) * 1000))
                            val instant = Instant.ofEpochMilli(time)
                            val folder = "$volume:${str("bucket_id")}"
                            rows += AssetEntity(DEVICE_LIBRARY_ID, id,
                                "local:${number("_size")}:${number("date_modified")}", str("_display_name").orEmpty(),
                                java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant),
                                instant.atZone(ZoneId.systemDefault()).toLocalDateTime().toString(), number("width"), number("height"),
                                number("is_favorite") == 1L, false, false, type.name, str("mime_type"),
                                number("duration").takeIf { type == AssetType.VIDEO }, folder, str("bucket_display_name") ?: "/",
                                sizeBytes = number("_size"))
                        }
                    }
                }
                replaceCatalog(rows)
            } catch (error: SecurityException) {
                // A revoked grant must never leave inaccessible cached items in the grid.
                database.assetDao().deleteLibrary(DEVICE_LIBRARY_ID)
            }
        }
    }
    override suspend fun assetDetail(assetId: String): ImmichAssetDetail = withContext(Dispatchers.IO) {
        val asset = (database.assetDao().asset(DEVICE_LIBRARY_ID, assetId) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)).toDomain()
        val exif = runCatching { resolver.openInputStream(Uri.parse(assetId))?.use { stream ->
            val data = ExifInterface(stream)
            AssetExif(fNumber = data.getAttribute(ExifInterface.TAG_F_NUMBER)?.toFloatOrNull(),
                exposureTime = data.getAttribute(ExifInterface.TAG_EXPOSURE_TIME), iso = data.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.toIntOrNull(),
                make = data.getAttribute(ExifInterface.TAG_MAKE), model = data.getAttribute(ExifInterface.TAG_MODEL))
        } }.getOrNull() ?: AssetExif()
        ImmichAssetDetail(asset, exif)
    }
    private suspend fun request(intent: PendingIntent) {
        val result = CompletableDeferred<Boolean>()
        actionChannel.send(PendingMediaAction(intent, result))
        if (!result.await()) throw CancellationException("Operation cancelled")
        syncCatalog()
    }
    override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = request(
        MediaStore.createFavoriteRequest(resolver, listOf(Uri.parse(assetId)), isFavorite))
    override suspend fun deleteAsset(assetId: String) = request(
        MediaStore.createTrashRequest(resolver, listOf(Uri.parse(assetId)), true))
    override suspend fun setFavorites(assetIds: List<String>, isFavorite: Boolean) {
        if (assetIds.isNotEmpty()) request(MediaStore.createFavoriteRequest(resolver, assetIds.map(Uri::parse), isFavorite))
    }
    override suspend fun deleteAssets(assetIds: List<String>) {
        if (assetIds.isNotEmpty()) request(MediaStore.createTrashRequest(resolver, assetIds.map(Uri::parse), true))
    }
    override fun rememberTransfer(transfer: FolderTransfer?) {
        preferences.edit().apply { if (transfer == null) remove(TRANSFER_KEY) else putString(TRANSFER_KEY, transfer.name) }.apply()
        rememberedTransfer.value = transfer
    }

    /** The catalogue's folders, each with what may be done to it: only the ones in Pictures/ change. */
    override suspend fun albums(): List<ImmichAlbum> {
        val paths = folderPaths()
        return super.albums().map { album ->
            val editable = paths[album.id]?.let(::isEditableDeviceFolder) == true
            album.copy(isFolder = true, canEditContent = editable, isOwned = editable)
        }
    }

    override suspend fun fileIntoAlbum(albumId: String, assetIds: List<String>, transfer: FolderTransfer): AlbumAddition {
        val target = editableFolder(albumId)
        return transferTo(target, assetIds, transfer).also { syncCatalog() }
    }

    override suspend fun createFolderAlbum(name: String, assetIds: List<String>, transfer: FolderTransfer): ImmichAlbum {
        val target = deviceAlbumFolder(name) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        val result = transferTo(target, assetIds, transfer)
        check(result.added + result.alreadyThere > 0) { "No photo reached the new album" }
        syncCatalog()
        val folderId = folderPaths().entries.firstOrNull { it.value.equals(target, ignoreCase = true) }?.key
        return albums().first { it.id == folderId }
    }

    /** Renaming a folder is moving everything in it to a folder with the new name. */
    override suspend fun renameAlbum(albumId: String, name: String) {
        editableFolder(albumId)
        val target = deviceAlbumFolder(name) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        val result = transferTo(target, idsIn(albumId), FolderTransfer.MOVE)
        syncCatalog()
        check(result.failed == 0) { "${result.failed} files could not be moved to $target" }
    }

    /** A folder only exists with files in it: deleting the album is sending them to the trash. */
    override suspend fun deleteAlbum(albumId: String) {
        editableFolder(albumId)
        deleteAssets(idsIn(albumId))
    }

    private suspend fun idsIn(albumId: String): List<String> =
        database.assetDao().allAssets(DEVICE_LIBRARY_ID).filter { it.folderId == albumId }.map { it.id }

    private suspend fun editableFolder(albumId: String): String =
        folderPaths()[albumId]?.takeIf(::isEditableDeviceFolder) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)

    /** Each folder of the catalogue (`volume:bucket`) and its path, as MediaStore writes it: `Pictures/Trip/`. */
    private suspend fun folderPaths(): Map<String, String> = withContext(Dispatchers.IO) {
        val paths = mutableMapOf<String, String>()
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            val columns = arrayOf("volume_name", "bucket_id", MediaStore.MediaColumns.RELATIVE_PATH)
            runCatching { resolver.query(collection, columns, "is_trashed = 0 AND is_pending = 0", null, null) }.getOrNull()?.use { cursor ->
                while (cursor.moveToNext()) {
                    val volume = cursor.getString(0) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    val path = cursor.getString(2) ?: continue
                    paths.putIfAbsent("$volume:${cursor.getString(1)}", path)
                }
            }
        }
        paths
    }

    /**
     * Moves or copies into [target]. Moving someone else's file needs the person's consent, asked
     * once for all of them; copying writes new files of IMAGO's, which needs none. One that fails
     * does not stop the others.
     */
    private suspend fun transferTo(target: String, assetIds: List<String>, transfer: FolderTransfer): AlbumAddition {
        val rows = assetIds.mapNotNull { database.assetDao().asset(DEVICE_LIBRARY_ID, it) }
        val paths = folderPaths()
        val (already, pending) = rows.partition { paths[it.folderId]?.equals(target, ignoreCase = true) == true }
        if (pending.isEmpty()) return AlbumAddition(0, already.size, assetIds.size - rows.size)
        if (transfer == FolderTransfer.MOVE) {
            val result = CompletableDeferred<Boolean>()
            actionChannel.send(PendingMediaAction(MediaStore.createWriteRequest(resolver, pending.map { Uri.parse(it.id) }), result))
            if (!result.await()) throw CancellationException("Operation cancelled")
        }
        val moved = withContext(Dispatchers.IO) {
            pending.count { row ->
                runCatching { if (transfer == FolderTransfer.MOVE) move(row, target) else copy(row, target) }.getOrDefault(false)
            }
        }
        return AlbumAddition(added = moved, alreadyThere = already.size, failed = assetIds.size - already.size - moved)
    }

    private fun move(row: AssetEntity, target: String): Boolean =
        resolver.update(Uri.parse(row.id), ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }, null, null) > 0

    /** A new file of the same kind, with the same name and date; left pending until it is whole. */
    private fun copy(row: AssetEntity, target: String): Boolean {
        val collection = if (row.type == AssetType.VIDEO.name) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, row.originalFileName)
            row.mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, target)
            runCatching { Instant.parse(row.fileCreatedAt).toEpochMilli() }.getOrNull()?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val copy = resolver.insert(collection, values) ?: return false
        return try {
            val source = resolver.openInputStream(Uri.parse(row.id)) ?: error("The original is no longer readable")
            source.use { input -> resolver.openOutputStream(copy)?.use { input.copyTo(it) } ?: error("The copy cannot be written") }
            resolver.update(copy, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (error: Exception) {
            resolver.delete(copy, null, null)
            false
        }
    }

    override suspend fun downloadOriginal(assetId: String, destination: File) = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(Uri.parse(assetId)) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
        Unit
    }

    private companion object {
        const val PREFERENCES = "imago-gallery"
        const val TRANSFER_KEY = "folder-transfer"
    }
}
