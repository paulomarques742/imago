package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRecipeDao {
    @Query(
        """
        SELECT * FROM saved_recipes
        WHERE libraryKey = :libraryKey AND deletedAt IS NULL
        ORDER BY collection COLLATE NOCASE, name COLLATE NOCASE
        """,
    )
    suspend fun list(libraryKey: String): List<SavedRecipeEntity>

    @Query("SELECT * FROM saved_recipes WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun getAny(libraryKey: String, id: String): SavedRecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: SavedRecipeEntity)

    @Query("DELETE FROM saved_recipes WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun delete(libraryKey: String, id: String)

    /** What is waiting to be sent, from the oldest to the most recent. */
    @Query("SELECT * FROM saved_recipes WHERE dirty = 1 ORDER BY editedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<SavedRecipeEntity>

    @Query("SELECT COUNT(*) FROM saved_recipes WHERE dirty = 1")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM saved_recipes WHERE dirty = 1")
    fun observePendingCount(): Flow<Int>
}
