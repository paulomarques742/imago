package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.MediaTypeFilter
import eu.studio742.imago.core.model.isRawOriginal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Type chip: photos, videos and RAWs told apart in SQL, and the SQL agreeing with `isRawOriginal`. */
class MediaTypeFilterTest {
    private val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @After fun tearDown() = database.close()

    private fun row(id: String, name: String, type: String = "IMAGE", mime: String? = null, favorite: Boolean = false) =
        AssetEntity(DEVICE_LIBRARY_ID, id, "c-$id", name, "2025-10-08T10:0${id.last()}:00.000Z", "2025-10-08T10:0${id.last()}:00", null, null,
            isFavorite = favorite, isEdited = false, hasLocalRecipe = false, type = type, mimeType = mime)

    private val rows = listOf(
        row("a1", "IMG_0001.JPG", mime = "image/jpeg"),
        row("a2", "DSCF0002.RAF", mime = "application/octet-stream"),
        row("a3", "clip.mp4", type = "VIDEO", mime = "video/mp4"),
        row("a4", "no_mime.jpg", mime = null),
        row("a5", "IMG_0005.dng", mime = "image/x-adobe-dng", favorite = true),
        row("a6", "renamed", mime = "image/x-sony-arw"),
        row("a7", "shot.heic", mime = "image/heic", favorite = true),
        row("a8", "bitmap.bmp", mime = "image/x-ms-bmp"),
    )

    private suspend fun ids(type: MediaTypeFilter, favorites: Boolean = false): Set<String> {
        database.assetDao().upsertAll(rows)
        val source = database.assetDao().pagingSource(DEVICE_LIBRARY_ID, favorites, false, null, null, null, mediaType = type.ordinal)
        return (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.id }.toSet()
    }

    @Test
    fun `every type slices the grid, and a missing MIME type is a photo`() = runBlocking {
        assertEquals(rows.map { it.id }.toSet(), ids(MediaTypeFilter.ANY))
        assertEquals(setOf("a1", "a4", "a7", "a8"), ids(MediaTypeFilter.PHOTO))
        assertEquals(setOf("a3"), ids(MediaTypeFilter.VIDEO))
        assertEquals(setOf("a2", "a5", "a6"), ids(MediaTypeFilter.RAW))
    }

    @Test
    fun `a type combines with the other chips`() = runBlocking {
        assertEquals(setOf("a5"), ids(MediaTypeFilter.RAW, favorites = true))
        assertEquals(setOf("a7"), ids(MediaTypeFilter.PHOTO, favorites = true))
    }

    @Test
    fun `the SQL and the model agree on what a RAW is`() = runBlocking {
        val inSql = ids(MediaTypeFilter.RAW)
        rows.filter { it.type == "IMAGE" }.forEach { row ->
            assertEquals(row.originalFileName, isRawOriginal(row.mimeType, row.originalFileName), row.id in inSql)
        }
    }

    @Test
    fun `the counts behind the grid's position follow the type`() = runBlocking {
        database.assetDao().upsertAll(rows)
        // The newest row is a8; the videos and RAWs older than it are what a jump has to count.
        val raws = database.assetDao().countNewerThan(DEVICE_LIBRARY_ID, false, false, null, null, null, "2025-10-08T10:00:00.000Z", mediaType = MediaTypeFilter.RAW.ordinal)
        assertEquals(3, raws)
    }
}
