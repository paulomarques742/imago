package eu.studio742.imago.core.data

import eu.studio742.imago.core.data.db.CandidateRejectionEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/**
 * Finds on this device the photo another device is talking about.
 *
 * In order: the SHA-1 already known — Immich's `checksum` in the catalogue or a hash computed
 * before —, and only then the candidates from the hints, which are confirmed by reading the file. An
 * unconfirmed candidate never counts as found, and one that was disproved is recorded so it is not
 * read again.
 */
class MediaResolver(
    private val database: ImmichRoomDatabase,
    private val hashes: ContentHashRepository,
) {
    /** This photo's SHA-1 if already known, without reading files or going to the network. */
    suspend fun cachedSha1(assetId: String): String? = runCatching { hashes.cached(assetId) }.getOrNull()

    /** This photo's SHA-1, reading the file or asking Immich if needed. */
    suspend fun sha1(assetId: String): String? = runCatching { hashes.sha1(assetId) }.getOrNull()

    /** A device photo's hints; null for the others, which are recognised by their checksum. */
    suspend fun hints(assetId: String): MediaHints? {
        val ref = runCatching { AssetReference.parse(assetId) }.getOrNull() ?: return null
        if (ref.libraryId != DEVICE_LIBRARY_ID) return null
        return database.assetDao().asset(ref.libraryId, ref.localId)?.hints()
    }

    /** The catalogue's `checksum`, in the form the editor compares with the recipe. */
    suspend fun catalogChecksum(ref: AssetReference): String? =
        database.assetDao().asset(ref.libraryId, ref.localId)?.checksum

    /** The photos on this device with this content that are known without reading anything. */
    suspend fun knownBySha1(sha1: String): List<AssetReference> {
        val found = linkedSetOf<AssetReference>()
        for (row in database.contentHashDao().bySha1(sha1)) {
            val ref = AssetReference(row.libraryKey, row.assetId)
            // A device hash is only valid while the file stays the same.
            if (cachedSha1(ref.encode()) == sha1) found += ref
        }
        immichChecksumOf(sha1)?.let { checksum ->
            database.assetDao().byChecksum(checksum).forEach { found += AssetReference(it.libraryKey, it.id) }
        }
        return found.toList()
    }

    /** The device candidates for being this photo, from the hints alone (first step). */
    suspend fun candidates(hints: MediaHints): List<AssetReference> {
        val size = hints.size ?: return emptyList()
        return database.assetDao().withSize(DEVICE_LIBRARY_ID, size)
            .filter { it.originalFileName.equals(hints.fileName, ignoreCase = true) || sameMoment(it.fileCreatedAt, hints.takenAt) }
            .map { AssetReference(it.libraryKey, it.id) }
    }

    /** Reads the candidates until one has this SHA-1 (second step). */
    suspend fun confirm(sha1: String, hints: MediaHints): AssetReference? {
        for (candidate in candidates(hints)) {
            if (database.candidateRejectionDao().isRejected(sha1, candidate.libraryId, candidate.localId)) continue
            val actual = sha1(candidate.encode()) ?: continue
            if (actual == sha1) return candidate
            database.candidateRejectionDao().insert(
                CandidateRejectionEntity(sha1, candidate.libraryId, candidate.localId, Instant.now().toString()),
            )
        }
        return null
    }

    /** The photo with this content. Without [readFiles], only what is known without reading files. */
    suspend fun find(sha1: String?, hints: MediaHints?, readFiles: Boolean): AssetReference? {
        if (sha1 == null || !isSha1(sha1)) return null
        knownBySha1(sha1).firstOrNull()?.let { return it }
        return if (readFiles && hints != null) confirm(sha1, hints) else null
    }

    companion object {
        fun isSha1(value: String) = value.length == 40 && value.all { it in '0'..'9' || it in 'a'..'f' }

        /** The hexadecimal SHA-1 in the form Immich stores it: Base64. */
        fun immichChecksumOf(sha1: String): String? = if (!isSha1(sha1)) null else
            Base64.getEncoder().encodeToString(ByteArray(20) { sha1.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

        /**
         * The same date with two seconds of slack: each platform rounds the photo's date its own
         * way, and the candidate is confirmed by the hash anyway.
         */
        internal fun sameMoment(a: String, b: String): Boolean {
            val first = parseMoment(a) ?: return false
            val second = parseMoment(b) ?: return false
            return Duration.between(first, second).abs() <= Duration.ofSeconds(2)
        }

        fun parseMoment(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }
}
