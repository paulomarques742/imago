package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.AssetReference
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.RecipeEntity
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RecipeConflictVersion
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRecipeRepository @Inject constructor(
    private val database: ImmichRoomDatabase,
    private val configuration: ConfigurationRepository,
    private val hashes: ContentHashRepository,
) : RecipeRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val media = MediaResolver(database, hashes)
    private val remote = RemoteRecipeStore(database, media, ReferenceTranslator(database, media))

    override suspend fun get(assetId: String): EditRecipe? {
        val ref = AssetReference.parse(assetId)
        if (ref.isRemote) return null
        // Without a recipe here, there may be one from another device waiting for this photo.
        val entity = database.recipeDao().get(ref.libraryId, ref.localId)
            ?: (if (remote.materializeFor(assetId)) database.recipeDao().get(ref.libraryId, ref.localId) else null)
            ?: return null
        return json.decodeFromString<EditRecipe>(entity.recipeJson).copy(assetId = assetId)
    }

    override fun conflicts(assetId: String): Flow<List<RecipeConflictVersion>> {
        val ref = runCatching { AssetReference.parse(assetId) }.getOrNull() ?: return flowOf(emptyList())
        return database.recipeConflictDao().observe(ref.libraryId, ref.localId).map { rows ->
            rows.mapNotNull { row ->
                runCatching { json.decodeFromString<EditRecipe>(row.recipeJson) }.getOrNull()
                    ?.let { RecipeConflictVersion(row.id, it.copy(assetId = assetId), row.deviceName, row.editedAt) }
            }
        }
    }

    /**
     * Saves the recipe and marks it as waiting to be sent.
     *
     * Saving the same recipe again — the editor does it on exit — does not count as an edit: giving
     * it a new `editedAt` would make it win over a real edit made on another device.
     */
    override suspend fun save(recipe: EditRecipe) {
        val ref = AssetReference.parse(recipe.assetId)
        val recipeJson = json.encodeToString(recipe)
        val sha1 = hashes.cached(recipe.assetId)
        val hints = if (ref.libraryId == DEVICE_LIBRARY_ID) {
            database.assetDao().asset(ref.libraryId, ref.localId)?.hints()?.let { json.encodeToString(it) }
        } else null
        database.withTransaction {
            val previous = database.recipeDao().getAny(ref.libraryId, ref.localId)
            if (previous != null && previous.sync.deletedAt == null && previous.recipeJson == recipeJson) return@withTransaction
            database.recipeDao().upsert(RecipeEntity(
                libraryKey = ref.libraryId,
                assetId = ref.localId,
                recipeJson = recipeJson,
                updatedAt = recipe.updatedAt,
                contentSha1 = sha1 ?: previous?.contentSha1,
                hintsJson = hints ?: previous?.hintsJson,
                sync = (previous?.sync ?: SyncState()).edited(Instant.now().toString()),
            ))
        }
        database.assetDao().markHasLocalRecipe(ref.libraryId, ref.localId)
        if (sha1 == null) hashes.request(recipe.assetId)
    }
}
