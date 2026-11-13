package eu.studio742.imago.core.sync

import eu.studio742.imago.core.interchange.RecipeGalleryError
import eu.studio742.imago.core.interchange.RecipeGalleryException
import eu.studio742.imago.core.model.diagnostics.DiagnosticAccess
import eu.studio742.imago.core.model.diagnostics.DiagnosticStore
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.sync.GalleryBackendException.Reason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class GalleryRepositoryTest {
    private val linkId = "SECRET_link_identifier_0123"
    private val photo = byteArrayOf(1, 2, 3)

    private class FakeBackend(var failure: GalleryBackendException? = null, var active: Boolean = true) : GalleryBackend {
        override val websiteUrl = "https://site.example"
        var published: List<Any>? = null
        private fun check() { failure?.let { throw it } }
        override suspend fun supporter(): SupporterState { check(); return SupporterState(active, null) }
        override suspend fun publishedLinks(): Set<String> { check(); return setOf("published") }
        override suspend fun publish(linkId: String, title: String, authorName: String, before: GalleryImage, after: GalleryImage): String {
            check()
            published = listOf(linkId, title, authorName, before.bytes.size, after.bytes.size)
            return "post"
        }
    }

    private suspend fun errorOf(block: suspend () -> Unit): RecipeGalleryError {
        try {
            block()
        } catch (failure: RecipeGalleryException) {
            return failure.error
        }
        fail("it should have failed")
        throw AssertionError()
    }

    @Test fun aSupporterPublishesWithTrimmedTexts() = runBlocking {
        val backend = FakeBackend()
        val gallery = GalleryRepository(backend) { true }
        assertTrue(gallery.isSupporter())
        assertEquals(setOf("published"), gallery.publishedLinks())
        gallery.publish(linkId, "  Golden  ", " Ana ", photo, photo)
        assertEquals(listOf(linkId, "Golden", "Ana", 3, 3), backend.published)
        assertEquals("https://site.example/pt/galeria/", gallery.galleryUrl(portuguese = true))
        assertEquals("https://site.example/gallery/", gallery.galleryUrl(portuguese = false))
    }

    @Test fun withoutAnAccountNothingIsAsked() = runBlocking {
        val backend = FakeBackend(failure = GalleryBackendException(Reason.FAILED))
        val gallery = GalleryRepository(backend) { false }
        assertFalse(gallery.isSupporter())
        assertEquals(emptySet<String>(), gallery.publishedLinks())
    }

    @Test fun onAnyDoubtTheAccountIsNotASupporter() = runBlocking {
        for (reason in listOf(Reason.OFFLINE, Reason.FAILED, Reason.NO_ACCOUNT)) {
            val gallery = GalleryRepository(FakeBackend(failure = GalleryBackendException(reason))) { true }
            assertFalse(reason.name, gallery.isSupporter())
            assertEquals(reason.name, emptySet<String>(), gallery.publishedLinks())
        }
        assertFalse(GalleryRepository(FakeBackend(active = false)) { true }.isSupporter())
    }

    @Test fun eachRefusalBecomesItsOwnReason() = runBlocking {
        val backend = FakeBackend()
        val gallery = GalleryRepository(backend) { true }
        mapOf(
            Reason.NO_ACCOUNT to RecipeGalleryError.NOT_SUPPORTER,
            Reason.NOT_SUPPORTER to RecipeGalleryError.NOT_SUPPORTER,
            Reason.IMPORTED to RecipeGalleryError.IMPORTED,
            Reason.ALREADY_PUBLISHED to RecipeGalleryError.ALREADY_PUBLISHED,
            Reason.INVALID to RecipeGalleryError.INVALID,
            Reason.LIMIT to RecipeGalleryError.LIMIT,
            Reason.LINK_NOT_FOUND to RecipeGalleryError.LINK_GONE,
            Reason.IMAGE_UPLOAD to RecipeGalleryError.IMAGE_UPLOAD,
            Reason.OFFLINE to RecipeGalleryError.OFFLINE,
            Reason.FAILED to RecipeGalleryError.FAILED,
        ).forEach { (reason, error) ->
            backend.failure = GalleryBackendException(reason)
            assertEquals(error, errorOf { gallery.publish(linkId, "Title", "Ana", photo, photo) })
        }
    }

    @Test fun aPhotoTheBucketWouldRefuseIsRefusedHere() = runBlocking {
        val gallery = GalleryRepository(FakeBackend()) { true }
        assertEquals(RecipeGalleryError.INVALID, errorOf { gallery.publish(linkId, "Title", "Ana", ByteArray(0), photo) })
        assertEquals(RecipeGalleryError.INVALID, errorOf { gallery.publish(linkId, "Title", "Ana", photo, ByteArray(1_000_001)) })
    }

    @Test fun aFailureIsInTheReportWithoutTheLinkOrTheServerAndARefusalIsNot() = runBlocking {
        val directory = Files.createTempDirectory("gallery-diagnostics").toFile()
        val store = DiagnosticStore(directory)
        LocalDiagnostics.start(store, emptyMap())
        try {
            errorOf { GalleryRepository(FakeBackend(GalleryBackendException(Reason.FAILED, 500))) { true }.publish(linkId, "My title", "Ana", photo, photo) }
            GalleryRepository(FakeBackend(GalleryBackendException(Reason.OFFLINE))) { true }.isSupporter()
            errorOf { GalleryRepository(FakeBackend(GalleryBackendException(Reason.IMPORTED, 403))) { true }.publish(linkId, "My title", "Ana", photo, photo) }

            val report = store.report(emptyMap(), "", DiagnosticAccess.UNKNOWN, emptyList()).readText()
            assertTrue(report, report.contains("GALLERY"))
            assertTrue(report, report.contains("500"))
            assertFalse(report, report.contains("403"))
            assertFalse(report, report.contains(linkId))
            assertFalse(report, report.contains("site.example"))
            assertFalse(report, report.contains("My title"))
        } finally {
            LocalDiagnostics.stop()
            directory.deleteRecursively()
        }
    }
}
