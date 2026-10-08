package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.MapContents
import eu.studio742.imago.core.model.MapMarker
import eu.studio742.imago.core.model.MapReading
import eu.studio742.imago.core.model.UserText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Where the photos were taken: read once from the files, and joined with a server's. */
class MapContentsTest {
    private val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @After fun tearDown() = database.close()

    private fun row(library: String, id: String, name: String, createdAt: String, checksum: String = "c-$id", type: String = "IMAGE") =
        AssetEntity(library, id, checksum, name, createdAt, createdAt.dropLast(1), null, null,
            isFavorite = false, isEdited = false, hasLocalRecipe = false, type = type)

    /** The catalogue as it is; the places come from [places], and every read is counted. */
    private inner class Device(private val places: Map<String, Pair<Double, Double>?>, var allowed: Boolean = true) : LocalCatalogLibrary(database) {
        val reads = mutableListOf<String>()
        override fun canReadLocations() = allowed
        override suspend fun readLocation(assetId: String): Pair<Double, Double>? {
            reads += assetId
            if (assetId == "broken") error("unreadable")
            return places[assetId]
        }
        override val accessRevision: StateFlow<Int> = MutableStateFlow(0)
        override fun accessSummary() = UserText(eu.studio742.imago.core.model.UserMessage.FOLDERS_NONE)
        override fun refreshAccess() = Unit
        override suspend fun syncCatalog() = Unit
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("unused")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = Unit
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit
    }

    @Test
    fun theDevicesPlacesAreReadOnceAndAgainOnlyWhenThePhotoChanges() = runBlocking {
        database.assetDao().upsertAll(
            listOf(
                row(DEVICE_LIBRARY_ID, "beach", "a.jpg", "2026-10-01T10:00:00.000Z"),
                row(DEVICE_LIBRARY_ID, "desk", "b.jpg", "2026-10-02T10:00:00.000Z"),
                row(DEVICE_LIBRARY_ID, "broken", "c.jpg", "2026-10-03T10:00:00.000Z"),
                // An EXIF of zeros is a camera that wrote none.
                row(DEVICE_LIBRARY_ID, "zero", "d.jpg", "2026-10-04T10:00:00.000Z"),
                row(DEVICE_LIBRARY_ID, "clip", "e.mp4", "2026-10-05T10:00:00.000Z", type = "VIDEO"),
            ),
        )
        val device = Device(mapOf("beach" to (38.7 to -9.1), "desk" to null, "zero" to (0.0 to 0.0)))

        val first = device.mapContents().toList()

        assertEquals(MapReading(0, 4), first.first().reading)
        assertEquals(MapContents(listOf(MapMarker("beach", 38.7, -9.1))), first.last())
        assertEquals(setOf("beach", "desk", "broken", "zero"), device.reads.toSet())

        device.reads.clear()
        assertEquals(listOf(MapMarker("beach", 38.7, -9.1)), device.mapContents().last().markers)
        assertTrue("nothing is read twice", device.reads.isEmpty())

        database.assetDao().upsertAll(listOf(row(DEVICE_LIBRARY_ID, "desk", "b.jpg", "2026-10-02T10:00:00.000Z", checksum = "edited")))
        device.mapContents().last()
        assertEquals(listOf("desk"), device.reads)
    }

    @Test
    fun withoutTheLocationPermissionNothingIsReadAndTheMapSaysSo() = runBlocking {
        database.assetDao().upsertAll(listOf(row(DEVICE_LIBRARY_ID, "beach", "a.jpg", "2026-10-01T10:00:00.000Z")))
        val device = Device(mapOf("beach" to (38.7 to -9.1)), allowed = false)

        val contents = device.mapContents().toList()

        assertEquals(listOf(MapContents(emptyList(), needsLocationAccess = true)), contents)
        assertTrue(device.reads.isEmpty())
    }

    @Test
    fun theUnifiedMapShowsAPhotoOnBothSidesOnceAsThePhones() = runBlocking {
        database.assetDao().upsertAll(
            listOf(
                row(DEVICE_LIBRARY_ID, "p1", "20261003_180641.jpg", "2026-10-03T17:06:41.000Z"),
                row(SERVER, "s1", "20261003_180641.jpg", "2026-10-03T18:06:41.000Z"),
                row(SERVER, "s2", "IMG_0001.JPG", "2026-10-02T10:00:00.000Z"),
            ),
        )
        val device = Device(mapOf("p1" to (41.1 to -8.6)))
        val server = Server(listOf(MapMarker("s1", 41.1, -8.6, "Porto"), MapMarker("s2", 37.0, -7.9, "Faro")))

        val markers = UnifiedLibrary(database, device, server, SERVER).mapContents().last().markers

        assertEquals(
            listOf(AssetReference(DEVICE_LIBRARY_ID, "p1").encode(), AssetReference(SERVER, "s2").encode()),
            markers.map { it.assetId },
        )
    }

    @Test
    fun aPlaceListsItsPhotosFromBothLibrariesNewestFirst() = runBlocking {
        database.assetDao().upsertAll(
            listOf(
                row(DEVICE_LIBRARY_ID, "p1", "a.jpg", "2026-10-01T10:00:00.000Z"),
                row(SERVER, "s1", "b.jpg", "2026-10-03T10:00:00.000Z"),
                row(SERVER, "s2", "c.jpg", "2026-10-02T10:00:00.000Z"),
            ),
        )
        val ids = listOf(AssetReference(DEVICE_LIBRARY_ID, "p1"), AssetReference(SERVER, "s2"), AssetReference(SERVER, "s1"), AssetReference(SERVER, "gone"))
            .map { it.encode() }

        val source = CatalogListPagingSource(database, ids)
        val first = source.load(PagingSource.LoadParams.Refresh(null, 2, false)) as PagingSource.LoadResult.Page
        val second = source.load(PagingSource.LoadParams.Append(first.nextKey!!, 2, false)) as PagingSource.LoadResult.Page

        assertEquals(listOf("s1", "s2"), first.data.map { AssetReference.parse(it.id).localId })
        assertEquals(listOf("p1"), second.data.map { AssetReference.parse(it.id).localId })
        assertEquals(null, second.nextKey)
    }

    /** A server that only knows where its photos are. */
    private class Server(private val markers: List<MapMarker>) : LibraryRepository {
        override fun mapContents(): Flow<MapContents> = flowOf(MapContents(markers))
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
