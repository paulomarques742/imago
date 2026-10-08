package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A photo of an Immich stack, as the server's list of stacks says.
 *
 * The timeline only names the covers. This is what tells the other photos of a stack apart when an
 * album or a search brings them — the search does not say which stack a photo is in — so they stay
 * out of the grid, under their cover.
 */
@Entity(
    tableName = "stack_members",
    primaryKeys = ["libraryKey", "assetId"],
    indices = [Index(value = ["libraryKey", "stackId"])],
)
data class StackMemberEntity(
    val libraryKey: String,
    val assetId: String,
    val stackId: String,
    val primaryAssetId: String,
) {
    val isCover: Boolean get() = assetId == primaryAssetId
}

/** How many photos a stack holds. */
data class StackSize(val stackId: String, val assetCount: Int)

@Dao
interface StackMemberDao {
    @Query("DELETE FROM stack_members WHERE libraryKey = :libraryKey")
    suspend fun clear(libraryKey: String)

    @Query("DELETE FROM stack_members WHERE libraryKey = :libraryKey AND stackId = :stackId")
    suspend fun clearStack(libraryKey: String, stackId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<StackMemberEntity>)

    @Query("SELECT * FROM stack_members WHERE libraryKey = :libraryKey AND assetId IN (:assetIds)")
    suspend fun ofAssets(libraryKey: String, assetIds: List<String>): List<StackMemberEntity>

    @Query(
        """
        SELECT stackId, COUNT(*) AS assetCount FROM stack_members
        WHERE libraryKey = :libraryKey AND stackId IN (:stackIds)
        GROUP BY stackId
        """,
    )
    suspend fun sizes(libraryKey: String, stackIds: List<String>): List<StackSize>

    /**
     * Takes out of the catalogue the photos that are in a stack without being its cover. Albums and
     * searches had been writing them there, and the timeline, which reads the catalogue, showed them
     * loose beside their cover.
     */
    @Query(
        """
        DELETE FROM assets WHERE libraryKey = :libraryKey AND id IN (
            SELECT assetId FROM stack_members WHERE libraryKey = :libraryKey AND assetId != primaryAssetId
        )
        """,
    )
    suspend fun removeCoveredFromCatalogue(libraryKey: String)
}
