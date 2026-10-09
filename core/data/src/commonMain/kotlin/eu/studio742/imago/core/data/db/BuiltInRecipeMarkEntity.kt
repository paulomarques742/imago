package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The person's heart and last use on one of the app's presets. The preset is code, so only this
 * row syncs; a preset that a later version drops leaves its row behind, unseen and harmless.
 */
@Entity(tableName = "built_in_recipe_marks")
data class BuiltInRecipeMarkEntity(
    @PrimaryKey val id: String,
    val isFavorite: Boolean,
    val usedAt: String?,
    val updatedAt: String,
    @Embedded val sync: SyncState = SyncState(),
)

@Dao
interface BuiltInRecipeMarkDao {
    @Query("SELECT * FROM built_in_recipe_marks WHERE deletedAt IS NULL")
    suspend fun list(): List<BuiltInRecipeMarkEntity>

    @Query("SELECT * FROM built_in_recipe_marks WHERE id = :id")
    suspend fun getAny(id: String): BuiltInRecipeMarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mark: BuiltInRecipeMarkEntity)

    @Query("DELETE FROM built_in_recipe_marks WHERE id = :id")
    suspend fun delete(id: String)

    /** What is waiting to be sent, from the oldest to the most recent. */
    @Query("SELECT * FROM built_in_recipe_marks WHERE dirty = 1 ORDER BY editedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<BuiltInRecipeMarkEntity>

    @Query("SELECT COUNT(*) FROM built_in_recipe_marks WHERE dirty = 1")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM built_in_recipe_marks WHERE dirty = 1")
    fun observePendingCount(): Flow<Int>
}
