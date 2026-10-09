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
                val (rows, paths) = readMedia(null, null)
                replaceCatalog(rows)
                // The whole picture: a folder no longer listed has emptied.
                folderPathCache = paths
            } catch (error: SecurityException) {
                // A revoked grant must never leave inaccessible cached items in the grid.
                database.assetDao().deleteLibrary(DEVICE_LIBRARY_ID)
            }
        }
    }

    /**
     * Each folder (`volume:bucket`) and its path as MediaStore writes it (`Pictures/Trip/`), kept from
     * the last full read: asking MediaStore for them went through every photo, several times per
     * album action.
     */
    @Volatile private var folderPathCache: Map<String, String> = emptyMap()

    /** Reads again only these photos, after IMAGO itself moved, copied, marked or deleted them. */
    private suspend fun refreshRows(assetIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext
        val rows = mutableListOf<AssetEntity>()
        val paths = mutableMapOf<String, String>()
        // MediaStore ids are unique across images and videos; the batches keep the IN list under SQLite's limit.
        assetIds.mapNotNull { runCatching { ContentUris.parseId(Uri.parse(it)) }.getOrNull() }.distinct().chunked(500).forEach { batch ->
            val (found, foundPaths) = readMedia("_id IN (${batch.joinToString(",")})", null)
            rows += found
            paths += foundPaths
        }
        replaceRows(assetIds, rows)
        folderPathCache = folderPathCache + paths
    }

    override suspend fun rowsOf(ids: Collection<String>): List<AssetEntity> = withContext(Dispatchers.IO) {
        ids.mapNotNull { runCatching { ContentUris.parseId(Uri.parse(it)) }.getOrNull() }.distinct().chunked(500).flatMap { batch ->
            readMedia("_id IN (${batch.joinToString(",")})", null).first
        }
    }

    /** The photos and videos [selection] picks, as catalogue rows, with the path of each one's folder. */
    private fun readMedia(selection: String?, args: Array<String>?): Pair<List<AssetEntity>, Map<String, String>> {
        val rows = mutableListOf<AssetEntity>()
        val paths = mutableMapOf<String, String>()
        val where = listOfNotNull("is_trashed = 0 AND is_pending = 0", selection).joinToString(" AND ") { "($it)" }
        for ((collection, type) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to AssetType.IMAGE,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to AssetType.VIDEO)) {
            val columns = arrayOf("_id", "_display_name", MediaStore.Images.ImageColumns.DATE_TAKEN, "date_added", "date_modified", "_size",
                "width", "height", "mime_type", "is_favorite", "bucket_id", "bucket_display_name", "volume_name",
                MediaStore.MediaColumns.RELATIVE_PATH) +
                if (type == AssetType.VIDEO) arrayOf("duration") else emptyArray()
            val cursorResult = try { resolver.query(collection, columns, where, args, null) } catch (_: SecurityException) { null }
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
                    str(MediaStore.MediaColumns.RELATIVE_PATH)?.let { paths.putIfAbsent(folder, it) }
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
        return rows to paths
    }
    /**
     * What the file says about itself, the catalogue's size and dimensions, the folder as the Files
     * app shows it, and — when Android lets it be seen — where it was taken.
     */
    override suspend fun assetDetail(assetId: String): ImmichAssetDetail = withContext(Dispatchers.IO) {
        val row = database.assetDao().asset(DEVICE_LIBRARY_ID, assetId) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        val exif = runCatching { resolver.openInputStream(Uri.parse(assetId))?.use { stream ->
            val data = ExifInterface(stream)
            fun number(tag: String) = data.getAttributeDouble(tag, Double.NaN).takeUnless { it.isNaN() }
            AssetExif(fNumber = data.getAttribute(ExifInterface.TAG_F_NUMBER)?.toFloatOrNull(),
                exposureTime = data.getAttribute(ExifInterface.TAG_EXPOSURE_TIME), iso = data.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.toIntOrNull(),
                make = data.getAttribute(ExifInterface.TAG_MAKE), model = data.getAttribute(ExifInterface.TAG_MODEL),
                focalLength = number(ExifInterface.TAG_FOCAL_LENGTH)?.toFloat(),
                exposureBias = number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.toFloat())
        } }.getOrNull() ?: AssetExif()
        val place = if (row.type == AssetType.IMAGE.name && canReadLocations()) runCatching { readLocation(assetId) }.getOrNull() else null
        val folder = runCatching { folderPaths()[row.folderId] }.getOrNull()?.trim('/')
        ImmichAssetDetail(
            asset = row.toDomain(),
            exif = exif.copy(
                fileSizeBytes = row.sizeBytes,
                imageWidth = exif.imageWidth ?: row.width?.toInt(),
                imageHeight = exif.imageHeight ?: row.height?.toInt(),
                latitude = place?.first,
                longitude = place?.second,
            ),
            folder = folder,
        )
    }

    override fun canRename(assetId: String): Boolean = true

    /**
     * The file's name in MediaStore, which moves the file itself. A name another photo of the folder
     * already has is refused before Android is asked; a file of another app it cannot see, Android
     * refuses itself.
     */
    override suspend fun renameAsset(assetId: String, name: String): String {
        val row = database.assetDao().asset(DEVICE_LIBRARY_ID, assetId) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        val target = requireNotNull(renamedFile(row.originalFileName, name)) { "Nothing of \"$name\" makes a file name" }
        if (target == row.originalFileName) return assetId
        val folder = folderPaths()[row.folderId]
        if (folder != null && withContext(Dispatchers.IO) { nameTaken(folder, target, assetId) }) {
            throw UserMessageException(UserMessage.FILE_NAME_TAKEN)
        }
        consent(MediaStore.createWriteRequest(resolver, listOf(Uri.parse(assetId))))
        val changed = withContext(Dispatchers.IO) {
            resolver.update(Uri.parse(assetId), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, target) }, null, null)
        }
        check(changed > 0) { "MediaStore did not rename $assetId" }
        refreshRows(listOf(assetId))
        return assetId
    }

    private fun nameTaken(folder: String, name: String, assetId: String): Boolean {
        val id = ContentUris.parseId(Uri.parse(assetId)).toString()
        return resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? COLLATE NOCASE AND ${MediaStore.MediaColumns._ID} != ?",
            arrayOf(folder, name, id),
            null,
        )?.use { it.count > 0 } ?: false
    }
    /** Asks Android (a dialog, or not as a media management app) and reads again what it touched. */
    private suspend fun request(intent: PendingIntent, touched: Collection<String>) {
        consent(intent)
        refreshRows(touched)
    }

    private suspend fun consent(intent: PendingIntent) {
        val result = CompletableDeferred<Boolean>()
        actionChannel.send(PendingMediaAction(intent, result))
        if (!result.await()) throw CancellationException("Operation cancelled")
    }
    override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = setFavorites(listOf(assetId), isFavorite)
    override suspend fun deleteAsset(assetId: String) = deleteAssets(listOf(assetId))
    override suspend fun setFavorites(assetIds: List<String>, isFavorite: Boolean) {
        if (assetIds.isNotEmpty()) request(MediaStore.createFavoriteRequest(resolver, assetIds.map(Uri::parse), isFavorite), assetIds)
    }
    override suspend fun deleteAssets(assetIds: List<String>) {
        if (assetIds.isNotEmpty()) request(MediaStore.createTrashRequest(resolver, assetIds.map(Uri::parse), true), assetIds)
    }
    override val openedFiles = OpenedFileLibrary(context)

    override val hasTrash: Boolean get() = true

    /** The phone's trash, the soonest to go first; each item says when it goes. */
    override suspend fun trash(): TrashContents = withContext(Dispatchers.IO) {
        val items = mutableListOf<TrashedAsset>()
        for ((collection, type) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to AssetType.IMAGE,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to AssetType.VIDEO)) {
            val columns = arrayOf("_id", "_display_name", MediaStore.Images.ImageColumns.DATE_TAKEN, "date_added", "width", "height",
                "mime_type", "volume_name", MediaStore.MediaColumns.DATE_EXPIRES) +
                if (type == AssetType.VIDEO) arrayOf("duration") else emptyArray()
            val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
            runCatching { resolver.query(collection, columns, args, null) }.getOrNull()?.use { cursor ->
                fun str(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { cursor.getString(it) }
                fun number(name: String) = str(name)?.toLongOrNull()
                while (cursor.moveToNext()) {
                    val volume = str("volume_name") ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    val itemCollection = if (type == AssetType.VIDEO) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
                    val id = ContentUris.withAppendedId(itemCollection, number("_id") ?: continue).toString()
                    val time = number(MediaStore.Images.ImageColumns.DATE_TAKEN)?.takeIf { it > 0 } ?: ((number("date_added") ?: 0) * 1000)
                    val instant = Instant.ofEpochMilli(time)
                    // MediaStore keeps the trashed file's name with a ".trashed-<expiry>-" prefix.
                    val name = str("_display_name").orEmpty().replace(Regex("""^\.trashed-\d+-"""), "")
                    val asset = ImmichAsset(id, "", name,
                        java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant),
                        instant.atZone(ZoneId.systemDefault()).toLocalDateTime().toString(), number("width"), number("height"),
                        isFavorite = false, isEdited = false, type = type, mimeType = str("mime_type"),
                        durationMs = number("duration").takeIf { type == AssetType.VIDEO })
                    val expires = number(MediaStore.MediaColumns.DATE_EXPIRES)?.let { Instant.ofEpochSecond(it).toString() }
                    items += TrashedAsset(asset, expires)
                }
            }
        }
        TrashContents(items.sortedBy { it.expiresAt ?: "" })
    }

    override suspend fun restoreFromTrash(assetIds: List<String>) {
        if (assetIds.isEmpty()) return
        request(MediaStore.createTrashRequest(resolver, assetIds.map(Uri::parse), false), assetIds)
    }

    override suspend fun deleteForever(assetIds: List<String>) {
        if (assetIds.isEmpty()) return
        consent(MediaStore.createDeleteRequest(resolver, assetIds.map(Uri::parse)))
    }

    override suspend fun emptyTrash() = deleteForever(trash().items.map { it.asset.id })

    override suspend fun openFromOtherApp(uris: List<String>, neighbours: Boolean): OpenedView? {
        val parsed = uris.map(Uri::parse)
        // A single photo of the gallery opens among its album, as the gallery would show it.
        if (neighbours && parsed.size == 1) {
            catalogRowFor(parsed.single(), refreshStaleFolder = true)?.let { row ->
                val album = row.folderId?.let { database.assetDao().inFolder(DEVICE_LIBRARY_ID, it) }.orEmpty()
                val index = album.indexOfFirst { it.id == row.id }
                if (index >= 0) {
                    // A WhatsApp folder can hold tens of thousands; the detail only needs some on each side.
                    val window = album.subList((index - NEIGHBOURS).coerceAtLeast(0), (index + NEIGHBOURS + 1).coerceAtMost(album.size))
                    return OpenedView(window.map { it.toDomain().withLibrary(DEVICE_LIBRARY_ID) }, window.indexOfFirst { it.id == row.id })
                }
            }
        }
        val assets = parsed.mapNotNull { uri ->
            catalogRowFor(uri)?.toDomain()?.withLibrary(DEVICE_LIBRARY_ID)
                ?: openedFiles.describe(uri)?.withLibrary(OPENED_LIBRARY_ID)
        }
        return assets.takeIf { it.isNotEmpty() }?.let { OpenedView(it, 0) }
    }

    private fun ImmichAsset.withLibrary(libraryId: String) = copy(id = AssetReference(libraryId, id).encode())

    /**
     * The catalogue row of a photo of the gallery, whichever way it came: a MediaStore `Uri` (the
     * camera), or a document of the files app that MediaStore also knows. Read on the spot if the
     * catalogue does not have it yet — a photo just taken. Null when it is not in the gallery, or the
     * gallery cannot be read.
     */
    private suspend fun catalogRowFor(uri: Uri, refreshStaleFolder: Boolean = false): AssetEntity? = withContext(Dispatchers.IO) {
        val media = when {
            uri.authority == MediaStore.AUTHORITY -> uri
            android.provider.DocumentsContract.isDocumentUri(context, uri) -> runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            else -> null
        } ?: return@withContext null
        val mediaId = runCatching { ContentUris.parseId(media) }.getOrNull()?.takeIf { it >= 0 } ?: return@withContext null
        val suffix = "%/media/$mediaId"
        database.assetDao().byIdSuffix(DEVICE_LIBRARY_ID, suffix) ?: run {
            refreshRows(listOf(media.toString()))
            // A photo the catalogue did not know says it is behind on that folder too: the others
            // just taken or copied there would be missing from the album it opens among.
            database.assetDao().byIdSuffix(DEVICE_LIBRARY_ID, suffix)?.also { row ->
                if (refreshStaleFolder) row.folderId?.let { refreshFolder(it) }
            }
        }
    }

    /** Reads one folder again from MediaStore: what is new comes in, what left goes out. */
    private suspend fun refreshFolder(folderId: String) = withContext(Dispatchers.IO) {
        val (rows, paths) = readMedia("bucket_id = ?", arrayOf(folderId.substringAfter(':')))
        val inFolder = rows.filter { it.folderId == folderId }
        replaceRows(database.assetDao().idsInFolder(DEVICE_LIBRARY_ID, folderId) + inFolder.map { it.id }, inFolder)
        folderPathCache = folderPathCache + paths
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
        return transferTo(target, assetIds, transfer)
    }

    override suspend fun createFolderAlbum(name: String, assetIds: List<String>, transfer: FolderTransfer, place: String?): ImmichAlbum {
        val target = deviceAlbumFolder(name) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        val result = transferTo(target, assetIds, transfer)
        check(result.added + result.alreadyThere > 0) { "No photo reached the new album" }
        val folderId = folderPaths().entries.firstOrNull { it.value.equals(target, ignoreCase = true) }?.key
        return albums().first { it.id == folderId }
    }

    /** Renaming a folder is moving everything in it to a folder with the new name, which MediaStore gives another id. */
    override suspend fun renameAlbum(albumId: String, name: String): String {
        editableFolder(albumId)
        val target = deviceAlbumFolder(name) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        val result = transferTo(target, idsIn(albumId), FolderTransfer.MOVE)
        check(result.failed == 0) { "${result.failed} files could not be moved to $target" }
        return folderPaths().entries.firstOrNull { it.value.equals(target, ignoreCase = true) }?.key ?: albumId
    }

    /** A folder only exists with files in it: deleting the album is sending them to the trash. */
    override suspend fun deleteAlbum(albumId: String) {
        editableFolder(albumId)
        deleteAssets(idsIn(albumId))
    }

    private suspend fun idsIn(albumId: String): List<String> = database.assetDao().idsInFolder(DEVICE_LIBRARY_ID, albumId)

    private suspend fun editableFolder(albumId: String): String =
        folderPaths()[albumId]?.takeIf(::isEditableDeviceFolder) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)

    /** The folders' paths; read in full only when no catalogue read has kept them yet. */
    private suspend fun folderPaths(): Map<String, String> {
        if (folderPathCache.isEmpty()) syncCatalog()
        return folderPathCache
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
        if (transfer == FolderTransfer.MOVE) consent(MediaStore.createWriteRequest(resolver, pending.map { Uri.parse(it.id) }))
        val touched = withContext(Dispatchers.IO) {
            pending.mapNotNull { row ->
                runCatching { if (transfer == FolderTransfer.MOVE) move(row, target) else copy(row, target) }.getOrNull()
            }
        }
        refreshRows(touched)
        val moved = touched.size
        return AlbumAddition(added = moved, alreadyThere = already.size, failed = assetIds.size - already.size - moved)
    }

    /** The moved photo's id, which does not change; null when it did not move. */
    private fun move(row: AssetEntity, target: String): String? =
        row.id.takeIf {
            resolver.update(Uri.parse(row.id), ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }, null, null) > 0
        }

    /** A new file of the same kind, with the same name and date; left pending until it is whole. Its id, or null. */
    private fun copy(row: AssetEntity, target: String): String? {
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
        val copy = resolver.insert(collection, values) ?: return null
        return try {
            val source = resolver.openInputStream(Uri.parse(row.id)) ?: error("The original is no longer readable")
            source.use { input -> resolver.openOutputStream(copy)?.use { input.copyTo(it) } ?: error("The copy cannot be written") }
            resolver.update(copy, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            copy.toString()
        } catch (error: Exception) {
            resolver.delete(copy, null, null)
            null
        }
    }

    /** Without this permission Android hands out the files with the GPS of the EXIF wiped. */
    override fun canReadLocations(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun readLocation(assetId: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val original = MediaStore.setRequireOriginal(Uri.parse(assetId))
        resolver.openInputStream(original)?.use { input ->
            val place = FloatArray(2)
            if (ExifInterface(input).getLatLong(place)) place[0].toDouble() to place[1].toDouble() else null
        }
    }

    override suspend fun downloadOriginal(assetId: String, destination: File) = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(Uri.parse(assetId)) ?: throw UserMessageException(UserMessage.FILE_UNAVAILABLE)
        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
        Unit
    }

    private companion object {
        const val PREFERENCES = "imago-gallery"
        const val NEIGHBOURS = 300
        const val TRANSFER_KEY = "folder-transfer"
    }
}
