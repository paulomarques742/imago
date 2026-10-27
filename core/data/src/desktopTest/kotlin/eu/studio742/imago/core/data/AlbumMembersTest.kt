package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.db.AlbumMemberEntity
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.StackMemberEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** An album opened from the catalogue: the server's order, the chips, and what a read takes out. */
class AlbumMembersTest {
    private val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    private val members = database.albumMemberDao()

    @After fun tearDown() = database.close()

    private fun row(id: String, createdAt: String, favorite: Boolean = false, edited: Boolean = false, name: String = "$id.jpg") =
        AssetEntity(SERVER, id, "c-$id", name, createdAt, createdAt.dropLast(1), null, null,
            isFavorite = favorite, isEdited = edited, hasLocalRecipe = false, type = "IMAGE")

    private fun member(id: String, position: Int, readAt: Long = 1L) = AlbumMemberEntity(SERVER, ALBUM, id, position, readAt)

    private suspend fun album(favorites: Boolean = false, edited: Boolean = false, query: String? = null): List<String> {
        val source = members.pagingSource(SERVER, ALBUM, favoritesOnly = favorites, editedOnly = edited, query = query)
        return (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.id }
    }

    @Test
    fun `the album keeps the server's order, not the dates`() = runBlocking {
        database.assetDao().upsertAll(listOf(row("old", "2019-01-01T00:00:00Z"), row("new", "2025-01-01T00:00:00Z")))
        members.upsertAll(listOf(member("old", 0), member("new", 1)))
        assertEquals(listOf("old", "new"), album())
    }

    @Test
    fun `the chips and the search act on the album`() = runBlocking {
        database.assetDao().upsertAll(listOf(
            row("beach", "2025-01-01T00:00:00Z", favorite = true, name = "beach.jpg"),
            row("city", "2025-01-02T00:00:00Z", edited = true, name = "city.jpg"),
        ))
        members.upsertAll(listOf(member("beach", 0), member("city", 1)))
        assertEquals(listOf("beach"), album(favorites = true))
        assertEquals(listOf("city"), album(edited = true))
        assertEquals(listOf("city"), album(query = "cit"))
    }

    @Test
    fun `a photo the latest read did not find leaves the album, and only this album`() = runBlocking {
        database.assetDao().upsertAll(listOf(row("kept", "2025-01-01T00:00:00Z"), row("removed", "2025-01-02T00:00:00Z")))
        members.upsertAll(listOf(member("kept", 0, readAt = 1), member("removed", 1, readAt = 1)))
        members.upsertAll(listOf(AlbumMemberEntity(SERVER, "other", "removed", 0, refreshedAt = 1)))
        members.upsertAll(listOf(member("kept", 0, readAt = 2)))
        members.removeUnseen(SERVER, ALBUM, refreshedAt = 2)
        assertEquals(listOf("kept"), album())
        val other = members.pagingSource(SERVER, "other", favoritesOnly = false, editedOnly = false, query = null)
        assertEquals(listOf("removed"), (other.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.id })
    }

    @Test
    fun `a photo under a stack's cover stays under it`() = runBlocking {
        database.assetDao().upsertAll(listOf(row("cover", "2025-01-01T00:00:00Z"), row("under", "2025-01-02T00:00:00Z")))
        database.stackMemberDao().insertAll(listOf(
            StackMemberEntity(SERVER, "cover", "stack", "cover"),
            StackMemberEntity(SERVER, "under", "stack", "cover"),
        ))
        members.upsertAll(listOf(member("cover", 0), member("under", 1)))
        assertEquals(listOf("cover"), album())
    }

    private companion object {
        const val SERVER = "server"
        const val ALBUM = "album"
    }
}
