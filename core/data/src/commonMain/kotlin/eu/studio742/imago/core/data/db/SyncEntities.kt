package eu.studio742.imago.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A record's sync state, the same in every table that syncs.
 *
 * It exists even without an account: saving marks the record as [dirty] and deleting leaves a mark
 * in [deletedAt], so that signing into an account later knows what is waiting to be sent.
 * [remoteRevision] is null while the record never reached the backend — and a record in that state
 * can be deleted for good, because there is no other device to tell.
 */
data class SyncState(
    val remoteRevision: Long? = null,
    @ColumnInfo(defaultValue = "") val editedAt: String = "",
    val editedByDevice: String? = null,
    val deletedAt: String? = null,
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
) {
    /** The same record after the person changed it just now — which also brings it back. */
    fun edited(now: String) = copy(editedAt = now, deletedAt = null, dirty = true)

    /** The deletion mark of a record that already exists in the backend. */
    fun deleted(now: String) = copy(editedAt = now, deletedAt = now, dirty = true)
}

/** How far each entity has already been received from the backend (`seq`). */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val entity: String,
    val cursor: Long,
)

/**
 * The backend library that matches a local key.
 *
 * The local key is never rewritten and never leaves the device; references are translated at the
 * boundary through this table.
 */
@Entity(tableName = "library_links")
data class LibraryLinkEntity(
    @PrimaryKey val localKey: String,
    val remoteLibraryId: String,
)

@Dao
interface LibraryLinkDao {
    @Query("SELECT * FROM library_links WHERE localKey = :localKey")
    suspend fun get(localKey: String): LibraryLinkEntity?

    @Query("SELECT * FROM library_links")
    suspend fun all(): List<LibraryLinkEntity>

    @Query("SELECT * FROM library_links WHERE remoteLibraryId = :remoteLibraryId LIMIT 1")
    suspend fun byRemote(remoteLibraryId: String): LibraryLinkEntity?

    /** A library linked again can resolve references that arrived before it. */
    @Query("SELECT * FROM library_links")
    fun observeAll(): Flow<List<LibraryLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: LibraryLinkEntity)

    @Query("DELETE FROM library_links WHERE localKey = :localKey")
    suspend fun delete(localKey: String)
}

/**
 * The SHA-1 of a photo's original file.
 *
 * For device photos it is computed by reading the file and is only valid while the MediaStore size
 * and `date_modified` stay the same. For Immich ones it comes from the server's `checksum`, and
 * [size] and [dateModified] stay null.
 */
@Entity(
    tableName = "content_hashes",
    primaryKeys = ["libraryKey", "assetId"],
    indices = [Index(value = ["sha1"])],
)
data class ContentHashEntity(
    val libraryKey: String,
    val assetId: String,
    val sha1: String,
    val size: Long?,
    val dateModified: Long?,
)

@Dao
interface ContentHashDao {
    @Query("SELECT * FROM content_hashes WHERE libraryKey = :libraryKey AND assetId = :assetId")
    suspend fun get(libraryKey: String, assetId: String): ContentHashEntity?

    @Query("SELECT * FROM content_hashes WHERE sha1 = :sha1")
    suspend fun bySha1(sha1: String): List<ContentHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(hash: ContentHashEntity)

    @Query("UPDATE OR REPLACE content_hashes SET assetId = :to WHERE libraryKey = :libraryKey AND assetId = :from")
    suspend fun moveAsset(libraryKey: String, from: String, to: String)
}

/** A received recipe that has not found its photo on this device yet. */
@Entity(tableName = "remote_recipes")
data class RemoteRecipeEntity(
    @PrimaryKey val contentSha1: String,
    val recipeJson: String,
    val revision: Long,
    val editedAt: String,
    val editedByDevice: String?,
    val hintsJson: String?,
)

/** The version of a recipe that lost a conflict, recoverable from the editor's history. */
@Entity(tableName = "recipe_conflicts", indices = [Index(value = ["libraryKey", "assetId"])])
data class RecipeConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryKey: String,
    val assetId: String,
    val recipeJson: String,
    val deviceName: String,
    val editedAt: String,
)

/**
 * A candidate the hints pointed at and the SHA-1 disproved.
 *
 * It is recorded so it is not read again: confirming a candidate costs reading the whole file.
 */
@Entity(tableName = "candidate_rejections", primaryKeys = ["contentSha1", "libraryKey", "assetId"])
data class CandidateRejectionEntity(
    val contentSha1: String,
    val libraryKey: String,
    val assetId: String,
    val rejectedAt: String,
)

@Dao
interface RemoteRecipeDao {
    @Query("SELECT * FROM remote_recipes")
    suspend fun all(): List<RemoteRecipeEntity>

    @Query("SELECT * FROM remote_recipes WHERE contentSha1 = :sha1")
    suspend fun get(sha1: String): RemoteRecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: RemoteRecipeEntity)

    @Query("DELETE FROM remote_recipes WHERE contentSha1 = :sha1")
    suspend fun delete(sha1: String)
}

@Dao
interface RecipeConflictDao {
    @Query("SELECT * FROM recipe_conflicts WHERE libraryKey = :libraryKey AND assetId = :assetId ORDER BY editedAt DESC")
    fun observe(libraryKey: String, assetId: String): Flow<List<RecipeConflictEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM recipe_conflicts
        WHERE libraryKey = :libraryKey AND assetId = :assetId AND editedAt = :editedAt AND recipeJson = :recipeJson
        """,
    )
    suspend fun count(libraryKey: String, assetId: String, editedAt: String, recipeJson: String): Int

    @Insert
    suspend fun insert(conflict: RecipeConflictEntity)

    @Query("UPDATE recipe_conflicts SET assetId = :to WHERE libraryKey = :libraryKey AND assetId = :from")
    suspend fun moveAsset(libraryKey: String, from: String, to: String)
}

@Dao
interface CandidateRejectionDao {
    @Query(
        """
        SELECT COUNT(*) > 0 FROM candidate_rejections
        WHERE contentSha1 = :sha1 AND libraryKey = :libraryKey AND assetId = :assetId
        """,
    )
    suspend fun isRejected(sha1: String, libraryKey: String, assetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rejection: CandidateRejectionEntity)

    @Query("UPDATE OR REPLACE candidate_rejections SET assetId = :to WHERE libraryKey = :libraryKey AND assetId = :from")
    suspend fun moveAsset(libraryKey: String, from: String, to: String)
}
