package eu.studio742.imago.feature.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import eu.studio742.imago.core.interchange.RecipeLinkError
import eu.studio742.imago.core.interchange.RecipeLinkException
import eu.studio742.imago.core.interchange.RecipeLinkService
import eu.studio742.imago.core.interchange.ImagoFileContent
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.interchange.decodeImagoFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalTestApi::class)
class RecipeLinkDialogTest {
    private val systemLocale = Locale.getDefault()

    @Before
    fun englishTexts() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restoreLocale() = Locale.setDefault(systemLocale)

    private class FakeLinks(
        override val signedIn: Boolean = true,
        val existing: Map<String, String> = emptyMap(),
        var failure: RecipeLinkError? = null,
    ) : RecipeLinkService {
        override val baseUrl = "https://site.example"
        var created: Pair<String, ByteArray>? = null
        var updated: String? = null
        var revoked: String? = null
        private fun check() { failure?.let { throw RecipeLinkException(it) } }
        override suspend fun mine(): Map<String, String> { check(); return existing }
        override suspend fun create(recipeId: String, content: ByteArray): String { check(); created = recipeId to content; return "new-link" }
        override suspend fun update(linkId: String, content: ByteArray) { check(); updated = linkId }
        override suspend fun revoke(linkId: String) { check(); revoked = linkId }
        override suspend fun open(linkId: String): ByteArray { check(); return ByteArray(0) }
    }

    private val recipe = SavedRecipe(
        id = "recipe-1",
        name = "Warm",
        collection = "Travel",
        recipe = EditRecipe(assetId = "a", originalChecksum = "c", createdAt = "t", updatedAt = "t"),
        createdAt = "t",
        updatedAt = "t",
        parts = setOf(RecipePart.VIBRANCE),
    )

    private fun controller(links: FakeLinks) = RecipeLinkController(links, CoroutineScope(Dispatchers.Unconfined))

    private val content = "{}".encodeToByteArray()

    @Test
    fun `without an account the link is offered, and creating it asks to sign in`() {
        val links = FakeLinks(signedIn = false)
        val controller = controller(links)

        controller.open(recipe)
        assertNull(controller.url)
        assertFalse(controller.readyToPublish())
        assertTrue(controller.askingAccount)
    }

    @Test
    fun `with an account an existing link is found, under the site's address`() {
        val controller = controller(FakeLinks(existing = mapOf("recipe-1" to "abc")))

        controller.open(recipe)

        assertEquals("https://site.example/r/abc", controller.url)
        assertTrue(controller.readyToPublish())
    }

    @Test
    fun `publishing without a link creates one`() {
        val links = FakeLinks()
        val controller = controller(links)

        controller.open(recipe)
        controller.publish(content)

        assertEquals("recipe-1", links.created?.first)
        assertEquals("https://site.example/r/new-link", controller.url)
        assertFalse(controller.updated)
    }

    @Test
    fun `publishing with a link updates it and keeps the address`() {
        val links = FakeLinks(existing = mapOf("recipe-1" to "abc"))
        val controller = controller(links)

        controller.open(recipe)
        controller.publish(content)

        assertEquals("abc", links.updated)
        assertNull(links.created)
        assertEquals("https://site.example/r/abc", controller.url)
        assertTrue(controller.updated)
    }

    @Test
    fun `stopping revokes the link and asks first`() {
        val links = FakeLinks(existing = mapOf("recipe-1" to "abc"))
        val controller = controller(links)
        controller.open(recipe)

        controller.askToStop()
        assertTrue(controller.confirmingStop)
        assertNull(links.revoked)

        controller.stop()
        assertEquals("abc", links.revoked)
        assertNull(controller.url)
        assertFalse(controller.confirmingStop)
    }

    @Test
    fun `reaching the limit is said and creates nothing`() {
        val links = FakeLinks()
        val controller = controller(links)
        controller.open(recipe)

        links.failure = RecipeLinkError.LIMIT
        controller.publish(content)

        assertEquals(RecipeLinkError.LIMIT, controller.error)
        assertNull(controller.url)
    }

    @Test
    fun `a failure looking for the link does not stop one being created`() {
        val links = FakeLinks(failure = RecipeLinkError.OFFLINE)
        val controller = controller(links)

        controller.open(recipe)
        assertEquals(RecipeLinkError.OFFLINE, controller.error)

        links.failure = null
        controller.publish(content)
        assertNotNull(controller.url)
        assertNull(controller.error)
    }

    @Test
    fun `an imported recipe asks about rights before a link too`() = runBlocking {
        val published = CompletableDeferred<ByteArray>()
        val exporter = RecipeExporter(
            RecipeFileActions(share = { _, _ -> }, saveAs = { _, _ -> }, open = {}),
            CoroutineScope(Dispatchers.Unconfined),
            { "IMAGO recipes" },
            {},
            { published.complete(it) },
        )

        exporter.start(listOf(recipe.copy(origin = RecipeOrigin.XMP)), RecipeDelivery.LINK)
        assertNotNull(exporter.askingRights)
        assertFalse(published.isCompleted)

        exporter.confirmRights()
        val file = withTimeout(5_000) { published.await() }
        assertEquals(1, (decodeImagoFile(file) as ImagoFileContent.Recipes).entries.size)
    }

    @Test
    fun `a recipe made in the app goes straight to the link`() = runBlocking {
        val published = CompletableDeferred<ByteArray>()
        val exporter = RecipeExporter(
            RecipeFileActions(share = { _, _ -> }, saveAs = { _, _ -> }, open = {}),
            CoroutineScope(Dispatchers.Unconfined),
            { "IMAGO recipes" },
            {},
            { published.complete(it) },
        )

        exporter.start(listOf(recipe), RecipeDelivery.LINK)

        withTimeout(5_000) { published.await() }
        assertNull(exporter.askingRights)
    }

    @Test
    fun `a failed open reaches the import as a link error, and a good one as bytes`() = runBlocking {
        assertEquals(RecipeLinkError.GONE, readRecipeLink(FakeLinks(failure = RecipeLinkError.GONE), "abc").linkError)
        assertNull(readRecipeLink(FakeLinks(failure = RecipeLinkError.OFFLINE), "abc").bytes)
        assertNotNull(readRecipeLink(FakeLinks(), "abc").bytes)
    }

    @Test
    fun `the dialog shows the link, its code and the way to send the file`() = runComposeUiTest {
        val controller = controller(FakeLinks(existing = mapOf("recipe-1" to "abc")))
        controller.open(recipe)
        var sentFile = false

        setContent { RecipeLinkDialog(controller, onPublish = {}, onSendFile = { sentFile = true }, onSendLink = {}, onOpenAccount = {}) }

        onNodeWithText("https://site.example/r/abc").assertExists()
        onNodeWithContentDescription("Copy link").assertExists()
        onNodeWithText("Save image").assertExists()
        onNodeWithText("Update link").assertExists()
        onNodeWithText("Stop sharing").assertExists()
        onNodeWithText("Send as a file").performClick()
        assertTrue(sentFile)
        assertNull(controller.target)
    }

    @Test
    fun `the dialog without a link offers to create one and says what it does`() = runComposeUiTest {
        val controller = controller(FakeLinks())
        controller.open(recipe)
        var published = false

        setContent { RecipeLinkDialog(controller, onPublish = { published = true }, onSendFile = null, onSendLink = null, onOpenAccount = {}) }

        onNodeWithText("Create link").performClick()
        assertTrue(published)
    }
}
