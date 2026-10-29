package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * What a look inside a device photo found: its movement, or none ([movement] null).
 *
 * Kept apart from `assets` because every MediaStore sync rewrites those rows; this survives it, and
 * goes back onto them by [identity] — the `local:size:modified` the row carries as its checksum. A
 * file changed since the look has another identity, and is looked at again.
 */
@Entity(tableName = "local_motions", primaryKeys = ["libraryKey", "assetId"])
data class LocalMotionEntity(
    val libraryKey: String,
    val assetId: String,
    val identity: String,
    /** An encoded `EmbeddedMotion`, as the row's `livePhotoVideoId` carries it. */
    val movement: String?,
)

@Dao
interface LocalMotionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(motions: List<LocalMotionEntity>)

    /**
     * The photos not looked inside yet, or changed since, the newest first: the ones on screen when the
     * library opens get their badge before the old ones. Only JPEG: the layouts found on phones so far
     * are JPEG's.
     */
    @Query(
        """
        SELECT a.* FROM assets a
        WHERE a.libraryKey = :libraryKey AND a.type = 'IMAGE' AND a.mimeType = 'image/jpeg'
          AND NOT EXISTS (SELECT 1 FROM local_motions m WHERE m.libraryKey = a.libraryKey AND m.assetId = a.id AND m.identity = a.checksum)
        ORDER BY a.fileCreatedAt DESC, a.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(libraryKey: String, limit: Int): List<AssetEntity>

    /** What was found, put back on the rows a sync has just rewritten. */
    @Query(
        """
        UPDATE assets
        SET livePhotoVideoId = (SELECT m.movement FROM local_motions m WHERE m.libraryKey = assets.libraryKey AND m.assetId = assets.id AND m.identity = assets.checksum),
            livePhotoKnown = EXISTS (SELECT 1 FROM local_motions m WHERE m.libraryKey = assets.libraryKey AND m.assetId = assets.id AND m.identity = assets.checksum)
        WHERE libraryKey = :libraryKey
        """,
    )
    suspend fun restore(libraryKey: String)

    @Query(
        """
        UPDATE assets
        SET livePhotoVideoId = (SELECT m.movement FROM local_motions m WHERE m.libraryKey = assets.libraryKey AND m.assetId = assets.id AND m.identity = assets.checksum),
            livePhotoKnown = EXISTS (SELECT 1 FROM local_motions m WHERE m.libraryKey = assets.libraryKey AND m.assetId = assets.id AND m.identity = assets.checksum)
        WHERE libraryKey = :libraryKey AND id IN (:ids)
        """,
    )
    suspend fun restore(libraryKey: String, ids: List<String>)

    /** The looks at photos that are no longer in the catalogue. */
    @Query("DELETE FROM local_motions WHERE libraryKey = :libraryKey AND assetId NOT IN (SELECT id FROM assets WHERE libraryKey = :libraryKey)")
    suspend fun forgetGone(libraryKey: String)
}
