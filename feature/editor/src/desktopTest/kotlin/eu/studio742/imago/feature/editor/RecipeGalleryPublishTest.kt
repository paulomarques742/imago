package eu.studio742.imago.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import eu.studio742.imago.core.interchange.RecipeGalleryError
import eu.studio742.imago.core.interchange.RecipeGalleryException
import eu.studio742.imago.core.interchange.RecipeGalleryLimits
import eu.studio742.imago.core.interchange.RecipeGalleryService
import eu.studio742.imago.core.interchange.RecipeLinkImages
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
        // The choice of photo is also on the link's own dialog, behind this one.
        onAllNodesWithText("Sample photo").assertCountEquals(2)
        onAllNodesWithText("Your photo").assertCountEquals(2)
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
        onNodeWithText("Show before and after on the page").assertDoesNotExist()
    }

    @Test
    fun `the card takes a photo of the persons own from a supporter, and only the samples from anyone else`() {
        runComposeUiTest {
            val links = linkController().apply { open(recipe) }
            setContent {
                CompositionLocalProvider(LocalRecipeGallery provides FakeGallery(supporter = true)) {
                    RecipeLinkDialog(links, onPublish = {}, onSendFile = null, onSendLink = null, onOpenAccount = {})
                }
            }
            onNodeWithText("Your photo").assertExists()
        }
        runComposeUiTest {
            val links = linkController().apply { open(recipe) }
            setContent {
                CompositionLocalProvider(LocalRecipeGallery provides FakeGallery(supporter = false)) {
                    RecipeLinkDialog(links, onPublish = {}, onSendFile = null, onSendLink = null, onOpenAccount = {})
                }
            }
            onNodeWithText("Your photo").assertDoesNotExist()
        }
    }

    @Test
    fun `a supporter can put the before and the after on the links page, and take them off`() = runComposeUiTest {
        val links = linkController().apply { open(recipe) }
        val service = ImageGallery()
        setContent {
            CompositionLocalProvider(LocalRecipeGallery provides service) {
                RecipeLinkDialog(links, onPublish = {}, onSendFile = null, onSendLink = null, onOpenAccount = {})
            }
        }
        waitUntil(timeoutMillis = 15_000) { runCatching { onNodeWithText("Show before and after on the page").assertIsEnabled() }.isSuccess }
        onNodeWithText("Show before and after on the page").performClick()
        waitUntil(timeoutMillis = 15_000) { service.set != null }
        assertTrue(service.set!!.first > 0 && service.set!!.second > 0)
        waitUntil(timeoutMillis = 15_000) { runCatching { onNodeWithText("Remove the images from the page").assertExists() }.isSuccess }
        onNodeWithText("Remove the images from the page").performClick()
        waitUntil(timeoutMillis = 15_000) { service.cleared }
    }

    @Test
    fun `the page images hidden after reports are announced`() = runComposeUiTest {
        val links = linkController().apply { open(recipe) }
        setContent {
            CompositionLocalProvider(LocalRecipeGallery provides ImageGallery(RecipeLinkImages.HIDDEN)) {
                RecipeLinkDialog(links, onPublish = {}, onSendFile = null, onSendLink = null, onOpenAccount = {})
            }
        }
        waitUntil(timeoutMillis = 15_000) { runCatching { onNodeWithText("were hidden after reports", substring = true).assertExists() }.isSuccess }
    }

    private class ImageGallery(var state: RecipeLinkImages = RecipeLinkImages.NONE) : RecipeGalleryService {
        var set: Pair<Int, Int>? = null
        var cleared = false
        override fun galleryUrl(portuguese: Boolean) = "https://site.example/gallery/"
        override suspend fun isSupporter() = true
        override suspend fun publishedLinks() = emptySet<String>()
        override suspend fun publish(linkId: String, title: String, authorName: String, before: ByteArray, after: ByteArray) = Unit
        override suspend fun linkImages(linkId: String) = state
        override suspend fun setLinkImages(linkId: String, before: ByteArray, after: ByteArray) {
            set = before.size to after.size
            state = RecipeLinkImages.SHOWN
        }
        override suspend fun clearLinkImages(linkId: String) {
            cleared = true
            state = RecipeLinkImages.NONE
        }
    }

    @Test
    fun ownPhotoComesFromTheLibraryPickerAndIsHandedOverOnceLoaded() {
        val loaded = ImageBitmap(4, 3)
        var received: ImageBitmap? = null
        var asked: String? = null
        val picker = GalleryPhotoPicker(
            CoroutineScope(Dispatchers.Unconfined),
            load = { asset -> asked = asset.id; loaded },
            onPicked = { { photo -> received = photo } },
        )
        picker()
        assertTrue(picker.open)
        picker.choose(libraryPhoto("photo-1"))
        assertFalse(picker.open)
        assertFalse(picker.loading)
        assertEquals("photo-1", asked)
        assertTrue(received === loaded)
    }

    @Test
    fun cancellingTheLibraryPickerHandsNothingOver() {
        var called = false
        val picker = GalleryPhotoPicker(CoroutineScope(Dispatchers.Unconfined), load = { null }, onPicked = { { called = true } })
        picker()
        picker.cancel()
        assertFalse(picker.open)
        assertFalse(called)
    }

    private fun libraryPhoto(id: String) = eu.studio742.imago.feature.library.AssetUiModel(
        id = id, checksum = "", fileName = "$id.jpg", date = "", fileCreatedAt = "", width = 4, height = 3,
        isFavorite = false, isEdited = false, hasLocalRecipe = false, thumbnailUrl = "", previewUrl = "", apiKey = "",
    )
}
