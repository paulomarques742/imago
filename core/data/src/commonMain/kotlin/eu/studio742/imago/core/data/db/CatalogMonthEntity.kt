package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A timeline month already brought whole into the local catalogue.
 *
 * `assetCount` is what the server counted when the month was synced, and it decides whether going
 * back there is needed: while the `/timeline/buckets` count stays the same, the month is as it was
 * and nothing is requested. This is what makes the next sync cost one request instead of a hundred
 * and twenty.
 */
@Entity(tableName = "catalog_months", primaryKeys = ["libraryKey", "month"])
data class CatalogMonthEntity(
    val libraryKey: String,
    val month: String,
    val assetCount: Int,
    val syncedAt: String,
)

@Dao
interface CatalogMonthDao {
    @Query("SELECT * FROM catalog_months WHERE libraryKey = :libraryKey")
    suspend fun all(libraryKey: String): List<CatalogMonthEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(month: CatalogMonthEntity)

    @Query("DELETE FROM catalog_months WHERE libraryKey = :libraryKey")
    suspend fun clear(libraryKey: String)
}
