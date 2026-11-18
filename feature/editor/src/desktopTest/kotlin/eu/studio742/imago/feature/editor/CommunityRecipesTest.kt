package eu.studio742.imago.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import eu.studio742.imago.core.interchange.CommunityRecipe
import eu.studio742.imago.core.interchange.CommunitySort
import eu.studio742.imago.core.interchange.RecipeExport
import eu.studio742.imago.core.interchange.RecipeGalleryError
import eu.studio742.imago.core.interchange.RecipeGalleryException
import eu.studio742.imago.core.interchange.RecipeGalleryService
import eu.studio742.imago.core.interchange.RecipeLinkError
import eu.studio742.imago.core.interchange.RecipeLinkException
import eu.studio742.imago.core.interchange.RecipeLinkService
import eu.studio742.imago.core.interchange.encodeImagoFile
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalTestApi::class)
class CommunityRecipesTest {
    private val systemLocale = Locale.getDefault()

    @Before
    fun englishTexts() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restoreLocale() = Locale.setDefault(systemLocale)

    private val authored = SavedRecipe(
        id = "author-recipe",
        name = "Golden hour",
        collection = "Ana's",
        recipe = EditRecipe(assetId = "", originalChecksum = "", createdAt = "t", updatedAt = "t", presence = Presence(vibrance = 30f)),
        createdAt = "t",
        updatedAt = "t",
        parts = setOf(RecipePart.VIBRANCE),
    )

    private val post = CommunityRecipe(
        id = "post-1", title = "Golden", authorName = "Ana", linkId = "abcdefghijklmnop",
        beforeUrl = "https://img/a.jpg", afterUrl = "https://img/b.jpg", likes = 2, liked = false, mine = false,
    )

    private class FakeGallery(
        val page: List<CommunityRecipe>,
        val supporter: Boolean = true,
        var likeFailure: RecipeGalleryError? = null,
        val url: String? = "https://site.example/gallery/",
    ) : RecipeGalleryService {
        val likes = mutableListOf<Pair<String, Boolean>>()
        override fun galleryUrl(portuguese: Boolean) = url
        override suspend fun isSupporter() = supporter
        override suspend fun publishedLinks() = emptySet<String>()
        override suspend fun publish(linkId: String, title: String, authorName: String, before: ByteArray, after: ByteArray) = Unit
        override suspend fun communityRecipes(sort: CommunitySort, offset: Int) = if (offset == 0) page else emptyList()
        override suspend fun like(postId: String, like: Boolean): Int {
            likeFailure?.let { throw RecipeGalleryException(it) }
            likes += postId to like
            return 7
        }
    }

    private class FakeLinks(val content: ByteArray?) : RecipeLinkService {
        override val baseUrl = "https://site.example"
        override val signedIn = false
        override suspend fun mine() = emptyMap<String, String>()
        override suspend fun create(recipeId: String, content: ByteArray) = "new"
        override suspend fun update(linkId: String, content: ByteArray) = Unit
        override suspend fun revoke(linkId: String) = Unit
        override suspend fun open(linkId: String) = content ?: throw RecipeLinkException(RecipeLinkError.GONE)
    }

    private fun controller(gallery: FakeGallery, content: ByteArray? = encodeImagoFile(listOf(RecipeExport(authored, authored.appliedParts)))) =
        CommunityRecipesController(gallery, FakeLinks(content), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun `anyone copies a community recipe into their own, marked as the community's`() {
        val community = controller(FakeGallery(listOf(post), supporter = false))
        community.start()
        assertEquals(listOf(post), community.recipes)
        community.open(post)
        assertEquals(30f, community.openedRecipe!!.recipe.presence.vibrance)

        val saved = mutableListOf<SavedRecipe>()
        val mine = listOf(authored.copy(id = "x", name = "Golden by Ana"))
        community.copy("Golden by Ana", "Community", mine) { saved += it }

        val copy = saved.single()
        assertEquals("Golden by Ana (2)", copy.name)
        assertEquals("Community", copy.collection)
        assertEquals(RecipeOrigin.COMMUNITY, copy.origin)
        assertNotEquals(authored.id, copy.id)
        assertEquals(30f, copy.recipe.presence.vibrance)
        assertTrue(post.id in community.copied)

        // Pressed again, nothing is copied twice.
        community.copy("Golden by Ana", "Community", mine) { saved += it }
        assertEquals(1, saved.size)
    }

    @Test
    fun `a link that is gone opens to nothing and cannot be copied`() {
        val community = controller(FakeGallery(listOf(post)), content = null)
        community.start()
        community.open(post)
        assertTrue(community.openFailed)
        assertNull(community.openedRecipe)
        var saved = false
        community.copy("n", "c", emptyList()) { saved = true }
        assertFalse(saved)
    }

    @Test
    fun `a supporter's like shows at once and takes the server's count`() {
        val gallery = FakeGallery(listOf(post))
        val community = controller(gallery)
        community.start()
        community.toggleLike(community.recipes.single())
        assertEquals(listOf("post-1" to true), gallery.likes)
        assertTrue(community.recipes.single().liked)
        assertEquals(7, community.recipes.single().likes)
    }

    @Test
    fun `a refused like is undone, and someone who is not a supporter cannot like`() {
        val gallery = FakeGallery(listOf(post), likeFailure = RecipeGalleryError.NOT_SUPPORTER)
        val community = controller(gallery)
        community.start()
        community.toggleLike(community.recipes.single())
        assertEquals(post, community.recipes.single())
        assertEquals(RecipeGalleryError.NOT_SUPPORTER, community.likeError)

        val outsider = FakeGallery(listOf(post), supporter = false)
        controller(outsider).apply { start(); toggleLike(recipes.single()) }
        assertTrue(outsider.likes.isEmpty())
    }

    @Test
    fun `the library has a community tab only where the gallery exists`() = runComposeUiTest {
        val community = controller(FakeGallery(listOf(post)))
        var withGallery by androidx.compose.runtime.mutableStateOf(true)
        setContent {
            RecipeLibraryScreen(
                saved = emptyList(), builtIn = emptyList(), isLoading = false, assetId = null,
                onBack = {}, onSelect = {}, onToggleFavorite = {}, onRename = {}, onDelete = {}, onCreate = null,
                community = if (withGallery) { modifier -> CommunityRecipesTab(community, modifier) } else null,
            )
        }
        onAllNodesWithText("Community").assertCountEquals(1)
        withGallery = false
        waitForIdle()
        onAllNodesWithText("Community").assertCountEquals(0)
    }
}
