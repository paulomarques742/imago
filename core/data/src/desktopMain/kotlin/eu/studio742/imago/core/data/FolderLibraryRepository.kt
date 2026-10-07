package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.UserText
import eu.studio742.imago.core.model.requireUser
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
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
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAssetDetail
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * This computer's library: the folders the user chose.
 *
 * It is the counterpart of the Android gallery. Each file is identified by its `file:` URI — the
 * same role as MediaStore's `content://`, and what the image loader opens directly. The folders and
 * the favourites stay in the configuration: the file system has no favourites, and the catalogue is
 * rebuilt from disk on every sync.
 */
class FolderLibraryRepository(
    database: ImmichRoomDatabase,
    private val preferences: SecurePreferences,
) : LocalCatalogLibrary(database) {
    private val json = Json
    private val stringList = ListSerializer(String.serializer())
    private val folderList = MutableStateFlow(readList(FOLDERS_KEY))
    val folders: StateFlow<List<String>> = folderList.asStateFlow()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    override val accessRevision = MutableStateFlow(0)

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
        val asset = (database.assetDao().asset(DEVICE_LIBRARY_ID, assetId) ?: throw UserMessageException(UserMessage.FOLDER_FILE_UNAVAILABLE)).toDomain()
        val exif = runCatching {
            val metadata = ImageMetadataReader.readMetadata(fileOf(assetId).toFile())
            val camera = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val shot = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            AssetExif(
                fNumber = shot?.getString(ExifSubIFDDirectory.TAG_FNUMBER)?.toFloatOrNull(),
                exposureTime = shot?.getString(ExifSubIFDDirectory.TAG_EXPOSURE_TIME),
                iso = shot?.getString(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)?.toIntOrNull(),
                make = camera?.getString(ExifIFD0Directory.TAG_MAKE),
                model = camera?.getString(ExifIFD0Directory.TAG_MODEL),
            )
        }.getOrNull() ?: AssetExif()
        ImmichAssetDetail(asset, exif)
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
            requireUser(java.awt.Desktop.getDesktop().moveToTrash(fileOf(assetId).toFile()), UserMessage.TRASH_FAILED)
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
                assetIds.forEach { requireUser(java.awt.Desktop.getDesktop().moveToTrash(fileOf(it).toFile()), UserMessage.TRASH_FAILED) }
            }
        } finally {
            syncCatalog()
        }
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
