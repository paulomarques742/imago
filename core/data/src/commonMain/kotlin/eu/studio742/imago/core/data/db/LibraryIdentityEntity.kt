package eu.studio742.imago.core.data.db

import androidx.room.*

@Entity(tableName = "library_identities")
data class LibraryIdentityEntity(@PrimaryKey val id: String)

@Dao
interface LibraryIdentityDao {
    @Query("SELECT id FROM library_identities") suspend fun ids(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(identity: LibraryIdentityEntity)
}
