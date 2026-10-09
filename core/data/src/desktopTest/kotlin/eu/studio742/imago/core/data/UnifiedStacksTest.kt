package eu.studio742.imago.core.data

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.StackMemberEntity
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.UserText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Stacks in the unified library, where a photo on the phone and on the server shows once, as the
 * phone's copy: the server's stacks have to show through those copies, and a stack made there has to
 * reach both sides.
 */
class UnifiedStacksTest {
    private val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @After fun tearDown() = database.close()

    private fun row(library: String, id: String, name: String, createdAt: String) =
        AssetEntity(library, id, "c-$id", name, createdAt, createdAt.dropLast(1), null, null,
            isFavorite = false, isEdited = false, hasLocalRecipe = false, type = "IMAGE")

    private inner class Phone(private val rows: List<AssetEntity>) : LocalCatalogLibrary(database) {
        override val accessRevision: StateFlow<Int> = MutableStateFlow(0)
        override fun accessSummary() = UserText(UserMessage.FOLDERS_NONE)
        override fun refreshAccess() = Unit
        override suspend fun syncCatalog() = replaceCatalog(rows)
        override suspend fun rowsOf(ids: Collection<String>) = rows.filter { it.id in ids }
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("unused")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = Unit
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit
    }

    /** A server whose stacks are what the test puts in the table; it only records what it is asked. */
    private class Server : LibraryRepository {
        val stacked = mutableListOf<List<String>>()
        override val canStack = true
        override suspend fun stackTogether(assetIds: List<String>) { stacked += assetIds }
        override suspend fun syncCatalog() = Unit
        override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?): Flow<PagingData<ImmichAsset>> = error("unused")
        override suspend fun albums(): List<ImmichAlbum> = error("unused")
        override suspend fun timeBuckets(): List<ImmichTimeBucket> = error("unused")
        override val catalogSync: StateFlow<CatalogSyncState> = MutableStateFlow(CatalogSyncState())
        override suspend fun loadMonth(month: String) = Unit
        override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override suspend fun indexOfDate(date: LocalDate, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override fun thumbnailUrl(assetId: String) = assetId
        override fun previewUrl(assetId: String) = assetId
        override fun videoPlaybackUrl(assetId: String) = assetId
        override fun apiKey(assetId: String) = ""
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("unused")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = Unit
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit
    }

