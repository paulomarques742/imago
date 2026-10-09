package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A photo of a stack as the unified library shows it, for the phone and the server [serverKey].
 *
 * In the unified library a photo on both sides shows once, as the phone's copy. A stack of the phone
 * and a stack of the server that share photos are one stack there: this is that stack, worked out
 * from the two whenever either changes, and read by the unified timeline's queries.
 *
 * [libraryKey] and [assetId] are the row the timeline shows — the phone's copy when there is one;
 * [deviceAssetId] and [serverAssetId] the photo on each side, so that a change goes to both.
 */
@Entity(tableName = "unified_stacks", primaryKeys = ["serverKey", "libraryKey", "assetId"])
data class UnifiedStackEntity(
    val serverKey: String,
    val libraryKey: String,
    val assetId: String,
    val groupId: String,
    val isCover: Boolean,
    val groupSize: Int,
    val deviceAssetId: String?,
    val serverAssetId: String?,
)

@Dao
interface UnifiedStackDao {
    @Query("DELETE FROM unified_stacks WHERE serverKey = :serverKey")
    suspend fun clear(serverKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<UnifiedStackEntity>)

    @Query("SELECT * FROM unified_stacks WHERE serverKey = :serverKey AND libraryKey = :libraryKey AND assetId = :assetId")
    suspend fun of(serverKey: String, libraryKey: String, assetId: String): UnifiedStackEntity?

    @Query("SELECT * FROM unified_stacks WHERE serverKey = :serverKey AND groupId = :groupId ORDER BY isCover DESC")
    suspend fun group(serverKey: String, groupId: String): List<UnifiedStackEntity>
}
