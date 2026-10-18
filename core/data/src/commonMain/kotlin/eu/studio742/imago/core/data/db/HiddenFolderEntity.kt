package eu.studio742.imago.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * An album of this device whose photos stay out of "All", "On this day" and the unified library —
 * screenshots, what arrives from messaging apps. The album itself still shows them, and so do the
 * Recent, Favourites and Edited chips. A choice of this device's only: it does not sync.
 *
 * The hidden ones are kept, not the shown ones, so that an album that appears later shows.
 */
@Entity(tableName = "hidden_folders", primaryKeys = ["libraryKey", "folderId"])
data class HiddenFolderEntity(val libraryKey: String, val folderId: String)

@Dao
interface HiddenFolderDao {
    @Query("SELECT folderId FROM hidden_folders WHERE libraryKey = :libraryKey")
    fun observe(libraryKey: String): Flow<List<String>>

    @Query("DELETE FROM hidden_folders WHERE libraryKey = :libraryKey")
    suspend fun clear(libraryKey: String)

    @Insert
    suspend fun insertAll(folders: List<HiddenFolderEntity>)
}
