package eu.studio742.imago.core.data

import androidx.room.Room
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AssetDaoFoldersTest {
    private fun row(id: String, folder: String, createdAt: String) = AssetEntity(
        DEVICE_LIBRARY_ID, id, "local:$id", "$id.jpg", createdAt, createdAt.dropLast(1), null, null,
        isFavorite = false, isEdited = false, hasLocalRecipe = false, type = "IMAGE",
        folderId = folder, folderName = folder.substringAfter(':'),
    )

    @Test fun countsEachFolderWithItsNewestPhotoAsTheCoverWithoutLoadingTheRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.assetDao().upsertAll(
                listOf(
                    row("a", "v:Trip", "2026-05-02T10:00:00.000Z"),
                    row("b", "v:Trip", "2026-05-09T10:00:00.000Z"),
                    row("c", "v:Trip", "2026-05-01T10:00:00.000Z"),
                    row("d", "v:Camera", "2026-09-30T10:00:00.000Z"),
                ),
            )

            val folders = database.assetDao().folders(DEVICE_LIBRARY_ID)
            val starts = database.assetDao().folderStarts(DEVICE_LIBRARY_ID).associate { it.folderId to it.startDate }

            assertEquals(listOf("v:Camera", "v:Trip"), folders.map { it.folderId })
            val trip = folders.single { it.folderId == "v:Trip" }
            assertEquals("b", trip.coverId)
            assertEquals(3, trip.assetCount)
            assertEquals("2026-05-09T10:00:00.000Z", trip.endDate)
            assertEquals("2026-05-01T10:00:00.000Z", starts["v:Trip"])
            assertEquals(listOf("a", "b", "c"), database.assetDao().idsInFolder(DEVICE_LIBRARY_ID, "v:Trip").sorted())
        } finally {
            database.close()
        }
    }
}
