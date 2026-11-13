package eu.studio742.imago.core.sync

import eu.studio742.imago.core.model.diagnostics.DiagnosticAccess
import eu.studio742.imago.core.model.diagnostics.DiagnosticStore
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SupporterRepositoryTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val token = "SECRET_purchase_token_0123"

    private class FakeGallery(var failure: GalleryBackendException? = null) : GalleryBackend {
        override val websiteUrl = "https://site.example"
        override suspend fun supporter(): SupporterState { failure?.let { throw it }; return SupporterState(true, "2026-12-11T10:00:00Z") }
        override suspend fun publishedLinks(): Set<String> = emptySet()
        override suspend fun publish(linkId: String, title: String, authorName: String, before: GalleryImage, after: GalleryImage) = "post"
    }

    private class FakePurchases(var failure: PlayPurchaseException? = null) : PlayPurchaseBackend {
        var sent: String? = null
        override suspend fun record(purchaseToken: String) { failure?.let { throw it }; sent = purchaseToken }
    }

    private fun repository(gallery: GalleryBackend? = FakeGallery(), purchases: PlayPurchaseBackend? = FakePurchases(), open: Boolean = true, signedIn: Boolean = true) =
        SupporterRepository(gallery, purchases, open) { if (signedIn) user else null }

    @Test fun theProgrammeShowsOnlyWhenOpenAndWithABackend() {
        assertTrue(repository().available)
        assertFalse(repository(open = false).available)
        assertFalse(repository(gallery = null).available)
    }

    @Test fun theProgrammesPageFollowsTheLanguage() {
        assertEquals("https://site.example/supporters/", repository().programmeUrl(portuguese = false))
        assertEquals("https://site.example/pt/apoiantes/", repository().programmeUrl(portuguese = true))
    }

    @Test fun theStateComesFromTheServerAndIsNothingWithoutAnAccount() = runBlocking {
        assertEquals(SupporterState(true, "2026-12-11T10:00:00Z"), repository().state())
        assertNull(repository(signedIn = false).state())
        assertNull(repository(gallery = FakeGallery(GalleryBackendException(GalleryBackendException.Reason.OFFLINE))).state())
    }

    @Test fun thePurchaseCarriesTheHashOfTheAccount() {
        assertEquals(playAccountHash(user), repository().accountHash())
        assertNull(repository(signedIn = false).accountHash())
    }

    @Test fun aPurchaseIsRecordedOrSaysWhyNot() = runBlocking {
        val purchases = FakePurchases()
        assertEquals(PlayPurchaseResult.RECORDED, repository(purchases = purchases).recordPlayPurchase(token))
        assertEquals(token, purchases.sent)
        mapOf(
            PlayPurchaseException.Reason.OTHER_ACCOUNT to PlayPurchaseResult.OTHER_ACCOUNT,
            PlayPurchaseException.Reason.NOT_PAID to PlayPurchaseResult.NOT_PAID,
            PlayPurchaseException.Reason.UNAVAILABLE to PlayPurchaseResult.FAILED,
            PlayPurchaseException.Reason.OFFLINE to PlayPurchaseResult.FAILED,
        ).forEach { (reason, result) ->
            assertEquals(reason.name, result, repository(purchases = FakePurchases(PlayPurchaseException(reason))).recordPlayPurchase(token))
        }
        assertEquals(PlayPurchaseResult.FAILED, repository(purchases = null).recordPlayPurchase(token))
    }

    @Test fun aFailureIsInTheReportWithoutTheTokenOrTheAccount() = runBlocking {
        val directory = Files.createTempDirectory("supporter-diagnostics").toFile()
        val store = DiagnosticStore(directory)
        LocalDiagnostics.start(store, emptyMap())
        try {
            repository(purchases = FakePurchases(PlayPurchaseException(PlayPurchaseException.Reason.FAILED, 500))).recordPlayPurchase(token)
            repository(purchases = FakePurchases(PlayPurchaseException(PlayPurchaseException.Reason.OTHER_ACCOUNT, 409))).recordPlayPurchase(token)
            repository(gallery = FakeGallery(GalleryBackendException(GalleryBackendException.Reason.OFFLINE))).state()

            val report = store.report(emptyMap(), "", DiagnosticAccess.UNKNOWN, emptyList()).readText()
            assertTrue(report, report.contains("SUPPORTER"))
            assertTrue(report, report.contains("500"))
            assertFalse(report, report.contains("409"))
            assertFalse(report, report.contains(token))
            assertFalse(report, report.contains(user))
            assertFalse(report, report.contains(playAccountHash(user)))
            assertFalse(report, report.contains("site.example"))
        } finally {
            LocalDiagnostics.stop()
            directory.deleteRecursively()
        }
    }
}
