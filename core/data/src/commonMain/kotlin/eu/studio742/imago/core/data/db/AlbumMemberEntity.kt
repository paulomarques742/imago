package eu.studio742.imago.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A photo in a server's album, at the place the server gives it.
 *
 * The photos themselves are already in `assets`, written by every read of an album; what was not kept
 * was which album they belong to, so an album could only be shown by asking the server again. With this,
 * an album opens from the catalogue and the server's answer corrects it afterwards.
 *
 * [refreshedAt] is the read that last saw the photo there: once a read has gone through the whole album,
 * every row it did not see is a photo taken out of it elsewhere, and goes.
 */
@Entity(tableName = "album_members", primaryKeys = ["libraryKey", "albumId", "assetId"])
data class AlbumMemberEntity(
    val libraryKey: String,
    val albumId: String,
    val assetId: String,
    val position: Int,
    val refreshedAt: Long,
)

@Dao
interface AlbumMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<AlbumMemberEntity>)

    /** What the read that finished at [refreshedAt] did not find: taken out of the album meanwhile. */
    @Query("DELETE FROM album_members WHERE libraryKey = :libraryKey AND albumId = :albumId AND refreshedAt != :refreshedAt")
    suspend fun removeUnseen(libraryKey: String, albumId: String, refreshedAt: Long)

    @Query("DELETE FROM album_members WHERE libraryKey = :libraryKey AND assetId IN (:assetIds)")
    suspend fun removeAssets(libraryKey: String, assetIds: List<String>)

    /**
     * The album's grid, in the server's order. The same chips and search as the timeline, applied here
     * because the album is read whole; a photo under a stack's cover stays under it, as in the timeline.
     */
    @Query(
        """
        SELECT a.* FROM album_members m
        JOIN assets a ON a.libraryKey = m.libraryKey AND a.id = m.assetId
        WHERE m.libraryKey = :libraryKey AND m.albumId = :albumId
          AND (:favoritesOnly = 0 OR a.isFavorite = 1)
          AND (:editedOnly = 0 OR a.isEdited = 1 OR a.hasLocalRecipe = 1)
          AND NOT EXISTS (SELECT 1 FROM stack_members s WHERE s.libraryKey = a.libraryKey AND s.assetId = a.id AND s.assetId != s.primaryAssetId)
          AND (:query IS NULL OR a.originalFileName LIKE '%' || :query || '%')
        ORDER BY m.position, a.id
        """,
    )
    fun pagingSource(
        libraryKey: String,
        albumId: String,
        favoritesOnly: Boolean,
        editedOnly: Boolean,
        query: String?,
    ): PagingSource<Int, AssetEntity>
}
