package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE libraryKey = :libraryKey AND assetId = :assetId AND deletedAt IS NULL LIMIT 1")
    suspend fun get(libraryKey: String, assetId: String): RecipeEntity?

    /** Deleted ones too: this is where the sync state to preserve is read from. */
    @Query("SELECT * FROM recipes WHERE libraryKey = :libraryKey AND assetId = :assetId LIMIT 1")
    suspend fun getAny(libraryKey: String, assetId: String): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE libraryKey = :libraryKey AND deletedAt IS NULL")
    suspend fun list(libraryKey: String): List<RecipeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE libraryKey = :libraryKey AND assetId = :assetId")
    suspend fun delete(libraryKey: String, assetId: String)

    /** Every recipe for this content, deleted ones included. */
    @Query("SELECT * FROM recipes WHERE contentSha1 = :sha1")
    suspend fun bySha1(sha1: String): List<RecipeEntity>

    /** Waiting to be sent, and only those that already know the SHA-1: without it there is no key in the backend. */
    @Query("SELECT * FROM recipes WHERE dirty = 1 AND contentSha1 IS NOT NULL ORDER BY editedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<RecipeEntity>

    @Query("SELECT COUNT(*) FROM recipes WHERE dirty = 1 AND contentSha1 IS NOT NULL")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM recipes WHERE dirty = 1 AND contentSha1 IS NOT NULL")
    fun observePendingCount(): Flow<Int>

    /** The recipes still waiting for the photo's hash. */
    @Query("SELECT * FROM recipes WHERE contentSha1 IS NULL AND deletedAt IS NULL")
    suspend fun withoutSha1(): List<RecipeEntity>

    /** Saves the hash computed after the recipe, without counting it as edited again. */
    @Query("UPDATE recipes SET contentSha1 = :sha1 WHERE libraryKey = :libraryKey AND assetId = :assetId")
    suspend fun setContentSha1(libraryKey: String, assetId: String, sha1: String)
}
