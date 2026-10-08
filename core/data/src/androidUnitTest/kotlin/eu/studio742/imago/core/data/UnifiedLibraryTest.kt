package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.UnifiedAssetRow
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnifiedLibraryTest {
    private lateinit var database: ImmichRoomDatabase

    private fun row(library: String, id: String, name: String, createdAt: String) = AssetEntity(
        library, id, "local:$id", name, createdAt, createdAt.dropLast(1), null, null,
        isFavorite = false, isEdited = false, hasLocalRecipe = false, type = "IMAGE",
    )

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        database.assetDao().upsertAll(
            listOf(
                // Taken on the phone and uploaded by the Immich app: one photo, two copies.
                row(DEVICE_LIBRARY_ID, "p1", "20261003_180641.jpg", "2026-10-03T17:06:41.000Z"),
                // The server read the date in another time zone: still the same photo.
                row(SERVER, "s1", "20261003_180641.jpg", "2026-10-03T18:06:41.000Z"),
                // Only on the phone.
                row(DEVICE_LIBRARY_ID, "p2", "20261004_090000.jpg", "2026-10-04T08:00:00.000Z"),
                // Only on the server.
                row(SERVER, "s2", "IMG_0001.JPG", "2026-10-02T10:00:00.000Z"),
                // The same name years apart is another photo.
                row(SERVER, "s3", "20261004_090000.jpg", "2019-10-04T08:00:00.000Z"),
            ),
        )
    }

    @After fun tearDown() = database.close()

    private suspend fun timeline(): List<UnifiedAssetRow> {
        val source = database.assetDao().unifiedPagingSource(DEVICE_LIBRARY_ID, SERVER, false, false, null, null, null)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        return page.data
    }

    @Test fun aPhotoOnBothSidesShowsOnceAsThePhonesCopyMarkedAsOnTheServer() = runBlocking {
        val rows = timeline()

        assertEquals(listOf("p2", "p1", "s2", "s3"), rows.map { it.asset.id })
        assertEquals(listOf(false, true, true, true), rows.map { it.alsoOnServer })
    }

    @Test fun theCountsAndTheMonthsAreTheTimelinesOwn() = runBlocking {
        assertEquals(
            1,
            database.assetDao().unifiedCountBefore(DEVICE_LIBRARY_ID, SERVER, false, false, null, null, null, "2026-10-03T17:06:41.000Z", "p1"),
        )
        assertEquals(
            listOf("2026-10-01" to 3, "2019-10-01" to 1),
            database.assetDao().unifiedTimeBuckets(DEVICE_LIBRARY_ID, SERVER).map { it.month to it.assetCount },
        )
    }

    @Test fun eachCopyFindsTheOther() = runBlocking {
        assertEquals("s1", database.assetDao().counterpart(DEVICE_LIBRARY_ID, "p1", SERVER))
        assertEquals("p1", database.assetDao().counterpart(SERVER, "s1", DEVICE_LIBRARY_ID))
        assertNull(database.assetDao().counterpart(DEVICE_LIBRARY_ID, "p2", SERVER))
    }

    private companion object {
        const val SERVER = "home"
    }
}
