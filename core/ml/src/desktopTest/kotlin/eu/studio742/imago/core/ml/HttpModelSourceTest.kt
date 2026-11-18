package eu.studio742.imago.core.ml

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.diagnostics.DiagnosticAccess
import eu.studio742.imago.core.model.diagnostics.DiagnosticStore
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpModelSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private lateinit var models: File
    private lateinit var store: DiagnosticStore
    private val weights = ByteArray(100_000) { (it * 31).toByte() }

    @Before fun setUp() {
        models = temporary.newFolder("models")
        store = DiagnosticStore(temporary.newFolder("diagnostics"))
        LocalDiagnostics.start(store, emptyMap())
    }

    @After fun tearDown() {
        LocalDiagnostics.stop()
        server.shutdown()
    }

    private fun source() = HttpModelSource(models, server.url("/storage/v1/object/public/ml-models/").toString(), OkHttpClient())

    private fun manifest(file: String = "u2netp/1/u2netp.onnx", sha256: String = weights.sha256(), size: Long = weights.size.toLong()) =
        server.enqueue(MockResponse().setBody(
            """{"format":1,"models":[{"id":"u2netp","version":1,"file":"$file","sha256":"$sha256","size":$size}]}""",
        ))

    private fun weights(bytes: ByteArray = weights) = server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

    private fun failure(block: () -> Unit) {
        val error = assertThrows(UserMessageException::class.java) { block() }
        assertEquals(UserMessage.MASK_MODEL_DOWNLOAD_FAILED, error.userMessage)
    }

    private fun leftovers() = models.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun downloadsOnceThenReadsTheDevice() {
        manifest()
        weights()
        val progress = mutableListOf<Long>()
        val path = source().download(MaskModel.SUBJECT) { received, total -> progress += received; assertEquals(weights.size.toLong(), total) }

        assertEquals("/storage/v1/object/public/ml-models/manifest.json", server.takeRequest().path)
        assertEquals("/storage/v1/object/public/ml-models/u2netp/1/u2netp.onnx", server.takeRequest().path)
        assertArrayEquals(weights, File(path).readBytes())
        assertEquals(File(models, "u2netp/1/u2netp.onnx").path, path)
        assertEquals(weights.size.toLong(), progress.last())

        // A second source over the same folder finds the file and does not ask the network.
        assertEquals(path, source().localPath(MaskModel.SUBJECT))
        assertEquals(path, source().download(MaskModel.SUBJECT))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun refusesWeightsThatDoNotMatchTheManifest() {
        manifest()
        weights(weights.copyOf().also { it[10] = (it[10] + 1).toByte() })
        failure { source().download(MaskModel.SUBJECT) }
        assertNull(source().localPath(MaskModel.SUBJECT))
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun refusesMoreBytesThanTheManifestAnnounces() {
        manifest(size = 1_000)
        weights()
        failure { source().download(MaskModel.SUBJECT) }
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun refusesAManifestWithoutTheModelOrWithAPathOutOfTheBucket() {
        server.enqueue(MockResponse().setBody("""{"format":1,"models":[]}"""))
        failure { source().download(MaskModel.SUBJECT) }
        manifest(file = "u2netp/../../secret")
        failure { source().download(MaskModel.SUBJECT) }
        server.enqueue(MockResponse().setBody("not json"))
        failure { source().download(MaskModel.SUBJECT) }
        // Three manifests asked for, and never a file outside the bucket.
        assertEquals(3, server.requestCount)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun failuresReachTheDiagnosticsWithoutTheAddress() {
        server.enqueue(MockResponse().setResponseCode(404))
        failure { source().download(MaskModel.SUBJECT) }
        manifest()
        weights(ByteArray(10))
        failure { source().download(MaskModel.SUBJECT) }

        val report = store.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()).readText()
        assertTrue(report, report.contains("MASK_MODEL"))
        assertTrue(report, report.contains("HTTP_ERROR"))
        assertTrue(report, report.contains("404"))
        assertTrue(report, report.contains("INVALID_RESPONSE"))
        for (secret in listOf(server.hostName, "ml-models", "manifest.json", "u2netp.onnx")) {
            assertFalse("$secret in the report", report.contains(secret))
        }
    }
}

private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
