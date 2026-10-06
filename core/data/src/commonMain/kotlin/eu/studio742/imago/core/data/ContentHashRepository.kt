package eu.studio742.imago.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ContentHashEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAssetDetail
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * The SHA-1 of a photo's original file — its identity outside this device.
 *
 * It never scans the library. An Immich photo already brings the hash in `checksum`; a photo on
 * the device is only read when someone needs it: when it gains a recipe, joins a composition or to
 * confirm a candidate. The result stays in `content_hashes` and is only read again if the file's
 * size or `date_modified` change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ContentHashRepository(
    private val database: ImmichRoomDatabase,
    private val openDeviceFile: (String) -> InputStream?,
    private val assetDetail: suspend (String) -> ImmichAssetDetail,
) {

    // One file at a time: reading whole videos in parallel only fought the interface for the disk.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pending = ConcurrentHashMap.newKeySet<AssetReference>()
    private val computedHashes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * The photos whose hash just became known. A project that went up without the SHA-1 of one of
     * them goes up again with it.
     */
    val computed: SharedFlow<String> = computedHashes.asSharedFlow()

    /** The hash already known and still valid, without reading files or going to the network. */
    suspend fun cached(assetId: String): String? {
        val ref = AssetReference.parse(assetId)
        val asset = database.assetDao().asset(ref.libraryId, ref.localId)
        if (ref.libraryId != DEVICE_LIBRARY_ID) {
            asset?.checksum?.let(::normalizeImmichChecksum)?.let { return it }
        }
        val stored = database.contentHashDao().get(ref.libraryId, ref.localId) ?: return null
        if (ref.libraryId != DEVICE_LIBRARY_ID) return stored.sha1
        val (size, modified) = asset?.let(::fileStamp) ?: return null
        return stored.sha1.takeIf { stored.size == size && stored.dateModified == modified }
    }

    /** This photo's hash, computed if needed. Null if the file is not accessible. */
    suspend fun sha1(assetId: String): String? = cached(assetId) ?: withContext(Dispatchers.IO) {
        val ref = AssetReference.parse(assetId)
        runCatching { if (ref.libraryId == DEVICE_LIBRARY_ID) hashDeviceFile(ref) else fetchImmichChecksum(assetId, ref) }
            .getOrNull()
            ?.also { sha1 ->
                database.recipeDao().setContentSha1(ref.libraryId, ref.localId, sha1)
                computedHashes.tryEmit(assetId)
            }
    }

    /** Asks for the hash in the background; whoever saves does not wait for the file to be read. */
    fun request(assetId: String) {
        val ref = runCatching { AssetReference.parse(assetId) }.getOrNull() ?: return
        if (!pending.add(ref)) return
        scope.launch {
            try { sha1(assetId) } finally { pending.remove(ref) }
        }
    }

    /** Asks for the hash of the device photos referred to in a project, template or brand kit. */
    fun requestDeviceMedia(json: JsonElement) {
        deviceReferences(json).forEach(::request)
    }

    private suspend fun hashDeviceFile(ref: AssetReference): String? {
        val asset = database.assetDao().asset(ref.libraryId, ref.localId) ?: return null
        val (size, modified) = fileStamp(asset) ?: return null
        val digest = MessageDigest.getInstance("SHA-1")
        val stream = openDeviceFile(ref.localId) ?: return null
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sha1 = digest.digest().toHex()
        database.contentHashDao().upsert(ContentHashEntity(ref.libraryId, ref.localId, sha1, size, modified))
        return sha1
    }

    /** The per-month answers have no `checksum`; the photo's detail does. */
    private suspend fun fetchImmichChecksum(assetId: String, ref: AssetReference): String? {
        val sha1 = normalizeImmichChecksum(assetDetail(assetId).asset.checksum) ?: return null
        database.contentHashDao().upsert(ContentHashEntity(ref.libraryId, ref.localId, sha1, null, null))
        return sha1
    }

    private companion object {
        const val DEFAULT_BUFFER = 256 * 1024
    }
}

/** The hints another device uses to find the same device photo without reading files. */
@Serializable
data class MediaHints(
    val fileName: String,
    val size: Long?,
    val takenAt: String,
    val width: Long?,
    val height: Long?,
)

internal fun AssetEntity.hints() = MediaHints(originalFileName, sizeBytes ?: fileStamp(this)?.first, fileCreatedAt, width, height)

/**
 * Immich's `checksum` is the SHA-1 in Base64; the identity uses it in lowercase hexadecimal. The
 * local pseudo-checksums (`local:<size>:<date>`) are not hashes and give null.
 */
fun normalizeImmichChecksum(checksum: String): String? {
    val value = checksum.trim()
    if (value.isEmpty() || value.startsWith("local:")) return null
    if (value.length == 40 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return value.lowercase()
    val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
    return bytes.takeIf { it.size == 20 }?.toHex()
}

/** Size and `date_modified` of a device photo, read from the catalogue. */
private fun fileStamp(asset: AssetEntity): Pair<Long, Long>? {
    val parts = asset.checksum.split(':')
    if (parts.size != 3 || parts[0] != "local") return null
    val size = asset.sizeBytes ?: parts[1].toLongOrNull() ?: return null
    return size to (parts[2].toLongOrNull() ?: return null)
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/** The references to device photos in the same `assetId` fields the migrations go through. */
internal fun deviceReferences(value: JsonElement): Set<String> = when (value) {
    is JsonArray -> value.flatMap(::deviceReferences).toSet()
    is JsonObject -> value.flatMap { (key, child) ->
        if (key == "assetId" && child is JsonPrimitive && child.isString) {
            listOfNotNull(child.content.takeIf {
                runCatching { AssetReference.parse(it).libraryId == DEVICE_LIBRARY_ID }.getOrDefault(false)
            })
        } else deviceReferences(child)
    }.toSet()
    else -> emptySet()
}
