package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import eu.studio742.imago.core.data.db.*
import eu.studio742.imago.core.model.*
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceLibraryRepositoryTest {
    @Test fun indexesPhotoVideoFoldersDatesAndRetainsEditsAfterLosingAccess() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val provider = TestMediaProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        val database = Room.inMemoryDatabaseBuilder(context, ImmichRoomDatabase::class.java).allowMainThreadQueries().build()
        val repo = DeviceLibraryRepository(context, database)
        try {
            repo.syncCatalog()
            val rows = database.assetDao().allAssets(DEVICE_LIBRARY_ID)
            assertEquals(2, rows.size)
            assertEquals(setOf("IMAGE", "VIDEO"), rows.map { it.type }.toSet())
            assertEquals("2026-09-08", rows.first().fileCreatedAt.take(10))
            assertEquals(1, repo.albums().size)
            assertEquals(2, repo.albums().single().assetCount)
            // The camera's folder is the system's: shown, never changed from here.
            assertTrue(repo.albums().single().isFolder)
            assertFalse(repo.albums().single().canEditContent)
            assertEquals(3000L, rows.first { it.type == "VIDEO" }.durationMs)
            val photo = rows.first { it.type == "IMAGE" }
            database.recipeDao().upsert(RecipeEntity(DEVICE_LIBRARY_ID, photo.id, "{}", "2026-09-08"))
            repo.syncCatalog()
            assertTrue(database.assetDao().asset(DEVICE_LIBRARY_ID, photo.id)!!.hasLocalRecipe)
            assertEquals(0, repo.indexOfDate(LocalDate.parse("2026-09-08"), LibraryFilter.ALL, null, null))
            provider.denyImages = true
            repo.syncCatalog()
            assertEquals(listOf("VIDEO"), database.assetDao().allAssets(DEVICE_LIBRARY_ID).map { it.type })
            assertNotNull(database.recipeDao().get(DEVICE_LIBRARY_ID, photo.id))
            provider.denyImages = false
            repo.syncCatalog()
            assertTrue(database.assetDao().asset(DEVICE_LIBRARY_ID, photo.id)!!.hasLocalRecipe)
        } finally { database.close() }
    }
    @Test fun distinguishesDeniedPartialAndFullAccess() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, ImmichRoomDatabase::class.java).build()
        val repo = DeviceLibraryRepository(context, database)
        try {
            val app = shadowOf(context)
            app.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            assertEquals(UserMessage.DEVICE_ACCESS_NONE, repo.accessSummary().message)
            app.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            assertEquals(UserMessage.DEVICE_ACCESS_SELECTED, repo.accessSummary().message)
            app.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            assertEquals(UserMessage.DEVICE_ACCESS_FULL, repo.accessSummary().message)
        } finally { database.close() }
    }
}

private class TestMediaProvider : ContentProvider() {
    var denyImages = false
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val video = uri.toString().contains("video")
        if (!video && denyImages) throw SecurityException("Access revoked")
        val values = mapOf<String, Any>("_id" to if (video) 2L else 1L, "_display_name" to if (video) "clip.mp4" else "photo.jpg",
            MediaStore.Images.ImageColumns.DATE_TAKEN to Instant.parse("2026-09-08T12:00:00Z").toEpochMilli(),
            "date_added" to 0L, "date_modified" to 10L, "_size" to 1024L, "width" to 100L, "height" to 200L,
            "mime_type" to if (video) "video/mp4" else "image/jpeg", "is_favorite" to 1L, "bucket_id" to 42L,
            "bucket_display_name" to "Camera", "volume_name" to "external_primary", "duration" to 3000L,
            MediaStore.MediaColumns.RELATIVE_PATH to "DCIM/Camera/")
        val columns = checkNotNull(projection)
        require(columns.all { it in values }) { "Unknown MediaStore column" }
        return MatrixCursor(columns).apply { addRow(columns.map { values[it] }.toTypedArray()) }
    }
    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
