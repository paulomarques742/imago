package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompositionProjectDao {
    @Query("SELECT * FROM composition_projects WHERE libraryKey = :libraryKey AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(libraryKey: String): Flow<List<CompositionProjectEntity>>

    @Query("SELECT * FROM composition_projects WHERE libraryKey = :libraryKey AND id = :id AND deletedAt IS NULL")
    suspend fun get(libraryKey: String, id: String): CompositionProjectEntity?

    @Query("SELECT * FROM composition_projects WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun getAny(libraryKey: String, id: String): CompositionProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompositionProjectEntity)

    @Query("DELETE FROM composition_projects WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun delete(libraryKey: String, id: String)

    @Query("SELECT * FROM composition_projects WHERE dirty = 1 ORDER BY editedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<CompositionProjectEntity>

    @Query("SELECT COUNT(*) FROM composition_projects WHERE dirty = 1")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM composition_projects WHERE dirty = 1")
    fun observePendingCount(): Flow<Int>

    /**
     * The rows with unresolved references (`AssetReference.isRemote`). [marker] is the start of
     * those references' encoded form; see `ReferenceTranslator.REMOTE_MARKER`.
     */
    @Query("SELECT * FROM composition_projects WHERE deletedAt IS NULL AND projectJson LIKE '%' || :marker || '%'")
    suspend fun withRemoteMedia(marker: String): List<CompositionProjectEntity>

    /** A reference resolved on this device is not an edit: the sync state stays. */
    @Query("UPDATE composition_projects SET projectJson = :json WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun replaceJson(libraryKey: String, id: String, json: String)

    /**
     * A photo's hash became ready after the project went up without it: it goes up again, with the
     * same `editedAt`, because nobody edited it.
     */
    @Query(
        """
        UPDATE composition_projects SET dirty = 1
        WHERE dirty = 0 AND deletedAt IS NULL AND remoteRevision IS NOT NULL AND projectJson LIKE '%' || :reference || '%'
        """,
    )
    suspend fun markDirtyReferencing(reference: String)
}

@Dao
interface CompositionTemplateDao {
    @Query("SELECT * FROM composition_templates WHERE libraryKey = :libraryKey AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(libraryKey: String): Flow<List<CompositionTemplateEntity>>

    @Query("SELECT * FROM composition_templates WHERE libraryKey = :libraryKey AND id = :id AND deletedAt IS NULL")
    suspend fun get(libraryKey: String, id: String): CompositionTemplateEntity?

    @Query("SELECT * FROM composition_templates WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun getAny(libraryKey: String, id: String): CompositionTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompositionTemplateEntity)

    @Query("DELETE FROM composition_templates WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun delete(libraryKey: String, id: String)

    @Query("SELECT * FROM composition_templates WHERE dirty = 1 ORDER BY editedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<CompositionTemplateEntity>

    @Query("SELECT COUNT(*) FROM composition_templates WHERE dirty = 1")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM composition_templates WHERE dirty = 1")
    fun observePendingCount(): Flow<Int>

    /**
     * The rows with unresolved references (`AssetReference.isRemote`). [marker] is the start of
     * those references' encoded form; see `ReferenceTranslator.REMOTE_MARKER`.
     */
    @Query("SELECT * FROM composition_templates WHERE deletedAt IS NULL AND templateJson LIKE '%' || :marker || '%'")
    suspend fun withRemoteMedia(marker: String): List<CompositionTemplateEntity>

    @Query("UPDATE composition_templates SET templateJson = :json WHERE libraryKey = :libraryKey AND id = :id")
    suspend fun replaceJson(libraryKey: String, id: String, json: String)

    @Query(
        """
        UPDATE composition_templates SET dirty = 1
        WHERE dirty = 0 AND deletedAt IS NULL AND remoteRevision IS NOT NULL AND templateJson LIKE '%' || :reference || '%'
        """,
    )
    suspend fun markDirtyReferencing(reference: String)
}

@Dao
interface BrandKitDao {
    @Query("SELECT * FROM brand_kits WHERE libraryKey = :libraryKey AND deletedAt IS NULL")
    fun observe(libraryKey: String): Flow<BrandKitEntity?>

    @Query("SELECT * FROM brand_kits WHERE libraryKey = :libraryKey")
    suspend fun getAny(libraryKey: String): BrandKitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BrandKitEntity)

    @Query("SELECT * FROM brand_kits WHERE dirty = 1 ORDER BY editedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<BrandKitEntity>

    @Query("SELECT COUNT(*) FROM brand_kits WHERE dirty = 1")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM brand_kits WHERE dirty = 1")
    fun observePendingCount(): Flow<Int>

    /**
     * The rows with unresolved references (`AssetReference.isRemote`). [marker] is the start of
     * those references' encoded form; see `ReferenceTranslator.REMOTE_MARKER`.
     */
    @Query("SELECT * FROM brand_kits WHERE deletedAt IS NULL AND kitJson LIKE '%' || :marker || '%'")
    suspend fun withRemoteMedia(marker: String): List<BrandKitEntity>

    @Query("UPDATE brand_kits SET kitJson = :json WHERE libraryKey = :libraryKey")
    suspend fun replaceJson(libraryKey: String, json: String)

    @Query(
        """
        UPDATE brand_kits SET dirty = 1
        WHERE dirty = 0 AND deletedAt IS NULL AND remoteRevision IS NOT NULL AND kitJson LIKE '%' || :reference || '%'
        """,
    )
    suspend fun markDirtyReferencing(reference: String)
}
