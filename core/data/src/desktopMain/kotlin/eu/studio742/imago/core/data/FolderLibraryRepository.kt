package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.UserText
import eu.studio742.imago.core.model.requireUser
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AlbumAddition
import eu.studio742.imago.core.model.AlbumPlace
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.FolderTransfer
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAssetDetail
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * This computer's library: the folders the user chose.
 *
 * It is the counterpart of the Android gallery. Each file is identified by its `file:` URI — the
 * same role as MediaStore's `content://`, and what the image loader opens directly. The folders and
 * the favourites stay in the configuration: the file system has no favourites, and the catalogue is
 * rebuilt from disk on every sync.
 *
 * The folders with photos are its albums. The ones inside the chosen folders are the person's to
 * change from here; the chosen ones take photos, and are changed in Settings.
 */
class FolderLibraryRepository(
    database: ImmichRoomDatabase,
    private val preferences: SecurePreferences,
    /** Sends a file, or an empty folder, to the Recycle Bin, where it can be recovered. */
    private val trash: (File) -> Boolean = { java.awt.Desktop.getDesktop().moveToTrash(it) },
) : LocalCatalogLibrary(database) {
    private val json = Json
    private val stringList = ListSerializer(String.serializer())
    private val folderList = MutableStateFlow(readList(FOLDERS_KEY))
    val folders: StateFlow<List<String>> = folderList.asStateFlow()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    override val accessRevision = MutableStateFlow(0)
    override val rememberedTransfer = MutableStateFlow(
        preferences.getString(TRANSFER_KEY)?.let { saved -> FolderTransfer.entries.firstOrNull { it.name == saved } },
    )

    override fun accessSummary(): UserText = when (val count = folderList.value.size) {
        0 -> UserText(UserMessage.FOLDERS_NONE)
        1 -> UserText(UserMessage.FOLDERS_ONE, Path.of(folderList.value.single()).name)
        else -> UserText(UserMessage.FOLDERS_MANY, count)
    }

    /** Reads the folders from disk again — when the window gains focus, for example. */
    override fun refreshAccess() {
        accessRevision.value++
        scope.launch { runCatching { syncCatalog() } }
    }

    suspend fun addFolder(folder: Path) {
        val real = withContext(Dispatchers.IO) { folder.toRealPath() }
        requireUser(real.isDirectory(), UserMessage.CHOOSE_FOLDER)
        val path = real.toString()
        if (path !in folderList.value) {
            folderList.value = folderList.value + path
            writeList(FOLDERS_KEY, folderList.value)
            accessRevision.value++
        }
        syncCatalog()
    }

    suspend fun removeFolder(folder: String) {
        folderList.value = folderList.value - folder
        writeList(FOLDERS_KEY, folderList.value)
        accessRevision.value++
        syncCatalog()
    }

    override suspend fun syncCatalog() = withContext(Dispatchers.IO) {
        refreshing {
            val favorites = readList(FAVORITES_KEY).toSet()
            val rows = mutableListOf<AssetEntity>()
            for (folder in folderList.value.map(Path::of).filter { it.isDirectory() }) {
                Files.walk(folder).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file -> entity(file, favorites)?.let(rows::add) }
                }
            }
            // A folder that disappeared (a disconnected drive) takes its photos off the grid; the recipes stay.
            replaceCatalog(rows)
        }
    }

    private fun entity(file: Path, favorites: Set<String>): AssetEntity? {
        val extension = file.extension.lowercase()
        val type = when (extension) {
            in IMAGE_EXTENSIONS -> AssetType.IMAGE
            in VIDEO_EXTENSIONS -> AssetType.VIDEO
            else -> return null
        }
        val stat = Files.readAttributes(file, BasicFileAttributes::class.java)
        val id = file.toUri().toString()
        val taken = if (type == AssetType.IMAGE) exifDate(file) else null
        val instant = taken ?: stat.lastModifiedTime().toInstant()
        val (width, height) = if (type == AssetType.IMAGE) dimensions(file) else null to null
        return AssetEntity(
            libraryKey = DEVICE_LIBRARY_ID,
            id = id,
            checksum = "local:${stat.size()}:${stat.lastModifiedTime().toMillis() / 1000}",
            originalFileName = file.name,
            fileCreatedAt = ISO_INSTANT.format(instant),
            localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime().toString(),
            width = width,
            height = height,
            isFavorite = id in favorites,
            isEdited = false,
            hasLocalRecipe = false,
            type = type.name,
            mimeType = MIME_TYPES[extension],
            folderId = file.parent.toString(),
            folderName = file.parent.name,
            sizeBytes = stat.size(),
        )
    }

    override suspend fun assetDetail(assetId: String): ImmichAssetDetail = withContext(Dispatchers.IO) {
        val row = database.assetDao().asset(DEVICE_LIBRARY_ID, assetId) ?: throw UserMessageException(UserMessage.FOLDER_FILE_UNAVAILABLE)
        val file = fileOf(assetId)
        val exif = runCatching {
            val metadata = ImageMetadataReader.readMetadata(file.toFile())
            val camera = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val shot = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val place = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation?.takeUnless { it.isZero }
            AssetExif(
                fNumber = shot?.getString(ExifSubIFDDirectory.TAG_FNUMBER)?.toFloatOrNull(),
                exposureTime = shot?.getString(ExifSubIFDDirectory.TAG_EXPOSURE_TIME),
                iso = shot?.getString(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)?.toIntOrNull(),
                make = camera?.getString(ExifIFD0Directory.TAG_MAKE),
                model = camera?.getString(ExifIFD0Directory.TAG_MODEL),
                focalLength = shot?.getRational(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)?.toFloat(),
                exposureBias = shot?.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_BIAS)?.toFloat(),
                latitude = place?.latitude,
                longitude = place?.longitude,
            )
        }.getOrNull() ?: AssetExif()
        ImmichAssetDetail(
            asset = row.toDomain(),
            exif = exif.copy(
                fileSizeBytes = row.sizeBytes,
                imageWidth = exif.imageWidth ?: row.width?.toInt(),
                imageHeight = exif.imageHeight ?: row.height?.toInt(),
            ),
            folder = file.parent?.toString(),
        )
    }

    override fun canRename(assetId: String): Boolean = true

    /** The file takes the new name in its folder; the photo's id is its path, so it changes with it. */
    override suspend fun renameAsset(assetId: String, name: String): String {
        val file = fileOf(assetId)
        val target = file.resolveSibling(requireNotNull(renamedFile(file.name, name)) { "Nothing of \"$name\" makes a file name" })
        if (target.name == file.name) return assetId
        withContext(Dispatchers.IO) {
            if (target.exists() && !Files.isSameFile(target, file)) throw UserMessageException(UserMessage.FILE_NAME_TAKEN)
            // Here a target that exists is this file: Windows takes a change of case alone as moving it onto itself.
            if (target.exists()) {
                val step = file.resolveSibling(".imago-rename-${System.nanoTime()}")
                Files.move(file, step)
                Files.move(step, target)
            } else {
                Files.move(file, target)
            }
        }
        val renamed = target.toUri().toString()
        followMoves(mapOf(assetId to renamed))
        val favorites = readList(FAVORITES_KEY).toSet()
        replaceRows(listOf(assetId, renamed), listOfNotNull(withContext(Dispatchers.IO) { entity(target, favorites) }))
        return renamed
    }

    override suspend fun setFavorite(assetId: String, isFavorite: Boolean) {
        val favorites = readList(FAVORITES_KEY).toMutableSet()
        if (isFavorite) favorites += assetId else favorites -= assetId
        writeList(FAVORITES_KEY, favorites.toList())
        syncCatalog()
    }

    /** Goes to the Recycle Bin, where it can be recovered — the app never deletes for good. */
    override suspend fun deleteAsset(assetId: String) {
        withContext(Dispatchers.IO) {
            requireUser(trash(fileOf(assetId).toFile()), UserMessage.TRASH_FAILED)
        }
        syncCatalog()
    }

    override suspend fun setFavorites(assetIds: List<String>, isFavorite: Boolean) {
        val favorites = readList(FAVORITES_KEY).toMutableSet()
        if (isFavorite) favorites += assetIds else favorites -= assetIds.toSet()
        writeList(FAVORITES_KEY, favorites.toList())
        syncCatalog()
    }

    /** The catalogue is read again once at the end; one that stopped halfway still shows what went. */
    override suspend fun deleteAssets(assetIds: List<String>) {
        try {
            withContext(Dispatchers.IO) {
                assetIds.forEach { requireUser(trash(fileOf(it).toFile()), UserMessage.TRASH_FAILED) }
            }
        } finally {
            syncCatalog()
        }
    }

    override suspend fun readLocation(assetId: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        ImageMetadataReader.readMetadata(fileOf(assetId).toFile()).getFirstDirectoryOfType(GpsDirectory::class.java)
            ?.geoLocation?.takeUnless { it.isZero }?.let { it.latitude to it.longitude }
    }

    override fun rememberTransfer(transfer: FolderTransfer?) {
        requireUser(preferences.write(mapOf(TRANSFER_KEY to transfer?.name)), UserMessage.CONFIGURATION_NOT_SAVED)
        rememberedTransfer.value = transfer
    }

    override suspend fun albums(): List<ImmichAlbum> = super.albums().map { album ->
        val folder = Path.of(album.id)
        val inside = rootOf(folder) != null
        album.copy(isFolder = true, canEditContent = inside, isOwned = inside && !isChosen(folder))
    }

    override suspend fun albumPlaces(assetIds: List<String>): List<AlbumPlace> {
        val roots = roots()
        if (roots.size < 2) return emptyList()
        val holding = assetIds.firstOrNull()?.let { runCatching { rootOf(fileOf(it)) }.getOrNull() }
        return roots.map { AlbumPlace(it.toString(), it.name, it.toString(), suggested = it == holding) }
    }

    override suspend fun fileIntoAlbum(albumId: String, assetIds: List<String>, transfer: FolderTransfer): AlbumAddition {
        val folder = Path.of(albumId)
        requireUser(rootOf(folder) != null && folder.isDirectory(), UserMessage.FOLDER_FILE_UNAVAILABLE)
        return transferTo(folder, assetIds, transfer)
    }

    /** A subfolder of [place], or of the chosen folder the photos are in. */
    override suspend fun createFolderAlbum(name: String, assetIds: List<String>, transfer: FolderTransfer, place: String?): ImmichAlbum {
        val folderName = requireNotNull(albumFolderName(name)) { "Nothing of \"$name\" makes a folder name" }
        val roots = roots()
        val parent = place?.let(Path::of)?.takeIf { it in roots }
            ?: assetIds.firstNotNullOfOrNull { id -> runCatching { rootOf(fileOf(id)) }.getOrNull() }
            ?: roots.firstOrNull()
            ?: throw UserMessageException(UserMessage.FOLDERS_NONE)
        val target = withContext(Dispatchers.IO) { Files.createDirectories(parent.resolve(folderName)).toRealPath() }
        val result = transferTo(target, assetIds, transfer)
        if (result.added + result.alreadyThere == 0) {
            withContext(Dispatchers.IO) { if (isEmpty(target)) Files.delete(target) }
            error("No photo reached the new album")
        }
        return albums().first { Path.of(it.id) == target }
    }

    /**
     * The folder takes the new name, with everything in it. When another folder beside it already
     * has that name, the photos join that one, as on the phone.
     */
    override suspend fun renameAlbum(albumId: String, name: String): String {
        val folder = ownedFolder(albumId)
        val folderName = requireNotNull(albumFolderName(name)) { "Nothing of \"$name\" makes a folder name" }
        val target = folder.resolveSibling(folderName)
        if (target.name == folder.name) return albumId
        val other = withContext(Dispatchers.IO) { target.exists() && !Files.isSameFile(target, folder) }
        if (other) {
            val joined = withContext(Dispatchers.IO) { target.toRealPath() }
            val result = transferTo(joined, idsIn(albumId), FolderTransfer.MOVE)
            check(result.failed == 0) { "${result.failed} files could not be moved to $joined" }
            withContext(Dispatchers.IO) { if (isEmpty(folder)) Files.delete(folder) }
            return joined.toString()
        }
        val moves = withContext(Dispatchers.IO) {
            val files = Files.walk(folder).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
            try {
                // Here a target that exists is this folder: Windows takes a change of case alone as
                // moving the folder onto itself, which does nothing.
                if (target.exists()) {
                    val step = folder.resolveSibling(".imago-rename-${System.nanoTime()}")
                    Files.move(folder, step)
                    Files.move(step, target)
                } else {
                    Files.move(folder, target)
                }
            } catch (error: IOException) {
                throw UserMessageException(UserMessage.FOLDER_RENAME_FAILED, cause = error)
            }
            files.associate { it.toUri().toString() to target.resolve(folder.relativize(it)).toUri().toString() }
        }
        followMoves(moves)
        syncCatalog()
        return target.toString()
    }

    /** The photos and videos go to the Recycle Bin; the folder too, when nothing else is left in it. */
    override suspend fun deleteAlbum(albumId: String) {
        val folder = ownedFolder(albumId)
        deleteAssets(idsIn(albumId))
        withContext(Dispatchers.IO) { if (folder.isDirectory() && isEmpty(folder)) trash(folder.toFile()) }
    }

    private fun roots(): List<Path> = folderList.value.map(Path::of)

    /** The chosen folder [path] is in; the innermost, when one chosen folder is inside another. */
    private fun rootOf(path: Path): Path? = roots().filter { path.startsWith(it) }.maxByOrNull { it.nameCount }

    private fun isChosen(folder: Path) = roots().any { it == folder }

    private fun ownedFolder(albumId: String): Path {
        val folder = Path.of(albumId)
        requireUser(rootOf(folder) != null && !isChosen(folder) && folder.isDirectory(), UserMessage.FOLDER_FILE_UNAVAILABLE)
        return folder
    }

    private suspend fun idsIn(albumId: String): List<String> = database.assetDao().idsInFolder(DEVICE_LIBRARY_ID, albumId)

    private fun isEmpty(folder: Path) = Files.list(folder).use { !it.findAny().isPresent }

    /**
     * Moves or copies into [target], keeping the name unless one there already has it. One that
     * fails does not stop the others. Only these photos' rows change in the catalogue.
     */
    private suspend fun transferTo(target: Path, assetIds: List<String>, transfer: FolderTransfer): AlbumAddition {
        val rows = assetIds.mapNotNull { database.assetDao().asset(DEVICE_LIBRARY_ID, it) }
        val (already, pending) = rows.partition { row -> row.folderId?.let(Path::of) == target }
        if (pending.isEmpty()) return AlbumAddition(0, already.size, assetIds.size - rows.size)
        val moves = mutableMapOf<String, String>()
        val arrived = withContext(Dispatchers.IO) {
            pending.mapNotNull { row ->
                runCatching {
                    val source = fileOf(row.id)
                    val destination = freeName(target, source.name)
                    if (transfer == FolderTransfer.MOVE) {
                        Files.move(source, destination)
                        moves[row.id] = destination.toUri().toString()
                    } else {
                        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    destination
                }.getOrNull()
            }
        }
        followMoves(moves)
        val favorites = readList(FAVORITES_KEY).toSet()
        val newRows = withContext(Dispatchers.IO) { arrived.mapNotNull { entity(it, favorites) } }
        replaceRows(moves.keys + arrived.map { it.toUri().toString() }, newRows)
        return AlbumAddition(added = arrived.size, alreadyThere = already.size, failed = assetIds.size - already.size - arrived.size)
    }

    /** A moved file is a new id for the same photo: its recipe and its favourite go with it. */
    private suspend fun followMoves(moves: Map<String, String>) {
        if (moves.isEmpty()) return
        database.moveAssetRecords(DEVICE_LIBRARY_ID, moves)
        val favorites = readList(FAVORITES_KEY)
        if (favorites.any(moves::containsKey)) writeList(FAVORITES_KEY, favorites.map { moves[it] ?: it })
    }

    override suspend fun downloadOriginal(assetId: String, destination: File) = withContext(Dispatchers.IO) {
        val source = fileOf(assetId)
        requireUser(Files.isRegularFile(source), UserMessage.FOLDER_FILE_UNAVAILABLE)
        Files.copy(source, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    private fun readList(key: String): List<String> =
        preferences.getString(key)?.let { runCatching { json.decodeFromString(stringList, it) }.getOrNull() }.orEmpty()

    private fun writeList(key: String, values: List<String>) {
        requireUser(preferences.write(mapOf(key to json.encodeToString(stringList, values))), UserMessage.CONFIGURATION_NOT_SAVED)
    }

    companion object {
        private const val FOLDERS_KEY = "device_folders"
        private const val FAVORITES_KEY = "device_favorites"
        private const val TRANSFER_KEY = "folder_transfer"
        private val ISO_INSTANT = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "tif", "tiff", "bmp")
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm", "avi", "m4v")
        private val MIME_TYPES = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
            "heic" to "image/heic", "heif" to "image/heif", "tif" to "image/tiff", "tiff" to "image/tiff", "bmp" to "image/bmp",
            "mp4" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska", "webm" to "video/webm",
            "avi" to "video/x-msvideo", "m4v" to "video/x-m4v",
        )

        fun fileOf(assetId: String): Path = Path.of(URI(assetId))

        /** [name] in [folder], or "name (2).jpg" and on when it is taken, as Windows does. */
        internal fun freeName(folder: Path, name: String): Path {
            val first = folder.resolve(name)
            if (!first.exists()) return first
            val base = first.nameWithoutExtension
            val extension = first.extension.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
            return generateSequence(2) { it + 1 }.map { folder.resolve("$base ($it)$extension") }.first { !it.exists() }
        }

        private fun exifDate(file: Path): Instant? = runCatching {
            ImageMetadataReader.readMetadata(file.toFile()).getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                ?.getDateOriginal(java.util.TimeZone.getDefault())?.toInstant()
        }.getOrNull()

        /** Only the header: the reader gives the dimensions without decoding the image. */
        private fun dimensions(file: Path): Pair<Long?, Long?> = runCatching {
            ImageIO.createImageInputStream(file.toFile())?.use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
                try { reader.input = input; reader.getWidth(0).toLong() to reader.getHeight(0).toLong() } finally { reader.dispose() }
            }
        }.getOrNull() ?: (null to null)
    }
}
