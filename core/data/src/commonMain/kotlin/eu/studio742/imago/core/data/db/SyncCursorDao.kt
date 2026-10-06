package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncCursorDao {
    @Query("SELECT cursor FROM sync_cursors WHERE entity = :entity")
    suspend fun get(entity: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(cursor: SyncCursorEntity)

    /** An account that leaves takes the cursors: the next account receives everything again. */
    @Query("DELETE FROM sync_cursors")
    suspend fun clear()
}
