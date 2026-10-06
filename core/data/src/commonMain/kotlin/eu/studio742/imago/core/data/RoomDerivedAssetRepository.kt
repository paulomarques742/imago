package eu.studio742.imago.core.data

import kotlinx.serialization.json.Json
import eu.studio742.imago.core.data.db.DerivedAssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.EditRecipe
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDerivedAssetRepository @Inject constructor(
    private val database: ImmichRoomDatabase,
    private val configuration: ConfigurationRepository,
) : DerivedAssetRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var backfilledLibraryKey: String? = null

    override suspend fun record(originalAssetId: String, derivedAssetId: String) {
        // If the server deduplicated against the original itself, recording it here would hide it from the library.
        if (derivedAssetId == originalAssetId) return
        val ref = eu.studio742.imago.core.model.AssetReference.parse(originalAssetId)
        val derived = eu.studio742.imago.core.model.AssetReference.parse(derivedAssetId)
        require(ref.libraryId == derived.libraryId)
        database.withTransaction {
            val dao = database.derivedAssetDao()
            val previous = dao.getAny(ref.libraryId, derived.localId)
            if (previous != null && previous.sync.deletedAt == null && previous.originalAssetId == ref.localId) return@withTransaction
            val now = Instant.now().toString()
            dao.upsert(
                DerivedAssetEntity(
                    libraryKey = ref.libraryId,
                    derivedAssetId = derived.localId,
                    originalAssetId = ref.localId,
                    createdAt = previous?.createdAt ?: now,
                    sync = (previous?.sync ?: SyncState()).edited(now),
                ),
            )
        }
    }

    override suspend fun derivedIds(): Set<String> {
        val libraryKey = libraryKey()
        backfillFromRecipes(libraryKey)
        return database.derivedAssetDao().ids(libraryKey).toSet()
    }

    /**
     * The exports made before this table existed are only recorded inside the recipe's JSON, in
     * `derivedAssetId`. It moves them into the table once per process — the insert is idempotent, so
     * there is no need to persist that it already ran.
     */
    private suspend fun backfillFromRecipes(libraryKey: String) {
        if (backfilledLibraryKey == libraryKey) return
        val recovered = database.recipeDao().list(libraryKey).mapNotNull { entity ->
            val recipe = runCatching { json.decodeFromString<EditRecipe>(entity.recipeJson) }.getOrNull()
            val derivedAssetId = recipe?.derivedAssetId?.takeIf { it.isNotBlank() && it != entity.assetId }
            derivedAssetId?.let {
                DerivedAssetEntity(
                    libraryKey = libraryKey,
                    derivedAssetId = it,
                    originalAssetId = entity.assetId,
                    createdAt = entity.updatedAt,
                    sync = SyncState().edited(entity.updatedAt),
                )
            }
        }
        if (recovered.isNotEmpty()) database.derivedAssetDao().insertMissing(recovered)
        backfilledLibraryKey = libraryKey
    }

    private fun libraryKey(): String =
        checkNotNull(configuration.currentConnection()).let { it.libraryId ?: libraryKeyOf(it.serverUrl) }
}
