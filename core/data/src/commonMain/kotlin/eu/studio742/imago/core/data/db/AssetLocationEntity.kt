package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Where a photo of this device was taken, read once from its file.
 *
 * Reading the EXIF of tens of thousands of photos takes minutes, so what was read stays here, apart
 * from the catalogue rows, which are rewritten on every sync. [checksum] is the catalogue's at the
 * time: a photo that changed is read again. A photo read without a place keeps null coordinates, so
 * it is not read again either.
 */
@Entity(tableName = "asset_locations", primaryKeys = ["libraryKey", "assetId"])
data class AssetLocationEntity(
    val libraryKey: String,
    val assetId: String,
    val checksum: String,
    val latitude: Double?,
    val longitude: Double?,
)

data class LocatedAsset(val assetId: String, val latitude: Double, val longitude: Double)

data class UnreadAsset(val id: String, val checksum: String)

@Dao
interface AssetLocationDao {
    /** The photos of the catalogue with a place, as they are now. */
    @Query(
        """
        SELECT a.id AS assetId, l.latitude AS latitude, l.longitude AS longitude
        FROM assets a JOIN asset_locations l ON l.libraryKey = a.libraryKey AND l.assetId = a.id AND l.checksum = a.checksum
        WHERE a.libraryKey = :libraryKey AND l.latitude IS NOT NULL AND l.longitude IS NOT NULL
        """,
    )
    suspend fun located(libraryKey: String): List<LocatedAsset>

    /** Photos whose place was never read, or that changed since. Videos keep it elsewhere than the EXIF. */
    @Query(
        """
        SELECT a.id AS id, a.checksum AS checksum FROM assets a
        LEFT JOIN asset_locations l ON l.libraryKey = a.libraryKey AND l.assetId = a.id AND l.checksum = a.checksum
        WHERE a.libraryKey = :libraryKey AND a.type = 'IMAGE' AND l.assetId IS NULL
        ORDER BY a.fileCreatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun unread(libraryKey: String, limit: Int): List<UnreadAsset>

    @Query(
        """
        SELECT COUNT(*) FROM assets a
        LEFT JOIN asset_locations l ON l.libraryKey = a.libraryKey AND l.assetId = a.id AND l.checksum = a.checksum
        WHERE a.libraryKey = :libraryKey AND a.type = 'IMAGE' AND l.assetId IS NULL
        """,
    )
    suspend fun unreadCount(libraryKey: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(locations: List<AssetLocationEntity>)

    @Query("UPDATE OR REPLACE asset_locations SET assetId = :to WHERE libraryKey = :libraryKey AND assetId = :from")
    suspend fun moveAsset(libraryKey: String, from: String, to: String)
}
