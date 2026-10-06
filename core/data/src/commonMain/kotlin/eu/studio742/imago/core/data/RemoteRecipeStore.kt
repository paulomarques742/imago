package eu.studio742.imago.core.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.RecipeConflictEntity
import eu.studio742.imago.core.data.db.RecipeEntity
import eu.studio742.imago.core.data.db.RemoteRecipeEntity
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import java.time.Instant

/**
 * The recipes that arrive from other devices, identified by content.
 *
 * A received recipe applies to every photo on this device with the same SHA-1 that is already
 * known. Those that find no photo stay in `remote_recipes`: Immich ones link up when the checksum
 * appears in the catalogue, and device ones light up the indicator on the candidates and only apply
 * when the photo is opened and the hash confirms it.
 */
class RemoteRecipeStore(
    private val database: ImmichRoomDatabase,
    private val media: MediaResolver,
    private val translator: ReferenceTranslator,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Saves a received recipe. [state] is already the backend's state (revision, author, nothing
     * waiting to be sent). A local row waiting to be sent with another base is not overwritten.
     */
    suspend fun apply(sha1: String, payload: JsonObject, state: SyncState, hints: JsonObject?) {
        val targets = targets(sha1)
        if (targets.isEmpty()) {
            if (state.deletedAt != null) database.remoteRecipeDao().delete(sha1)
            else database.remoteRecipeDao().upsert(
                RemoteRecipeEntity(sha1, payload.toString(), state.remoteRevision ?: 0, state.editedAt, state.editedByDevice, hints?.toString()),
            )
            return
        }
        database.remoteRecipeDao().delete(sha1)
        val recipe = decode(payload) ?: return
        for (target in targets) {
            val previous = database.recipeDao().getAny(target.libraryId, target.localId)
            if (previous != null && previous.sync.dirty && previous.sync.remoteRevision != state.remoteRevision) continue
            if (state.deletedAt != null) {
                database.recipeDao().delete(target.libraryId, target.localId)
                database.assetDao().clearHasLocalRecipe(target.libraryId, target.localId)
                continue
            }
            database.recipeDao().upsert(
                RecipeEntity(
                    libraryKey = target.libraryId,
                    assetId = target.localId,
                    recipeJson = localJson(recipe, target),
                    updatedAt = recipe.updatedAt,
                    contentSha1 = sha1,
                    hintsJson = hints?.toString() ?: previous?.hintsJson,
                    sync = state,
                ),
            )
            database.assetDao().markHasLocalRecipe(target.libraryId, target.localId)
        }
    }

    /** The version that lost a conflict, in the history of every photo with this content. */
    suspend fun addConflict(sha1: String, payload: JsonObject, editedAt: String, deviceName: String) {
        val recipe = decode(payload) ?: return
        for (target in targets(sha1)) {
            val recipeJson = localJson(recipe, target)
            if (database.recipeConflictDao().count(target.libraryId, target.localId, editedAt, recipeJson) > 0) continue
            database.recipeConflictDao().insert(RecipeConflictEntity(0, target.libraryId, target.localId, recipeJson, deviceName, editedAt))
        }
    }

    /**
     * Links the waiting recipes to what the catalogue already knows, without reading files: Immich's
     * checksum applies it right away; device hints only light up the "has recipe" indicator.
     */
    suspend fun matchWaiting() {
        for (waiting in database.remoteRecipeDao().all()) {
            if (media.knownBySha1(waiting.contentSha1).isNotEmpty()) {
                apply(waiting.contentSha1, parse(waiting.recipeJson) ?: continue, waiting.state(), waiting.hintsJson?.let(::parse))
                continue
            }
            val hints = waiting.hints() ?: continue
            for (candidate in media.candidates(hints)) {
                if (database.candidateRejectionDao().isRejected(waiting.contentSha1, candidate.libraryId, candidate.localId)) continue
                database.assetDao().markHasLocalRecipe(candidate.libraryId, candidate.localId)
            }
        }
    }

    /**
     * When opening a photo without a recipe: if there is one waiting that may be its own, confirms by
     * the hash and applies it. Disproved candidates lose the indicator. `true` if it applied.
     */
    suspend fun materializeFor(assetId: String): Boolean {
        val ref = runCatching { AssetReference.parse(assetId) }.getOrNull() ?: return false
        if (ref.isRemote || database.recipeDao().getAny(ref.libraryId, ref.localId) != null) return false
        val waiting = database.remoteRecipeDao().all()
        if (waiting.isEmpty()) return false
        val asset = database.assetDao().asset(ref.libraryId, ref.localId)
        val plausible = if (ref.libraryId == DEVICE_LIBRARY_ID) {
            waiting.filter { recipe -> recipe.hints()?.let { hints -> asset != null && asset.matches(hints) } == true }
        } else waiting
        if (plausible.isEmpty()) return false

        // Read the file (or ask Immich) only when there really is a recipe that may be its own.
        val sha1 = media.sha1(assetId) ?: return false
        val match = plausible.firstOrNull { it.contentSha1 == sha1 }
        if (ref.libraryId == DEVICE_LIBRARY_ID) {
            for (rejected in plausible.filter { it.contentSha1 != sha1 }) {
                database.candidateRejectionDao().insert(
                    eu.studio742.imago.core.data.db.CandidateRejectionEntity(rejected.contentSha1, ref.libraryId, ref.localId, Instant.now().toString()),
                )
            }
        }
        if (match == null) {
            if (database.recipeDao().get(ref.libraryId, ref.localId) == null) database.assetDao().clearHasLocalRecipe(ref.libraryId, ref.localId)
            return false
        }
        database.withTransaction {
            apply(match.contentSha1, parse(match.recipeJson) ?: return@withTransaction, match.state(), match.hintsJson?.let(::parse))
        }
        return database.recipeDao().get(ref.libraryId, ref.localId) != null
    }

    /** The photos on this device with this content: the known ones and those that already have its recipe. */
    private suspend fun targets(sha1: String): List<AssetReference> =
        (media.knownBySha1(sha1) + database.recipeDao().bySha1(sha1).map { AssetReference(it.libraryKey, it.assetId) }).distinct()

    private suspend fun decode(payload: JsonObject): EditRecipe? =
        runCatching { json.decodeFromString<EditRecipe>(translator.incoming(payload).toString()) }.getOrNull()

    /**
     * The recipe in the exact form the repository saves it, pointed at this photo: saving again
     * without changes must not look like an edit, and the editor compares the catalogue's checksum.
     */
    private suspend fun localJson(recipe: EditRecipe, target: AssetReference): String = json.encodeToString(
        recipe.copy(assetId = target.encode(), originalChecksum = media.catalogChecksum(target).orEmpty()),
    )

    private fun parse(raw: String): JsonObject? = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull()

    private fun RemoteRecipeEntity.state() = SyncState(remoteRevision = revision, editedAt = editedAt, editedByDevice = editedByDevice)

    private fun RemoteRecipeEntity.hints(): MediaHints? = hintsJson?.let {
        runCatching { json.decodeFromString<MediaHints>(it) }.getOrNull()
    }

    private fun eu.studio742.imago.core.data.db.AssetEntity.matches(hints: MediaHints): Boolean =
        (hints.size == null || sizeBytes == null || hints.size == sizeBytes) &&
            (originalFileName.equals(hints.fileName, ignoreCase = true) || MediaResolver.sameMoment(fileCreatedAt, hints.takenAt))
}
