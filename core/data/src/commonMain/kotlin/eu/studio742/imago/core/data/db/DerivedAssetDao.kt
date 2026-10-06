package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DerivedAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DerivedAssetEntity)

    /** Only adds: a record that already exists keeps the sync state it had. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(entities: List<DerivedAssetEntity>)

    @Query("SELECT * FROM derived_assets WHERE libraryKey = :libraryKey AND derivedAssetId = :derivedAssetId")
    suspend fun getAny(libraryKey: String, derivedAssetId: String): DerivedAssetEntity?

    @Query("SELECT derivedAssetId FROM derived_assets WHERE libraryKey = :libraryKey AND deletedAt IS NULL")
    suspend fun ids(libraryKey: String): List<String>

    @Query("DELETE FROM derived_assets WHERE libraryKey = :libraryKey AND derivedAssetId = :derivedAssetId")
    suspend fun delete(libraryKey: String, derivedAssetId: String)

    @Query("SELECT * FROM derived_assets WHERE dirty = 1 ORDER BY editedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<DerivedAssetEntity>

    @Query("SELECT COUNT(*) FROM derived_assets WHERE dirty = 1")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM derived_assets WHERE dirty = 1")
    fun observePendingCount(): Flow<Int>
}
