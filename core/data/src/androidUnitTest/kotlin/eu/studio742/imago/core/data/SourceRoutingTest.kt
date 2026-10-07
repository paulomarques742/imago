package eu.studio742.imago.core.data

import androidx.room.Room
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.immich.*
import eu.studio742.imago.core.model.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SourceRoutingTest {
    @Test fun suspendedDownloadAndFavoriteKeepTheirSourceAfterSelectionChanges() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var favoriteSource: String? = null
        val api = object : ImmichApi by OkHttpImmichApi(OkHttpClient()) {
            // Without this the real client would ask the network for the key's permissions.
            override suspend fun keyPermissions(connection: ImmichConnection) = setOf(eu.studio742.imago.core.immich.generated.ImmichKeyPermissions.ALL)
            override suspend fun validateConnection(connection: ImmichConnection) = ServerVersion(2,6,3)
            override suspend fun currentUserId(connection: ImmichConnection) = "user-${connection.apiKey}"
            override suspend fun setFavorite(connection: ImmichConnection, assetId: String, isFavorite: Boolean) {
                favoriteSource = connection.libraryId
                assertEquals("same-id", assetId)
            }
            override suspend fun downloadOriginal(connection: ImmichConnection, assetId: String, destination: File) {
                entered.complete(Unit); proceed.await()
                destination.writeText("${connection.libraryId}:$assetId")
            }
        }
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("router-test", 0)
        prefs.edit().clear().commit()
        val config = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        config.saveLibrary(null, "A", "https://a.test", "key-a")
        val first = config.libraries.value.last().id
        config.saveLibrary(null, "B", "https://b.test", "key-b")
        val second = config.libraries.value.last().id
        config.selectLibrary(first)
        val db = Room.inMemoryDatabaseBuilder(context, ImmichRoomDatabase::class.java).allowMainThreadQueries().build()
        val router = SourceLibraryRepository(config, db, api, DeviceLibraryRepository(context, db))
        val reference = AssetReference(first, "same-id").encode()
        val file = File(context.cacheDir, "routing-test.txt")
        try {
            val job = launch { router.downloadOriginal(reference, file) }
            entered.await(); config.selectLibrary(second); proceed.complete(Unit); job.join()
            assertEquals("$first:same-id", file.readText())
            router.setFavorite(reference, true)
            assertEquals(first, favoriteSource)
            assertEquals("key-a", router.apiKey(reference))
            config.removeLibrary(first)
            assertTrue(runCatching { router.downloadOriginal(reference, file) }.isFailure)
            assertEquals(second, config.selectedLibraryId.value)
        } finally { file.delete(); db.close() }
    }
}
