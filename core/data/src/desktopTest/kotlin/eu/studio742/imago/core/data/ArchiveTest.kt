package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.UserText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The archive: out of the timeline and what counts it, kept through a sync, and on both sides at once. */
class ArchiveTest {
    private val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @After fun tearDown() = database.close()

    private fun row(library: String, id: String, name: String, createdAt: String) =
        AssetEntity(library, id, "c-$id", name, createdAt, createdAt.dropLast(1), null, null,
            isFavorite = false, isEdited = false, hasLocalRecipe = false, type = "IMAGE")

    /** A device whose sync writes [rows] again, as a real one rewrites its catalogue. */
    private inner class Device(private val rows: List<AssetEntity>) : LocalCatalogLibrary(database) {
        override val accessRevision: StateFlow<Int> = MutableStateFlow(0)
        override fun accessSummary() = UserText(eu.studio742.imago.core.model.UserMessage.FOLDERS_NONE)
        override fun refreshAccess() = Unit
        override suspend fun syncCatalog() = replaceCatalog(rows)
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("unused")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = Unit
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit
    }

    private suspend fun timelineIds(): List<String> {
        val source = database.assetDao().pagingSource(DEVICE_LIBRARY_ID, false, false, null, null, null)
        return (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.id }
    }

    @Test
    fun theDevicesArchiveLeavesTheTimelineAndSurvivesASync() = runBlocking {
        val device = Device(
            listOf(
                row(DEVICE_LIBRARY_ID, "beach", "a.jpg", "2025-10-08T10:00:00.000Z"),
                row(DEVICE_LIBRARY_ID, "desk", "b.jpg", "2025-10-09T10:00:00.000Z"),
            ),
        )
        device.syncCatalog()

        device.setArchived(listOf("beach"), archived = true)
        device.syncCatalog()

        assertEquals(listOf("desk"), timelineIds())
        assertEquals(listOf(1), database.assetDao().timeBuckets(DEVICE_LIBRARY_ID).map { it.assetCount })
        assertEquals(0, database.assetDao().countNewerThan(DEVICE_LIBRARY_ID, false, false, null, null, null, "2025-10-09T12:00:00.000Z"))
        assertEquals(emptyList<Any>(), device.onThisDay(LocalDate.of(2026, 10, 8)))
        val archive = database.assetDao().archivedPagingSource(DEVICE_LIBRARY_ID).load(PagingSource.LoadParams.Refresh(null, 50, false))
        assertEquals(listOf("beach"), (archive as PagingSource.LoadResult.Page).data.map { it.id })

        device.setArchived(listOf("beach"), archived = false)
        device.syncCatalog()
        assertEquals(listOf("desk", "beach"), timelineIds())
    }

    @Test
    fun inTheUnifiedLibraryArchivingThePhonesCopyArchivesTheServersToo() = runBlocking {
        val phone = row(DEVICE_LIBRARY_ID, "p1", "20251008_100000.jpg", "2025-10-08T09:00:00.000Z")
        val device = Device(listOf(phone))
        device.syncCatalog()
        database.assetDao().upsertAll(listOf(row(SERVER, "s1", "20251008_100000.jpg", "2025-10-08T10:00:00.000Z")))
        val server = Server()

        UnifiedLibrary(database, device, server, SERVER).setArchived(listOf(eu.studio742.imago.core.model.AssetReference(DEVICE_LIBRARY_ID, "p1").encode()), true)

        assertEquals(listOf("s1"), server.archived)
        assertEquals(emptyList<String>(), timelineIds())
    }

    private class Server : LibraryRepository {
        val archived = mutableListOf<String>()
        override suspend fun setArchived(assetIds: List<String>, archived: Boolean) { this.archived += assetIds }
        override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?) = error("unused")
        override suspend fun albums() = emptyList<eu.studio742.imago.core.model.ImmichAlbum>()
        override suspend fun timeBuckets() = emptyList<eu.studio742.imago.core.model.ImmichTimeBucket>()
        override val catalogSync: StateFlow<CatalogSyncState> = MutableStateFlow(CatalogSyncState())
        override suspend fun syncCatalog() = Unit
        override suspend fun loadMonth(month: String) = Unit
        override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override suspend fun indexOfDate(date: LocalDate, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override fun thumbnailUrl(assetId: String) = ""
        override fun previewUrl(assetId: String) = ""
        override fun videoPlaybackUrl(assetId: String) = ""
        override fun apiKey(assetId: String) = ""
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("unused")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = Unit
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit
    }

    private companion object {
        const val SERVER = "home"
    }
}
