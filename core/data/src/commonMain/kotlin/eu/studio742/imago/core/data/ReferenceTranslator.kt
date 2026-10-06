package eu.studio742.imago.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import java.util.Base64

/**
 * The boundary of media references.
 *
 * Inside the device, a photo is `imago:<libraryKey>:<assetId>`, and the local key never leaves it.
 * In the backend it is `imago-r:<remote library>:<id in the service>`, with the real SHA-1 in
 * place of the checksum and, for photos on the device, the hints for another device to recognise it.
 *
 * When receiving, a reference whose library is not linked on this device looks for the photo by its
 * content; if it does not find it, it is stored as it came (`AssetReference.remote`), and resolved
 * later with [resolveStored].
 */
class ReferenceTranslator(
    private val database: ImmichRoomDatabase,
    private val media: MediaResolver,
) {
    /** A photo from a library that has no counterpart in the account yet: the record cannot go up. */
    class UnlinkedLibraryException(val libraryKey: String) :
        Exception("There are photos from a library that is not linked to the account yet.")

    /** [missingHashes] are the photos that went up without a SHA-1, because it was not computed yet. */
    data class Outgoing(val payload: JsonElement, val missingHashes: Set<String>)

    suspend fun outgoing(value: JsonElement): Outgoing {
        val missing = linkedSetOf<String>()
        val translated = outgoing(value, missing)
        // No local key reaches the backend, wherever it comes from.
        check(!containsLocalReference(translated)) { "A local reference was left untranslated." }
        return Outgoing(translated, missing)
    }

    private suspend fun outgoing(value: JsonElement, missing: MutableSet<String>): JsonElement = when (value) {
        is JsonObject -> {
            val fields = LinkedHashMap<String, JsonElement>()
            for ((key, child) in value) {
                if (key.lowercase() in FORBIDDEN_FIELDS) throw SecurityException("The payload contains local configuration ($key).")
                fields[key] = if (isReferenceField(key) && child.isLocalReference()) {
                    JsonPrimitive(toWire((child as JsonPrimitive).content))
                } else {
                    outgoing(child, missing)
                }
            }
            val assetId = value.string("assetId")?.takeIf { it.startsWith(LOCAL_PREFIX) }
            if (assetId != null) {
                val ref = AssetReference.parse(assetId)
                val sha1 = if (ref.isRemote) {
                    CHECKSUM_FIELDS.firstNotNullOfOrNull { value.string(it) }?.takeIf(MediaResolver::isSha1)
                } else {
                    media.cachedSha1(assetId)
                }
                if (sha1 == null && !ref.isRemote) missing += assetId
                // The `local:` pseudo-checksum never goes up: either the real SHA-1, or empty until there is one.
                for (field in CHECKSUM_FIELDS) if (field in value) fields[field] = JsonPrimitive(sha1.orEmpty())
                if ("fileName" in value && ref.libraryId == DEVICE_LIBRARY_ID) {
                    media.hints(assetId)?.let { hints ->
                        hints.size?.let { fields["sizeBytes"] = JsonPrimitive(it) }
                        fields["takenAt"] = JsonPrimitive(hints.takenAt)
                    }
                }
            }
            JsonObject(fields)
        }
        is JsonArray -> JsonArray(value.map { outgoing(it, missing) })
        else -> value
    }

    private suspend fun toWire(id: String): String {
        val ref = AssetReference.parse(id)
        val remoteLibrary = ref.remoteLibraryId
            ?: database.libraryLinkDao().get(ref.libraryId)?.remoteLibraryId
            ?: throw UnlinkedLibraryException(ref.libraryId)
        return "$WIRE_PREFIX$remoteLibrary:${encode(ref.localId)}"
    }

    /** What came from the backend, with the references in this device's form. */
    suspend fun incoming(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> {
            val fields = LinkedHashMap<String, JsonElement>()
            for ((key, child) in value) {
                fields[key] = if (isReferenceField(key) && child.isWireReference()) {
                    JsonPrimitive(fromWire((child as JsonPrimitive).content, null, null))
                } else {
                    incoming(child)
                }
            }
            val wire = value.string("assetId")?.takeIf { it.startsWith(WIRE_PREFIX) }
            if (wire != null) {
                val sha1 = CHECKSUM_FIELDS.firstNotNullOfOrNull { value.string(it) }?.takeIf(MediaResolver::isSha1)
                val local = fromWire(wire, sha1, value.hints())
                fields["assetId"] = JsonPrimitive(local)
                localChecksum(local)?.let { checksum -> for (field in CHECKSUM_FIELDS) if (field in value) fields[field] = JsonPrimitive(checksum) }
            }
            JsonObject(fields)
        }
        is JsonArray -> JsonArray(value.map { incoming(it) })
        else -> value
    }

    private suspend fun fromWire(wire: String, sha1: String?, hints: MediaHints?): String {
        val (remoteLibrary, providerId) = parseWire(wire) ?: return wire
        database.libraryLinkDao().byRemote(remoteLibrary)?.let { return AssetReference(it.localKey, providerId).encode() }
        media.find(sha1, hints, readFiles = false)?.let { return it.encode() }
        return AssetReference.remote(remoteLibrary, providerId).encode()
    }

    /**
     * Tries again a stored record's unresolved references: the library may have been linked in the
     * meantime, the catalogue may have the photo, or a candidate may be confirmed by reading the file
     * ([readFiles]). Null if nothing changed.
     */
    suspend fun resolveStored(value: JsonElement, readFiles: Boolean): JsonElement? {
        var changed = false
        suspend fun visit(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> {
                val fields = LinkedHashMap<String, JsonElement>()
                for ((key, child) in element) fields[key] = visit(child)
                val ref = element.string("assetId")?.let { runCatching { AssetReference.parse(it) }.getOrNull() }
                val remoteLibrary = ref?.remoteLibraryId
                if (ref != null && remoteLibrary != null) {
                    val linked = database.libraryLinkDao().byRemote(remoteLibrary)?.let { AssetReference(it.localKey, ref.localId) }
                    val sha1 = CHECKSUM_FIELDS.firstNotNullOfOrNull { element.string(it) }?.takeIf(MediaResolver::isSha1)
                    val found = linked ?: media.find(sha1, element.hints(), readFiles)
                    if (found != null) {
                        changed = true
                        fields["assetId"] = JsonPrimitive(found.encode())
                        media.catalogChecksum(found)?.let { checksum ->
                            for (field in CHECKSUM_FIELDS) if (field in element) fields[field] = JsonPrimitive(checksum)
                        }
                    }
                }
                JsonObject(fields)
            }
            is JsonArray -> JsonArray(element.map { visit(it) })
            else -> element
        }
        val result = visit(value)
        return result.takeIf { changed }
    }

    private suspend fun localChecksum(reference: String): String? {
        val ref = runCatching { AssetReference.parse(reference) }.getOrNull() ?: return null
        return if (ref.isRemote) null else media.catalogChecksum(ref)
    }

    private fun JsonObject.hints(): MediaHints? {
        val fileName = string("fileName") ?: return null
        val size = (this["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: return null
        return MediaHints(
            fileName = fileName,
            size = size,
            takenAt = string("takenAt").orEmpty(),
            width = (this["width"] as? JsonPrimitive)?.longOrNull,
            height = (this["height"] as? JsonPrimitive)?.longOrNull,
        )
    }

    companion object {
        const val LOCAL_PREFIX = "imago:"
        const val WIRE_PREFIX = "imago-r:"

        /**
         * The start of any unresolved reference, as it is stored: the library starts with `remote`,
         * and this is how Room finds the records to try again.
         */
        val REMOTE_MARKER: String = LOCAL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString("remote".toByteArray())

        private val CHECKSUM_FIELDS = listOf("checksum", "originalChecksum")
        private val FORBIDDEN_FIELDS = setOf(
            "apikey", "api_key", "x-api-key", "serverurl", "serverurls", "activeurl", "authorization", "librarykey",
        )

        /** The fields that hold references: `assetId`, `derivedAssetId`, `thumbnailAssetId`… */
        private fun isReferenceField(key: String) = key.endsWith("ssetId")

        private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        fun parseWire(value: String): Pair<String, String>? {
            if (!value.startsWith(WIRE_PREFIX)) return null
            val parts = value.removePrefix(WIRE_PREFIX).split(':')
            if (parts.size != 2 || parts[0].isBlank()) return null
            val provider = runCatching { String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
            return parts[0] to provider
        }

        private fun JsonElement.isLocalReference() =
            this is JsonPrimitive && isString && content.startsWith(LOCAL_PREFIX) && runCatching { AssetReference.parse(content) }.isSuccess

        private fun JsonElement.isWireReference() = this is JsonPrimitive && isString && content.startsWith(WIRE_PREFIX)

        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

        /** A local reference anywhere in the payload, whatever the field. */
        fun containsLocalReference(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.values.any(::containsLocalReference)
            is JsonArray -> value.any(::containsLocalReference)
            is JsonPrimitive -> value.isLocalReference()
        }
    }
}
