package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.UserText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The albums left out of "All": out of it, "On this day" and the unified library, and nothing else. */
class HiddenAlbumsTest {
    private val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @After fun tearDown() = database.close()

    private fun row(library: String, id: String, name: String, createdAt: String, folder: String? = null, favorite: Boolean = false) =
        AssetEntity(library, id, "c-$id", name, createdAt, createdAt.dropLast(1), null, null,
            isFavorite = favorite, isEdited = false, hasLocalRecipe = false, type = "IMAGE", folderId = folder, folderName = folder)

    private inner class Device(private val rows: List<AssetEntity>) : LocalCatalogLibrary(database) {
        override val accessRevision: StateFlow<Int> = MutableStateFlow(0)
        override fun accessSummary() = UserText(eu.studio742.imago.core.model.UserMessage.FOLDERS_NONE)
        override fun refreshAccess() = Unit
        override suspend fun syncCatalog() = replaceCatalog(rows)
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("unused")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = Unit
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit

        /** Renaming moves the files to a folder with another id, as on both platforms. */
        suspend fun rename(from: String, to: String) = renamingKeepsHidden(from) {
            database.assetDao().upsertAll(rows.filter { it.folderId == from }.map { it.copy(folderId = to, folderName = to) })
            to
        }
    }

    private val camera = row(DEVICE_LIBRARY_ID, "camera", "IMG_1.jpg", "2025-10-08T10:00:00.000Z", folder = "Camera", favorite = true)
    private val screenshot = row(DEVICE_LIBRARY_ID, "shot", "Screenshot_1.png", "2025-10-08T11:00:00.000Z", folder = "Screenshots", favorite = true)

    private suspend fun ids(hideFolders: Boolean, favorites: Boolean = false, albumId: String? = null): List<String> {
        val source = database.assetDao().pagingSource(DEVICE_LIBRARY_ID, favorites, false, null, null, null, albumId, hideFolders)
        return (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.id }
    }

    @Test
    fun aHiddenAlbumLeavesAllAndOnThisDayButNotTheOtherChipsNorItself() = runBlocking {
        val device = Device(listOf(camera, screenshot))
        device.syncCatalog()

        device.setHiddenAlbums(setOf("Screenshots"))
        device.syncCatalog()

        assertEquals(setOf("Screenshots"), device.hiddenAlbums().first())
        assertEquals(listOf("camera"), ids(hideFolders = true))
        assertEquals(listOf("shot", "camera"), ids(hideFolders = false, favorites = true))
        assertEquals(listOf("shot"), ids(hideFolders = true, albumId = "Screenshots"))
        assertEquals(listOf(1), database.assetDao().timeBuckets(DEVICE_LIBRARY_ID).map { it.assetCount })
        assertEquals(0, database.assetDao().countNewerThan(DEVICE_LIBRARY_ID, false, false, null, null, null, "2025-10-08T10:30:00.000Z", hideFolders = true))
        assertEquals(1, database.assetDao().countNewerThan(DEVICE_LIBRARY_ID, false, false, null, null, null, "2025-10-08T10:30:00.000Z"))
        assertEquals(listOf(listOf("camera")), device.onThisDay(LocalDate.of(2026, 10, 8)).map { it.assetIds })

        device.setHiddenAlbums(emptySet())
        assertEquals(listOf("shot", "camera"), ids(hideFolders = true))
    }

    @Test
    fun aRenamedAlbumStaysHiddenUnlessItJoinsOneThatShows() = runBlocking {
        val device = Device(listOf(camera, screenshot))
        device.syncCatalog()
        device.setHiddenAlbums(setOf("Screenshots"))

        device.rename("Screenshots", "Prints")
        assertEquals(setOf("Prints"), device.hiddenAlbums().first())

        device.rename("Prints", "Camera")
        assertEquals(setOf("Prints"), device.hiddenAlbums().first())
    }

    @Test
    fun inTheUnifiedLibraryTheServersCopyOfAHiddenPhotoStaysOutToo() = runBlocking {
        val device = Device(listOf(camera, screenshot))
        device.syncCatalog()
        database.assetDao().upsertAll(listOf(row(SERVER, "s-shot", "Screenshot_1.png", "2025-10-08T11:00:00.000Z")))
        device.setHiddenAlbums(setOf("Screenshots"))

        fun unified(hideFolders: Boolean) = runBlocking {
            val source = database.assetDao().unifiedPagingSource(DEVICE_LIBRARY_ID, SERVER, false, false, null, null, null, hideFolders)
            (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.asset.id }
        }

        assertEquals(listOf("camera"), unified(hideFolders = true))
        assertEquals(listOf("shot", "camera"), unified(hideFolders = false))
        assertEquals(listOf(1), database.assetDao().unifiedTimeBuckets(DEVICE_LIBRARY_ID, SERVER).map { it.assetCount })
    }

    private companion object {
        const val SERVER = "home"
    }
}
