package eu.studio742.imago.core.sync

import eu.studio742.imago.core.interchange.RecipeLinkError
import eu.studio742.imago.core.interchange.RecipeLinkException
import eu.studio742.imago.core.model.diagnostics.DiagnosticAccess
import eu.studio742.imago.core.model.diagnostics.DiagnosticStore
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.sync.RecipeLinkBackendException.Reason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class RecipeLinkRepositoryTest {
    private val linkId = "SECRET_link_identifier_0123"

    private class FakeBackend(var failure: RecipeLinkBackendException? = null, var opened: String? = "{}") : RecipeLinkBackend {
        override val websiteUrl = "https://site.example"
        var created: Pair<String, String>? = null
        private fun check() { failure?.let { throw it } }
        override suspend fun create(recipeId: String, content: String): String { check(); created = recipeId to content; return "new" }
        override suspend fun update(id: String, content: String) = check()
        override suspend fun revoke(id: String) = check()
        override suspend fun mine(): List<RecipeLink> { check(); return listOf(RecipeLink("link", "recipe")) }
        override suspend fun open(id: String): String? { check(); return opened }
    }

    private suspend fun errorOf(block: suspend () -> Unit): RecipeLinkError {
        try {
            block()
        } catch (failure: RecipeLinkException) {
            return failure.error
        }
        fail("it should have failed")
        throw AssertionError()
    }

    @Test fun theLinksComeByRecipeAndTheFileGoesAsText() = runBlocking {
        val backend = FakeBackend()
        val links = RecipeLinkRepository(backend) { true }
        assertEquals(mapOf("recipe" to "link"), links.mine())
        assertEquals("new", links.create("r", "{\"a\":1}".encodeToByteArray()))
        assertEquals("r" to "{\"a\":1}", backend.created)
        assertEquals("https://site.example", links.baseUrl)
        assertTrue(links.signedIn)
    }

    @Test fun eachFailureBecomesItsOwnReason() = runBlocking {
        val backend = FakeBackend()
        val links = RecipeLinkRepository(backend) { true }
        mapOf(
            Reason.NO_ACCOUNT to RecipeLinkError.NO_ACCOUNT,
            Reason.LIMIT to RecipeLinkError.LIMIT,
            Reason.TOO_LARGE to RecipeLinkError.TOO_LARGE,
            Reason.NOT_FOUND to RecipeLinkError.GONE,
            Reason.OFFLINE to RecipeLinkError.OFFLINE,
            Reason.FAILED to RecipeLinkError.FAILED,
        ).forEach { (reason, error) ->
            backend.failure = RecipeLinkBackendException(reason)
            assertEquals(error, errorOf { links.revoke("id") })
        }
    }

    @Test fun aLinkWithNothingBehindItIsGone() = runBlocking {
        val links = RecipeLinkRepository(FakeBackend(opened = null)) { false }
        assertEquals(RecipeLinkError.GONE, errorOf { links.open("id") })
    }

    @Test fun aFailureIsInTheReportWithoutTheLink() = runBlocking {
        val directory = Files.createTempDirectory("recipe-link-diagnostics").toFile()
        val store = DiagnosticStore(directory)
        LocalDiagnostics.start(store, emptyMap())
        try {
            val links = RecipeLinkRepository(FakeBackend(RecipeLinkBackendException(Reason.FAILED, 500))) { true }
            errorOf { links.open(linkId) }
            errorOf { links.update(linkId, "{}".encodeToByteArray()) }
            errorOf { links.revoke(linkId) }

            val report = store.report(emptyMap(), "", DiagnosticAccess.UNKNOWN, emptyList()).readText()
            assertTrue(report, report.contains("RECIPE_LINK"))
            assertTrue(report, report.contains("500"))
            assertFalse(report, report.contains(linkId))
            assertFalse(report, report.contains("site.example"))
        } finally {
            LocalDiagnostics.stop()
            directory.deleteRecursively()
        }
    }
}
