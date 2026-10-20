package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private const val NOW = "2026-10-18T10:00:00Z"
private const val ASSET = "imago:bGlicmFyeQ:YXNzZXQ"

/**
 * A photo reaches the composition with the look the library shows it with, whether it goes in as an
 * element or as the background, and keeps it through saving and reopening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerPhotoRecipeTest {
    private val dispatcher = StandardTestDispatcher()
    private val edited = EditRecipe(
        assetId = ASSET,
        originalChecksum = "sum",
        createdAt = NOW,
        updatedAt = NOW,
        tone = Tone(exposure = 1.2f),
    )
    private val media = ComposerMedia(id = ASSET, checksum = "sum", fileName = "a.jpg")
    private val projects = InMemoryProjects(
        CompositionProject(
            id = "p",
            name = "P",
            format = PageFormatPreset.STORY_9_16.format,
            pages = listOf(CompositionPage(id = "page-0", index = 0)),
            createdAt = NOW,
            updatedAt = NOW,
        ),
    )
    private val recipes = object : RecipeRepository {
        override suspend fun get(assetId: String) = edited.takeIf { assetId == ASSET }
        override suspend fun save(recipe: EditRecipe) = Unit
    }
    private val viewModel by lazy {
        val fakes = FakeComposerRepos
        ComposerEditorViewModel(projects, fakes.templates, fakes.media, recipes, fakes.exports, fakes.brandKits)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun stored() = projects.get("p")

    @Test
    fun `an edited photo keeps its recipe as an element, through saving and reopening`() = runTest(dispatcher) {
        viewModel.open("p"); advanceUntilIdle()
        viewModel.addMedia(listOf(media)); advanceUntilIdle()
        viewModel.saveNow(); advanceUntilIdle()
        viewModel.open("p"); advanceUntilIdle()

        val photo = stored().elements.filterIsInstance<CompositionElement.Photo>().single()
        assertEquals(edited, photo.recipe.recipe)
    }

    @Test
    fun `an edited photo chosen from the library as the background keeps its recipe`() = runTest(dispatcher) {
        viewModel.open("p"); advanceUntilIdle()
        viewModel.setPhotoBackground(media, currentPageOnly = false); advanceUntilIdle()
        viewModel.saveNow(); advanceUntilIdle()

        val background = stored().background as CompositionBackground.Photo
        assertEquals(edited, background.recipe?.recipe)
    }

    @Test
    fun `a photo element made the background takes its recipe along`() = runTest(dispatcher) {
        viewModel.open("p"); advanceUntilIdle()
        viewModel.addMedia(listOf(media)); advanceUntilIdle()
        val applied = edited.copy(updatedAt = "2026-10-18T10:05:00Z", tone = Tone(exposure = -.5f))
        viewModel.updateSelectedPhotoRecipe(applied)

        viewModel.useSelectedPhotoAsBackground(forCurrentPage = true)
        viewModel.saveNow(); advanceUntilIdle()

        val background = stored().pages.single().backgroundOverride as CompositionBackground.Photo
        assertEquals(applied, background.recipe?.recipe)
    }

    private class InMemoryProjects(vararg initial: CompositionProject) : CompositionRepository {
        private val stored = initial.associateBy { it.id }.toMutableMap()

        override fun observeAll(): Flow<List<CompositionProject>> = flowOf(emptyList())
        override suspend fun get(id: String): CompositionProject = stored.getValue(id)
        override suspend fun save(project: CompositionProject) { stored[project.id] = project }
        override suspend fun delete(id: String) { stored.remove(id) }
        override suspend fun duplicate(id: String, name: suspend (original: String) -> String): CompositionProject = error("not used")
    }
}
