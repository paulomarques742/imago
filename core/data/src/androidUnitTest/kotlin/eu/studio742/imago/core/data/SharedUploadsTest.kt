package eu.studio742.imago.core.data

import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.ImmichApiException
import eu.studio742.imago.core.immich.ImmichUploadResult
import eu.studio742.imago.core.immich.OkHttpImmichApi
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.ServerVersion
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SharedUploadsTest {
    private lateinit var root: File
    private val sent = mutableListOf<String>()
    private val answers = mutableMapOf<String, () -> ImmichUploadResult>()

    private val api = object : ImmichApi by OkHttpImmichApi(OkHttpClient()) {
        // Without this the real client would ask the network for the key's permissions.
        override suspend fun keyPermissions(connection: ImmichConnection) = setOf(eu.studio742.imago.core.immich.generated.ImmichKeyPermissions.ALL)
        override suspend fun validateConnection(connection: ImmichConnection) = ServerVersion(3, 1, 0)
        override suspend fun currentUserId(connection: ImmichConnection) = "user-${connection.apiKey}"
        override suspend fun uploadAsset(
            connection: ImmichConnection,
            file: File,
            fileName: String,
            mimeType: String,
            fileCreatedAt: String,
        ): ImmichUploadResult {
            val answer = answers[fileName] ?: { ImmichUploadResult("id-$fileName", "created") }
            val result = answer()
            sent += "${connection.libraryId}:$fileName:$mimeType:${file.readText()}"
            return result
        }
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("imago-shared").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun sendsEveryFileToTheChosenLibraryAndCountsDuplicates() = runBlocking {
        val (config, first, second) = twoLibraries()
        answers["b.mp4"] = { ImmichUploadResult("existing", "duplicate") }
        val queue = queue(second, "a.jpg" to "image/jpeg", "b.mp4" to "video/mp4")
        val progress = mutableListOf<Pair<Int, Int>>()

        val run = SharedMediaUploader(config, api).upload(queue) { done, total -> progress += done to total }

        assertEquals(SharedUploadRun.Finished(SharedUploadSummary(uploaded = 1, duplicates = 1, failed = 0)), run)
        assertEquals(listOf("$second:a.jpg:image/jpeg:a.jpg", "$second:b.mp4:video/mp4:b.mp4"), sent)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        // The copies go as they go up; the manifest stays until the job discards it.
        assertFalse(queue.fileOf(queue.batch.items[0]).exists())
        assertTrue(File(queue.directory, SharedUploadQueue.MANIFEST).exists())
        assertTrue(first != second)
    }

    @Test fun withoutNetworkStopsAndTheNextRunSendsOnlyWhatIsLeft() = runBlocking {
        val (config, library) = oneLibrary()
        var offline = true
        answers["b.jpg"] = {
            if (offline) throw ImmichApiException.Connection(IOException("offline"))
            ImmichUploadResult("id-b", "created")
        }
        val queue = queue(library, "a.jpg" to "image/jpeg", "b.jpg" to "image/jpeg", "c.jpg" to "image/jpeg")

        val interrupted = SharedMediaUploader(config, api).upload(queue)
        assertTrue(interrupted is SharedUploadRun.RetryLater)
        assertEquals(listOf("b.jpg", "c.jpg"), queue.pending.map { it.value.fileName })

        // O trabalho repetido abre a fila do disco, como o WorkManager faria noutro processo.
        offline = false
        sent.clear()
        val reopened = checkNotNull(SharedUploadQueue.open(queue.directory))
        val resumed = SharedMediaUploader(config, api).upload(reopened)

        assertEquals(SharedUploadRun.Finished(SharedUploadSummary(3, 0, 0)), resumed)
        assertEquals(listOf("b.jpg", "c.jpg"), sent.map { it.split(':')[1] })
    }

    @Test fun aFileTheServerRefusesIsCountedAndTheOthersStillGo() = runBlocking {
        val (config, library) = oneLibrary()
        answers["a.xyz"] = { throw ImmichApiException.Server(400, "unsupported file type") }
        val queue = queue(library, "a.xyz" to "application/octet-stream", "b.jpg" to "image/jpeg")

        val run = SharedMediaUploader(config, api).upload(queue)

        assertEquals(SharedUploadRun.Finished(SharedUploadSummary(uploaded = 1, duplicates = 0, failed = 1)), run)
    }

    @Test fun aServerErrorIsWorthRetrying() = runBlocking {
        val (config, library) = oneLibrary()
        answers["a.jpg"] = { throw ImmichApiException.Server(503, "") }

        val run = SharedMediaUploader(config, api).upload(queue(library, "a.jpg" to "image/jpeg"))

        assertTrue(run is SharedUploadRun.RetryLater)
    }

    @Test fun aRejectedKeyStopsTheWholeUpload() = runBlocking {
        val (config, library) = oneLibrary()
        answers["a.jpg"] = { throw ImmichApiException.Authentication() }
        val queue = queue(library, "a.jpg" to "image/jpeg", "b.jpg" to "image/jpeg")

        val run = SharedMediaUploader(config, api).upload(queue)

        assertTrue(run is SharedUploadRun.Stopped)
        assertEquals(2, queue.pending.size)
    }

    /** Retrying would not grant the permission: it stops at once, saying which one the key lacks. */
    @Test fun aKeyWithoutUploadStopsNamingThePermission() = runBlocking {
        val (config, library) = oneLibrary()
        answers["a.jpg"] = { throw ImmichApiException.MissingPermission(listOf("asset.upload")) }
        val queue = queue(library, "a.jpg" to "image/jpeg", "b.jpg" to "image/jpeg")

        val run = SharedMediaUploader(config, api).upload(queue)

        val cause = (run as SharedUploadRun.Stopped).cause as UserMessageException
        assertEquals(UserMessage.IMMICH_PERMISSION_MISSING, cause.userMessage)
        assertEquals(listOf("asset.upload"), cause.args)
        assertEquals(2, queue.pending.size)
    }

    @Test fun aLibraryRemovedMeanwhileStopsWithItsOwnMessage() = runBlocking {
        val (config, library) = oneLibrary()
        val queue = queue(library, "a.jpg" to "image/jpeg")
        config.removeLibrary(library)

        val run = SharedMediaUploader(config, api).upload(queue)

        val cause = (run as SharedUploadRun.Stopped).cause as UserMessageException
        assertEquals(UserMessage.LIBRARY_DISCONNECTED, cause.userMessage)
        assertTrue(sent.isEmpty())
    }

    @Test fun givingUpCountsWhatIsLeftAsFailed() = runBlocking {
        val (config, library) = oneLibrary()
        answers["b.jpg"] = { throw ImmichApiException.Connection(IOException("offline")) }
        val queue = queue(library, "a.jpg" to "image/jpeg", "b.jpg" to "image/jpeg", "c.jpg" to "image/jpeg")
        SharedMediaUploader(config, api).upload(queue)

        queue.giveUp()

        assertEquals(SharedUploadSummary(uploaded = 1, duplicates = 0, failed = 2), queue.summary)
        assertTrue(queue.pending.isEmpty())
    }

    @Test fun aFolderWithoutManifestCannotBeReopened() {
        val directory = SharedUploadStaging(root).newDirectory()
        File(directory, "0").writeText("half copied")

        assertNull(SharedUploadQueue.open(directory))
    }

    @Test fun onlyOldCopiesThatNeverBecameAnUploadAreCleaned() {
        val staging = SharedUploadStaging(root)
        val abandoned = staging.newDirectory().apply { setLastModified(0) }
        val recent = staging.newDirectory()
        val queued = staging.newDirectory()
        SharedUploadQueue.create(queued, SharedUploadBatch("library", emptyList()))
        queued.setLastModified(0)

        staging.cleanAbandoned()

        assertFalse(abandoned.exists())
        assertTrue(recent.exists())
        assertTrue(queued.exists())
    }

    private fun queue(libraryId: String, vararg files: Pair<String, String>): SharedUploadQueue {
        val directory = SharedUploadStaging(root).newDirectory()
        val items = files.mapIndexed { index, (name, mime) ->
            File(directory, index.toString()).writeText(name)
            SharedUploadItem(index.toString(), name, mime, "2026-09-25T10:00:00Z")
        }
        return SharedUploadQueue.create(directory, SharedUploadBatch(libraryId, items))
    }

    private suspend fun oneLibrary(): Pair<EncryptedConfigurationRepository, String> {
        val config = configuration()
        config.saveLibrary(null, "Casa", "https://casa.test", "key-a")
        return config to config.libraries.value.last().id
    }

    private suspend fun twoLibraries(): Triple<EncryptedConfigurationRepository, String, String> {
        val config = configuration()
        config.saveLibrary(null, "Casa", "https://casa.test", "key-a")
        val first = config.libraries.value.last().id
        config.saveLibrary(null, "Trabalho", "https://trabalho.test", "key-b")
        return Triple(config, first, config.libraries.value.last().id)
    }

    private fun configuration(): EncryptedConfigurationRepository {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("shared-uploads-test", 0)
        prefs.edit().clear().commit()
        return EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
    }
}
