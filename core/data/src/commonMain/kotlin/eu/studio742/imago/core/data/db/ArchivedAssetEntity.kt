package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A photo of this device the person archived. Android and the computer's folders have no archive of
 * their own, so this list is it: the catalogue rows are rewritten on every sync, and get their mark
 * back from here, as they get the recipe's.
 */
@Entity(tableName = "archived_assets", primaryKeys = ["libraryKey", "assetId"])
data class ArchivedAssetEntity(val libraryKey: String, val assetId: String)

@Dao
interface ArchivedAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun archive(assets: List<ArchivedAssetEntity>)

    @Query("DELETE FROM archived_assets WHERE libraryKey = :libraryKey AND assetId IN (:assetIds)")
    suspend fun unarchive(libraryKey: String, assetIds: List<String>)

    @Query("UPDATE OR REPLACE archived_assets SET assetId = :to WHERE libraryKey = :libraryKey AND assetId = :from")
    suspend fun moveAsset(libraryKey: String, from: String, to: String)
}