    private suspend fun unifiedGrid(): List<ImmichAsset> {
        val source = database.assetDao().unifiedPagingSource(DEVICE_LIBRARY_ID, SERVER, false, false, null, null, null)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.asset.toDomain().copy(stackId = it.unifiedStackId, stackCount = it.unifiedStackSize) }
    }

    private val one = "2025-10-01T10:00:00.000Z"
    private val two = "2025-10-02T10:00:00.000Z"
    private val three = "2025-10-03T10:00:00.000Z"

    @Test fun aServerStackShowsThroughThePhonesCopies() = runBlocking {
        // Two photos on both sides, stacked on the server; the second one is under the cover there.
        val phone = Phone(listOf(row(DEVICE_LIBRARY_ID, "p1", "one.jpg", one), row(DEVICE_LIBRARY_ID, "p2", "two.jpg", two)))
        database.assetDao().upsertAll(listOf(row(SERVER, "s1", "one.jpg", one)))
        database.stackMemberDao().insertAll(
            listOf(
                StackMemberEntity(SERVER, "s1", "stack", "s1", "one.jpg", one),
                StackMemberEntity(SERVER, "s2", "stack", "s1", "two.jpg", two),
            ),
        )
        val library = UnifiedLibrary(database, phone, Server(), SERVER)

        library.syncCatalog()

        val grid = unifiedGrid()
        assertEquals(listOf("p1"), grid.map { it.id })
        assertEquals(2, grid.single().stackCount)
        // The strip: the phone's copies, the cover first.
        assertEquals(
            listOf("p1", "p2"),
            library.stackMembers(eu.studio742.imago.core.model.AssetReference(DEVICE_LIBRARY_ID, "p1").encode())
                .map { eu.studio742.imago.core.model.AssetReference.parse(it.id).localId },
        )
    }

    @Test fun stackingInTheUnifiedLibraryStacksOnBothSides() = runBlocking {
        // p1 and p2 are on both sides; p3 only on the phone.
        val phone = Phone(
            listOf(
                row(DEVICE_LIBRARY_ID, "p1", "one.jpg", one),
                row(DEVICE_LIBRARY_ID, "p2", "two.jpg", two),
                row(DEVICE_LIBRARY_ID, "p3", "three.jpg", three),
            ),
        )
        database.assetDao().upsertAll(listOf(row(SERVER, "s1", "one.jpg", one), row(SERVER, "s2", "two.jpg", two)))
        val server = Server()
        val library = UnifiedLibrary(database, phone, server, SERVER)
        library.syncCatalog()

        library.stackTogether(listOf("p2", "p1", "p3").map { eu.studio742.imago.core.model.AssetReference(DEVICE_LIBRARY_ID, it).encode() })

        // On the server, the two that are there, in the order chosen.
        assertEquals(listOf(listOf("s2", "s1")), server.stacked)
        // On the phone, all three.
        assertEquals(setOf("p1", "p2", "p3"), database.stackMemberDao().all(DEVICE_LIBRARY_ID).map { it.assetId }.toSet())
        val grid = unifiedGrid()
        assertEquals(listOf("p2"), grid.map { it.id })
        assertEquals(3, grid.single().stackCount)
    }

    @Test fun photosTogetherOnNeitherSideAreNotStacked() = runBlocking {
        // One only on the phone, one only on the server.
        val phone = Phone(listOf(row(DEVICE_LIBRARY_ID, "p1", "one.jpg", one)))
        database.assetDao().upsertAll(listOf(row(SERVER, "s9", "nine.jpg", two)))
        val library = UnifiedLibrary(database, phone, Server(), SERVER)
        library.syncCatalog()

        val refused = runCatching {
            library.stackTogether(
                listOf(
                    eu.studio742.imago.core.model.AssetReference(DEVICE_LIBRARY_ID, "p1").encode(),
                    eu.studio742.imago.core.model.AssetReference(SERVER, "s9").encode(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(refused is UserMessageException)
        assertEquals(UserMessage.STACK_APART, (refused as UserMessageException).userMessage)
    }

    @Test fun aPhoneStackHidesItsPhotosServerCopiesToo() = runBlocking {
        // Both photos are backed up; the stack was made in the phone's own library.
        val phone = Phone(listOf(row(DEVICE_LIBRARY_ID, "p1", "one.jpg", one), row(DEVICE_LIBRARY_ID, "p2", "two.jpg", two)))
        database.assetDao().upsertAll(listOf(row(SERVER, "s1", "one.jpg", one), row(SERVER, "s2", "two.jpg", two)))
        phone.syncCatalog()
        phone.stackTogether(listOf("p1", "p2"))

        UnifiedLibrary(database, phone, Server(), SERVER).syncCatalog()

        val grid = unifiedGrid()
        assertEquals(listOf("p1"), grid.map { it.id })
        assertEquals(2, grid.single().stackCount)
    }

    @Test fun stacksThatShareAPhotoAreOne() {
        val remote = listOf(
            StackMemberEntity(SERVER, "s1", "r", "s1", "one.jpg", one),
            StackMemberEntity(SERVER, "s2", "r", "s1", "two.jpg", two),
            StackMemberEntity(SERVER, "s4", "r", "s1", "four.jpg", one),
        )
        val local = listOf(
            StackMemberEntity(DEVICE_LIBRARY_ID, "p2", "l", "p3"),
            StackMemberEntity(DEVICE_LIBRARY_ID, "p3", "l", "p3"),
        )

        val rows = unifiedStacks(SERVER, local, remote, phoneCopyOf = mapOf("s1" to "p1", "s2" to "p2"))
            .associateBy { it.libraryKey to it.assetId }

        // One group: p1, p2, p3 on the phone and s4, only on the server; the server's cover leads.
        assertEquals(4, rows.size)
        assertEquals(setOf(4), rows.values.map { it.groupSize }.toSet())
        assertTrue(rows.getValue(DEVICE_LIBRARY_ID to "p1").isCover)
        assertEquals("s4", rows.getValue(SERVER to "s4").serverAssetId)
        assertEquals("s2", rows.getValue(DEVICE_LIBRARY_ID to "p2").serverAssetId)
        assertNull(rows.getValue(DEVICE_LIBRARY_ID to "p3").serverAssetId)
    }

    private companion object {
        const val SERVER = "server"
    }
}
