package eu.studio742.imago.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AssetDao {
    @Query("DELETE FROM assets WHERE libraryKey = :libraryKey")
    suspend fun deleteLibrary(libraryKey: String)
    @Query("SELECT * FROM assets WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun asset(libraryKey: String, id: String): AssetEntity?
    @Query("SELECT * FROM assets WHERE libraryKey = :libraryKey ORDER BY fileCreatedAt DESC, id DESC")
    suspend fun allAssets(libraryKey: String): List<AssetEntity>

    /** The Immich photos with this `checksum` (the SHA-1 in Base64, as the server gives it). */
    @Query("SELECT * FROM assets WHERE checksum = :checksum AND libraryKey != 'device'")
    suspend fun byChecksum(checksum: String): List<AssetEntity>

    /** The candidates for "the same photo" in a library, by file size. */
    @Query("SELECT * FROM assets WHERE libraryKey = :libraryKey AND sizeBytes = :sizeBytes")
    suspend fun withSize(libraryKey: String, sizeBytes: Long): List<AssetEntity>

    /**
     * The library grid.
     *
     * `editedOnly` has no counterpart in Immich — `MetadataSearchDto` does not accept `isEdited` —
     * so it is here, over the already synced catalogue, that the "Edited" chip is resolved. The same
     * goes for `hasLocalRecipe`, which only exists locally.
     *
     * There is no filtering by type: photos and videos share the grid. What goes into the catalogue
     * is decided by the sync, and that is where audio and loose files are left out.
     */
    @Query(
        """
        SELECT * FROM assets
        WHERE libraryKey = :libraryKey
          AND (:favoritesOnly = 0 OR isFavorite = 1)
          AND (:editedOnly = 0 OR isEdited = 1 OR hasLocalRecipe = 1)
          AND (:monthStart IS NULL OR fileCreatedAt >= :monthStart)
          AND (:monthEnd IS NULL OR fileCreatedAt < :monthEnd)
          AND (:albumId IS NULL OR folderId = :albumId)
          AND (:query IS NULL OR originalFileName LIKE '%' || :query || '%')
        ORDER BY fileCreatedAt DESC, id DESC
        """,
    )
    fun pagingSource(
        libraryKey: String,
        favoritesOnly: Boolean,
        editedOnly: Boolean,
        monthStart: String?,
        monthEnd: String?,
        query: String?,
        albumId: String? = null,
    ): PagingSource<Int, AssetEntity>

    @Query("UPDATE assets SET isFavorite = :isFavorite WHERE libraryKey = :libraryKey AND id = :assetId")
    suspend fun setFavorite(libraryKey: String, assetId: String, isFavorite: Boolean)

    @Query("DELETE FROM assets WHERE libraryKey = :libraryKey AND id = :assetId")
    suspend fun delete(libraryKey: String, assetId: String)

    /**
     * How many photos in this view are more recent than this instant.
     *
     * As the grid is sorted by `fileCreatedAt DESC`, that count is exactly the index of the first
     * photo of that date or earlier — which turns "jump to 3 March" into an indexed count instead of
     * a scan through the loaded list. The filters are the same as [pagingSource]'s, and have to stay
     * so: if they diverge, the index points to a position in another list.
     */
    @Query(
        """
        SELECT COUNT(*) FROM assets
        WHERE libraryKey = :libraryKey
          AND (:favoritesOnly = 0 OR isFavorite = 1)
          AND (:editedOnly = 0 OR isEdited = 1 OR hasLocalRecipe = 1)
          AND (:monthStart IS NULL OR fileCreatedAt >= :monthStart)
          AND (:monthEnd IS NULL OR fileCreatedAt < :monthEnd)
          AND (:query IS NULL OR originalFileName LIKE '%' || :query || '%')
          AND fileCreatedAt > :boundary
        """,
    )
    suspend fun countNewerThan(
        libraryKey: String,
        favoritesOnly: Boolean,
        editedOnly: Boolean,
        monthStart: String?,
        monthEnd: String?,
        query: String?,
        boundary: String,
    ): Int

    /**
     * A photo's position in this view.
     *
     * It counts the ones before it in grid order — `fileCreatedAt DESC, id DESC` — which gives the
     * exact index without depending on what paging has read. Coming back from the detail needs this:
     * the photo one came from may be three thousand positions from the start, and looking for it in
     * the loaded list only found it if it happened to be there.
     */
    @Query(
        """
        SELECT COUNT(*) FROM assets
        WHERE libraryKey = :libraryKey
          AND (:favoritesOnly = 0 OR isFavorite = 1)
          AND (:editedOnly = 0 OR isEdited = 1 OR hasLocalRecipe = 1)
          AND (:monthStart IS NULL OR fileCreatedAt >= :monthStart)
          AND (:monthEnd IS NULL OR fileCreatedAt < :monthEnd)
          AND (:query IS NULL OR originalFileName LIKE '%' || :query || '%')
          AND (
            fileCreatedAt > :anchorCreatedAt
            OR (fileCreatedAt = :anchorCreatedAt AND id > :anchorId)
          )
        """,
    )
    suspend fun countBefore(
        libraryKey: String,
        favoritesOnly: Boolean,
        editedOnly: Boolean,
        monthStart: String?,
        monthEnd: String?,
        query: String?,
        anchorCreatedAt: String,
        anchorId: String,
    ): Int

    /** The date of a catalogue photo, to serve as the anchor for [countBefore]. */
    @Query("SELECT fileCreatedAt FROM assets WHERE libraryKey = :libraryKey AND id = :assetId")
    suspend fun createdAt(libraryKey: String, assetId: String): String?

    /** The photos of a month, by the same expression [timeBuckets] groups them by. */
    @Query(
        """
        SELECT * FROM assets
        WHERE libraryKey = :libraryKey
          AND substr(CASE WHEN localDateTime = '' THEN fileCreatedAt ELSE localDateTime END, 1, 7) = :month
        """,
    )
    suspend fun byMonth(libraryKey: String, month: String): List<AssetEntity>

    /**
     * Empties a month to write it again with what the server has now.
     *
     * This is how a photo deleted on another device disappears here: the month is rewritten whole,
     * and whatever does not come in the bucket stops existing. What was known beyond that about the
     * photos that stay — name, checksum, dimensions — is read first and goes back in with them.
     */
    @Query(
        """
        DELETE FROM assets
        WHERE libraryKey = :libraryKey
          AND substr(CASE WHEN localDateTime = '' THEN fileCreatedAt ELSE localDateTime END, 1, 7) = :month
        """,
    )
    suspend fun deleteMonth(libraryKey: String, month: String)

    /**
     * Fills in what the month bucket did not bring, with what the photo's detail brought.
     *
     * The catalogue grows at two speeds: the skeleton arrives month by month and knows where each
     * photo is in the timeline; the name and the checksum arrive when someone opens it.
     */
    @Query(
        """
        UPDATE assets
        SET checksum = :checksum,
            originalFileName = :originalFileName,
            width = :width,
            height = :height,
            isEdited = :isEdited,
            mimeType = :mimeType
        WHERE libraryKey = :libraryKey AND id = :assetId
        """,
    )
    suspend fun enrich(
        libraryKey: String,
        assetId: String,
        checksum: String,
        originalFileName: String,
        width: Long?,
        height: Long?,
        isEdited: Boolean,
        mimeType: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<AssetEntity>)

    @Query("DELETE FROM assets WHERE libraryKey = :libraryKey")
    suspend fun clear(libraryKey: String)

    /**
     * Deletes this app's exports that stayed in the catalogue before they started being filtered.
     *
     * The `LIKE` catches those sent from another device, or before the `derived_assets` table
     * existed — see `isImmichRoomExport`.
     */
    @Query(
        """
        DELETE FROM assets
        WHERE libraryKey = :libraryKey
          AND (id IN (SELECT derivedAssetId FROM derived_assets WHERE libraryKey = :libraryKey)
               OR originalFileName LIKE '%\_ImmichRoom.jpg' ESCAPE '\')
        """,
    )
    suspend fun purgeAppExports(libraryKey: String)

    @Query("UPDATE assets SET hasLocalRecipe = 1 WHERE libraryKey = :libraryKey AND id = :assetId")
    suspend fun markHasLocalRecipe(libraryKey: String, assetId: String)

    @Query("UPDATE assets SET hasLocalRecipe = 0 WHERE libraryKey = :libraryKey AND id = :assetId")
    suspend fun clearHasLocalRecipe(libraryKey: String, assetId: String)

    @Query(
        """
        UPDATE assets SET hasLocalRecipe = 1
        WHERE libraryKey = :libraryKey
          AND id IN (SELECT assetId FROM recipes WHERE libraryKey = :libraryKey AND deletedAt IS NULL)
        """,
    )
    suspend fun restoreLocalRecipeFlags(libraryKey: String)

    @Query(
        """
        SELECT substr(CASE WHEN localDateTime = '' THEN fileCreatedAt ELSE localDateTime END, 1, 7) || '-01' AS month,
               COUNT(*) AS assetCount
        FROM assets
        WHERE libraryKey = :libraryKey
        GROUP BY substr(CASE WHEN localDateTime = '' THEN fileCreatedAt ELSE localDateTime END, 1, 7)
        ORDER BY month DESC
        """,
    )
    suspend fun timeBuckets(libraryKey: String): List<LocalTimeBucket>
}

data class LocalTimeBucket(val month: String, val assetCount: Int)
