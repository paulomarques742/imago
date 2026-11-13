package eu.studio742.imago.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import eu.studio742.imago.core.interchange.RecipeGalleryError
import eu.studio742.imago.core.interchange.RecipeGalleryException
import eu.studio742.imago.core.interchange.RecipeGalleryLimits
import eu.studio742.imago.core.interchange.RecipeGalleryService
import eu.studio742.imago.core.interchange.RecipeLinkService
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalTestApi::class)
class RecipeGalleryPublishTest {
    private val systemLocale = Locale.getDefault()

    @Before
    fun englishTexts() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restoreLocale() = Locale.setDefault(systemLocale)

    private class FakeGallery(
        val supporter: Boolean = true,
        val published: Set<String> = emptySet(),
        var failure: RecipeGalleryError? = null,
        val url: String? = "https://site.example/gallery/",
    ) : RecipeGalleryService {
        var sent: List<Any>? = null
        override fun galleryUrl(portuguese: Boolean) = url
        override suspend fun isSupporter() = supporter
        override suspend fun publishedLinks() = published
        override suspend fun publish(linkId: String, title: String, authorName: String, before: ByteArray, after: ByteArray) {
            failure?.let { throw RecipeGalleryException(it) }
            sent = listOf(linkId, title, authorName, before.size, after.size)
        }
    }

    private class FakeLinks(val existing: Map<String, String>) : RecipeLinkService {
        override val baseUrl = "https://site.example"
        override val signedIn = true
        override suspend fun mine() = existing
        override suspend fun create(recipeId: String, content: ByteArray) = "new-link"
        override suspend fun update(linkId: String, content: ByteArray) = Unit
        override suspend fun revoke(linkId: String) = Unit
        override suspend fun open(linkId: String) = ByteArray(0)
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

    private fun gallery(service: FakeGallery) = RecipeGalleryController(service, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `a supporter is asked which links are already in the gallery`() {
        val controller = gallery(FakeGallery(published = setOf("abc")))
        controller.check()
        assertTrue(controller.supporter)
        assertEquals(setOf("abc"), controller.published)
    }

    @Test
    fun `someone who is not a supporter is not even asked about the gallery`() {
        val controller = gallery(FakeGallery(supporter = false, published = setOf("abc")))
        controller.check()
        assertFalse(controller.supporter)
        assertEquals(emptySet<String>(), controller.published)
    }

    @Test
    fun `a build without a gallery asks nothing`() {
        val controller = gallery(FakeGallery(url = null))
        controller.check()
        assertFalse(controller.supporter)
    }

    @Test
    fun `publishing sends the link, the texts and both photos, and marks the link published`() {
        val service = FakeGallery()
        val controller = gallery(service)
        controller.check()
        controller.start()
        controller.publish("abc", "Warm", "Ana", byteArrayOf(1, 2), byteArrayOf(3))
        assertEquals(listOf("abc", "Warm", "Ana", 2, 1), service.sent)
        assertTrue(controller.done)
        assertFalse(controller.working)
        assertTrue("abc" in controller.published)
    }

    @Test
    fun `a refusal says why, and one already published counts as published`() {
        val service = FakeGallery(failure = RecipeGalleryError.IMPORTED)
        val controller = gallery(service)
        controller.publish("abc", "Warm", "Ana", byteArrayOf(1), byteArrayOf(1))
        assertEquals(RecipeGalleryError.IMPORTED, controller.error)
        assertFalse(controller.done)

        service.failure = RecipeGalleryError.ALREADY_PUBLISHED
        controller.publish("abc", "Warm", "Ana", byteArrayOf(1), byteArrayOf(1))
        assertTrue("abc" in controller.published)
    }

    @Test
    fun `a photo is fitted to the gallery's side, keeping its shape, and a small one is left alone`() {
        val big = ImageBitmap(4000, 3000).fittedTo(RecipeGalleryLimits.PHOTO_SIDE)
        assertEquals(1600, big.width)
        assertEquals(1200, big.height)
        val small = ImageBitmap(800, 600)
        assertTrue(small.fittedTo(RecipeGalleryLimits.PHOTO_SIDE) === small)
    }

    @Test
    fun `a photo is encoded as a JPEG under the bucket's limit`() {
        val bytes = ImageBitmap(1600, 1200).encodeForGallery()
        assertTrue(bytes.size <= RecipeGalleryLimits.PHOTO_MAX_BYTES)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    private fun linkController() = RecipeLinkController(FakeLinks(mapOf("recipe-1" to "abcdefghijklmnop")), CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `a supporter with a link is offered the gallery`() = runComposeUiTest {
        val links = linkController().apply { open(recipe) }
        setContent {
            CompositionLocalProvider(LocalRecipeGallery provides FakeGallery()) {
                RecipeLinkDialog(links, onPublish = {}, onSendFile = null, onSendLink = null, onOpenAccount = {})
            }
        }
        onNodeWithText("Publish to the gallery").performClick()
        onNodeWithText("Sample photo").assertExists()
        onNodeWithText("Your photo").assertExists()
        onNodeWithText("Your name, as it shows").assertExists()
    }

    @Test
    fun `a recipe already in the gallery is shown as published, with the way to see it`() = runComposeUiTest {
        val links = linkController().apply { open(recipe) }
        setContent {
            CompositionLocalProvider(LocalRecipeGallery provides FakeGallery(published = setOf("abcdefghijklmnop"))) {
                RecipeLinkDialog(links, onPublish = {}, onSendFile = null, onSendLink = null, onOpenAccount = {})
            }
        }
        onNodeWithText("This recipe is in the community gallery.").assertExists()
        onNodeWithText("See the gallery").assertExists()
        onNodeWithText("Publish to the gallery").assertDoesNotExist()
    }

    @Test
    fun `to anyone who is not a supporter the gallery is not there`() = runComposeUiTest {
        val links = linkController().apply { open(recipe) }
        setContent {
            CompositionLocalProvider(LocalRecipeGallery provides FakeGallery(supporter = false)) {
                RecipeLinkDialog(links, onPublish = {}, onSendFile = null, onSendLink = null, onOpenAccount = {})
            }
        }
        onNodeWithText("Update link").assertExists()
        onNodeWithText("Publish to the gallery").assertDoesNotExist()
    }
}
