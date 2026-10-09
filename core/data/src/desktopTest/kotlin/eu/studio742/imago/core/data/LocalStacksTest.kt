package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.StackMemberEntity
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.UserText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Stacks of this device's photos — the phone's, the computer's folders'. Neither Android nor a
 * folder has stacks, so the app keeps them, and they have to hold through every reading of the files.
 */
class LocalStacksTest {
    private val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @After fun tearDown() = database.close()

    private fun row(id: String, name: String = "$id.jpg", createdAt: String = "2025-10-0${id.length}T10:00:00.000Z", library: String = DEVICE_LIBRARY_ID) =
        AssetEntity(library, id, "c-$id", name, createdAt, createdAt.dropLast(1), null, null,
            isFavorite = false, isEdited = false, hasLocalRecipe = false, type = "IMAGE")

    /** A device whose files are [files]; a sync reads them all, as a real one does. */
    private inner class Device(var files: List<AssetEntity>) : LocalCatalogLibrary(database) {
        override val accessRevision: StateFlow<Int> = MutableStateFlow(0)
        override fun accessSummary() = UserText(eu.studio742.imago.core.model.UserMessage.FOLDERS_NONE)
        override fun refreshAccess() = Unit
        override suspend fun syncCatalog() = replaceCatalog(files)
        override suspend fun rowsOf(ids: Collection<String>) = files.filter { it.id in ids }
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("unused")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = Unit
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit
    }

