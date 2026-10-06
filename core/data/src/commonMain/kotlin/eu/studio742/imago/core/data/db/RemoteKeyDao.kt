package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RemoteKeyDao {
    @Query("SELECT * FROM remote_keys WHERE libraryKey = :libraryKey AND filter = :filter")
    suspend fun get(libraryKey: String, filter: String): RemoteKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: RemoteKeyEntity)

    @Query("DELETE FROM remote_keys WHERE libraryKey = :libraryKey AND filter = :filter")
    suspend fun clear(libraryKey: String, filter: String)

    /**
     * Forgets the paging of every slice of this library.
     *
     * It goes with `AssetDao.clear`: the catalogue is a single one shared by every slice, so deleting
     * it leaves the others' keys pointing to pages of a catalogue that no longer exists — and a
     * neighbouring chip would open on an empty grid convinced it was already synced.
     */
    @Query("DELETE FROM remote_keys WHERE libraryKey = :libraryKey")
    suspend fun clearLibrary(libraryKey: String)
}

