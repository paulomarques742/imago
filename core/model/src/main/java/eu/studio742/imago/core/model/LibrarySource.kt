package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Base64

const val DEVICE_LIBRARY_ID = "device"

/** Files another app opened with IMAGO that are not in any library: each one's id is its `Uri`. */
const val OPENED_LIBRARY_ID = "opened"
const val GLOBAL_LIBRARY_ID = "application"

/**
 * The prefix of libraries that do not exist on this device: a reference received from another
 * device that has not found the photo here yet is kept as it came, with the remote library after
 * this prefix. No local source uses it, so the reference shows as unavailable and is never mistaken
 * for a local photo.
 */
const val REMOTE_LIBRARY_PREFIX = "remote:"

/**
 * A library configured on this device.
 *
 * An Immich library can have several addresses — the home network one and the external one, for
 * example — in the order the user prefers them. The library's identity is the account ([userId]),
 * never the address. [activeUrl] is the address that answered last time; it is state of the moment
 * and is not saved with the list.
 */
@Serializable
data class LibrarySource(
    val id: String,
    val name: String,
    val serverUrls: List<String> = emptyList(),
    val userId: String? = null,
    val apiKey: String? = null,
    @Transient val activeUrl: String? = null,
) {
    /** The address in use: the active one, if it still belongs to the list, or the first. */
    val serverUrl: String? get() = activeUrl?.takeIf { it in serverUrls } ?: serverUrls.firstOrNull()
    val isDevice: Boolean get() = id == DEVICE_LIBRARY_ID
    val isConnected: Boolean get() = isDevice || !apiKey.isNullOrBlank()
    fun connection(): ImmichConnection = ImmichConnection(
        checkNotNull(serverUrl), checkNotNull(apiKey) { "Reconnect this library in Settings." }, id,
    )
}

/** An opaque, source-qualified ID travels through UI, recipes and persisted media references. */
@Serializable
data class AssetReference(val libraryId: String, val localId: String) {
    fun encode(): String = "imago:${encodePart(libraryId)}:${encodePart(localId)}"

    /** A reference from another device that has not resolved here yet. */
    val isRemote: Boolean get() = libraryId.startsWith(REMOTE_LIBRARY_PREFIX)

    /** The backend library of an unresolved reference; null for local ones. */
    val remoteLibraryId: String? get() = libraryId.removePrefix(REMOTE_LIBRARY_PREFIX).takeIf { isRemote }

    companion object {
        fun parse(value: String): AssetReference {
            require(value.startsWith("imago:")) { "The content has no source library." }
            val parts = value.split(':')
            require(parts.size == 3)
            return AssetReference(decodePart(parts[1]), decodePart(parts[2]))
        }
        fun remote(remoteLibraryId: String, providerAssetId: String) =
            AssetReference(REMOTE_LIBRARY_PREFIX + remoteLibraryId, providerAssetId)

        fun qualify(libraryId: String, value: String): String =
            if (value.startsWith("imago:")) value else AssetReference(libraryId, value).encode()
        private fun encodePart(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decodePart(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }
}

/**
 * How a library is named in a message the data layer raises. The libraries the app names, and not
 * the person, have no saved name; the start of the id is what still tells them apart there.
 */
fun LibrarySource.messageName(): String = name.ifBlank { id.take(8) }