    private suspend fun grid(): List<AssetEntity> {
        val source = database.assetDao().pagingSource(DEVICE_LIBRARY_ID, false, false, null, null, null)
        return (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data
    }

    @Test fun aStackShowsOnceByItsCoverAndHoldsThroughASync() = runBlocking {
        val device = Device(listOf(row("a"), row("bb"), row("ccc"), row("dddd")))
        device.syncCatalog()

        device.stackTogether(listOf("bb", "a", "ccc"))
        device.syncCatalog()

        val shown = grid().associateBy { it.id }
        assertEquals(setOf("bb", "dddd"), shown.keys)
        assertEquals(3, shown.getValue("bb").stackCount)
        assertNull(shown.getValue("dddd").stackCount)
        // The months count what the grid shows.
        assertEquals(listOf(2), database.assetDao().timeBuckets(DEVICE_LIBRARY_ID).map { it.assetCount })
        // The strip: the cover first.
        assertEquals(listOf("bb", "a", "ccc"), device.stackMembers("ccc").map { it.id })
    }

    @Test fun aFileThatLeavesTheDeviceLeavesItsStack() = runBlocking {
        val device = Device(listOf(row("a"), row("bb"), row("ccc")))
        device.syncCatalog()
        device.stackTogether(listOf("a", "bb", "ccc"))

        // The cover was deleted in another app: the next photo is the cover now.
        device.files = listOf(row("bb"), row("ccc"))
        device.syncCatalog()
        assertEquals(listOf("bb"), grid().map { it.id })
        assertEquals(2, grid().single().stackCount)

        // One photo left: no stack.
        device.files = listOf(row("ccc"))
        device.syncCatalog()
        assertEquals(listOf("ccc"), grid().map { it.id })
        assertNull(grid().single().stackCount)
        assertEquals(emptyList<StackMemberEntity>(), database.stackMemberDao().all(DEVICE_LIBRARY_ID))
    }

    @Test fun takingOutMakingTheCoverAndUndoing() = runBlocking {
        val device = Device(listOf(row("a"), row("bb"), row("ccc")))
        device.syncCatalog()
        device.stackTogether(listOf("a", "bb", "ccc"))

        device.makeStackCover("ccc")
        assertEquals(listOf("ccc"), grid().map { it.id })

        device.removeFromStack("a")
        assertEquals(setOf("a", "ccc"), grid().map { it.id }.toSet())
        assertEquals(2, grid().single { it.id == "ccc" }.stackCount)

        device.unstack("bb")
        assertEquals(setOf("a", "bb", "ccc"), grid().map { it.id }.toSet())
        assertNull(grid().firstOrNull { it.stackCount != null })
    }

    @Test fun stackingACoverBringsItsStackAlong() = runBlocking {
        val device = Device(listOf(row("a"), row("bb"), row("ccc")))
        device.syncCatalog()
        device.stackTogether(listOf("a", "bb"))

        device.stackTogether(listOf("ccc", "a"))

        assertEquals(listOf("ccc"), grid().map { it.id })
        assertEquals(3, grid().single().stackCount)
    }

    @Test fun anExportHidesOnlyWhileItsOriginalIsThere() = runBlocking {
        val moment = "2025-10-08T10:00:00.000Z"
        val original = row("a", "IMG 1.HEIC", createdAt = moment)
        val export = row("edit", "IMG_1_ImmichRoom.jpg", createdAt = moment)
        val device = Device(listOf(original, export))
        device.syncCatalog()
        assertEquals(listOf("a"), grid().map { it.id })

        // The original was deleted: the export is the only copy left, and shows.
        device.files = listOf(export)
        device.syncCatalog()
        assertEquals(listOf("edit"), grid().map { it.id })

        // It came back: the export hides again.
        device.files = listOf(original, export)
        device.syncCatalog()
        assertEquals(listOf("a"), grid().map { it.id })
    }

    @Test fun aNameLikeAnExportFromAnotherMomentIsAPhotoOfItsOwn() = runBlocking {
        val device = Device(
            listOf(
                row("a", "IMG_1.jpg", createdAt = "2025-10-08T10:00:00.000Z"),
                row("other", "IMG_1_ImmichRoom.jpg", createdAt = "2025-12-24T10:00:00.000Z"),
            ),
        )
        device.syncCatalog()

        assertEquals(setOf("a", "other"), grid().map { it.id }.toSet())
    }

    @Test fun anExportIsToldByTheNameItsOriginalWouldGiveIt() {
        val at = "2025-10-08T10:00:00.000Z"
        assertEquals(true, isExportOf("IMG_1_ImmichRoom.jpg", at, "IMG 1.HEIC", "2025-10-08T11:30:00Z"))
        assertEquals(false, isExportOf("IMG_1_ImmichRoom.jpg", at, "IMG_2.jpg", at))
        assertEquals(false, isExportOf("IMG_1_ImmichRoom.jpg", at, "IMG_1.jpg", "2025-10-10T10:00:00.000Z"))
        // A server row before its name is known says nothing.
        assertEquals(false, isExportOf("IMG_1_ImmichRoom.jpg", at, "", at))
    }

    @Test fun inTheUnifiedLibraryAPhotoUnderACoverDoesNotShowThroughItsServerCopy() = runBlocking {
        val device = Device(listOf(row("a", "one.jpg"), row("bb", "two.jpg")))
        device.syncCatalog()
        // Both are backed up to the server.
        database.assetDao().upsertAll(
            listOf(
                row("s1", "one.jpg", createdAt = "2025-10-01T10:00:00.000Z", library = SERVER),
                row("s2", "two.jpg", createdAt = "2025-10-02T10:00:00.000Z", library = SERVER),
            ),
        )

        device.stackTogether(listOf("a", "bb"))

        val source = database.assetDao().unifiedPagingSource(DEVICE_LIBRARY_ID, SERVER, false, false, null, null, null)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("a"), page.data.map { it.asset.id })
    }

    @Test fun settlingDropsWhatIsGoneAndUndoesStacksOfOne() {
        val members = listOf("a", "b", "c").map { StackMemberEntity(DEVICE_LIBRARY_ID, it, "s1", "a") } +
            listOf("x", "y").map { StackMemberEntity(DEVICE_LIBRARY_ID, it, "s2", "x") }

        val settled = settleLocalStacks(members, gone = setOf("a", "y"))

        assertEquals(listOf("b", "c"), settled.map { it.assetId })
        assertEquals(setOf("b"), settled.map { it.primaryAssetId }.toSet())
    }

    private companion object {
        const val SERVER = "server"
    }
}
