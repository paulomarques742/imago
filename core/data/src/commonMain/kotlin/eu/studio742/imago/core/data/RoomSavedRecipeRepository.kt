package eu.studio742.imago.core.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import eu.studio742.imago.core.data.db.BuiltInRecipeMarkEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.SavedRecipeEntity
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSavedRecipeRepository @Inject constructor(
    private val database: ImmichRoomDatabase,
    private val configuration: ConfigurationRepository,
) : SavedRecipeRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun list(): List<SavedRecipe> = database.savedRecipeDao().list(libraryKey()).map { entity ->
        SavedRecipe(
            id = entity.id,
            name = entity.name,
            collection = entity.collection,
            recipe = json.decodeFromString<EditRecipe>(entity.recipeJson),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            isFavorite = entity.isFavorite,
            usedAt = entity.usedAt,
        )
    }

    override suspend fun save(recipe: SavedRecipe) = database.withTransaction {
        val dao = database.savedRecipeDao()
        val previous = dao.getAny(libraryKey(), recipe.id)
        val entity = SavedRecipeEntity(
            libraryKey = libraryKey(),
            id = recipe.id,
            name = recipe.name,
            collection = recipe.collection,
            recipeJson = json.encodeToString(recipe.recipe),
            createdAt = recipe.createdAt,
            updatedAt = recipe.updatedAt,
            isFavorite = recipe.isFavorite,
            usedAt = recipe.usedAt,
        )
        if (previous != null && previous.sync.deletedAt == null && previous.copy(sync = SyncState()) == entity) return@withTransaction
        dao.upsert(entity.copy(sync = (previous?.sync ?: SyncState()).edited(Instant.now().toString())))
    }

    override suspend fun delete(id: String) = database.withTransaction {
        val dao = database.savedRecipeDao()
        val previous = dao.getAny(libraryKey(), id) ?: return@withTransaction
        // A preset that never reached the backend has nobody to tell it was deleted.
        if (previous.sync.remoteRevision == null) dao.delete(libraryKey(), id)
        else if (previous.sync.deletedAt == null) dao.upsert(previous.copy(sync = previous.sync.deleted(Instant.now().toString())))
    }

    override suspend fun builtInMarks(): List<BuiltInRecipeMark> = database.builtInRecipeMarkDao().list().map { entity ->
        BuiltInRecipeMark(id = entity.id, isFavorite = entity.isFavorite, usedAt = entity.usedAt)
    }

    override suspend fun saveBuiltInMark(mark: BuiltInRecipeMark) = database.withTransaction {
        val dao = database.builtInRecipeMarkDao()
        val previous = dao.getAny(mark.id)
        if (previous != null && previous.sync.deletedAt == null &&
            previous.isFavorite == mark.isFavorite && previous.usedAt == mark.usedAt
        ) return@withTransaction
        val now = Instant.now().toString()
        dao.upsert(
            BuiltInRecipeMarkEntity(
                id = mark.id,
                isFavorite = mark.isFavorite,
                usedAt = mark.usedAt,
                updatedAt = now,
                sync = (previous?.sync ?: SyncState()).edited(now),
            ),
        )
    }

    private fun libraryKey(): String =
        eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
}
