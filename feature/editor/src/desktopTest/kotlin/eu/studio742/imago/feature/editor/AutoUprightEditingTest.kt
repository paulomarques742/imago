package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.UPRIGHT_FULL
import eu.studio742.imago.core.model.UPRIGHT_GUIDED
import eu.studio742.imago.core.model.UPRIGHT_OFF
import eu.studio742.imago.core.model.UPRIGHT_VERTICAL
import eu.studio742.imago.core.model.UprightGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** The automatic Upright modes through the real view model, over a sample photo. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoUprightEditingTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${T::class.simpleName}.${method.name} is not used by the automatic Upright")
    } as T

    private val saved = SavedRecipe(
        id = "plain",
        name = "Plain",
        collection = "General",
        recipe = EditRecipe(
            assetId = "imago:library:photo",
            originalChecksum = "checksum",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        ),
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private val viewModel by lazy {
        EditorViewModel(
            context = PlatformContext.INSTANCE,
            recipes = unused<RecipeRepository>(),
            savedRecipes = object : SavedRecipeRepository {
                override suspend fun list() = listOf(saved)
                override suspend fun save(recipe: SavedRecipe) = Unit
                override suspend fun delete(id: String) = Unit
            },
            derivedAssets = unused<DerivedAssetRepository>(),
            configuration = unused<ConfigurationRepository>(),
            immichApi = unused<ImmichApi>(),
            exporter = unused<EditorExporter>(),
        )
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun awaitState(condition: (EditorUiState) -> Boolean) = runBlocking {
        withTimeoutOrNull(30_000) { viewModel.state.first(condition) }
            ?: error("Timed out; the state was ${viewModel.state.value.copy(bitmap = null, history = emptyList())}")
    }

    private fun open() {
        viewModel.open(PhotoEditTarget.Recipe(sampleEditorAsset(0), RecipeSource.Saved(saved)))
        awaitState { it.bitmap != null && it.recipeEdit != null }
    }

    private fun choose(mode: String) {
        viewModel.setUprightMode(mode)
        // The history step lands a moment after the recipe, from the detection's thread: wait for both.
        awaitState { !it.isDetectingUpright && it.recipe?.geometry?.perspective?.upright == mode && it.canUndo }
    }

    private val perspective get() = viewModel.state.value.recipe!!.geometry.perspective

    @Test
    fun `a mode is stored with what it found and leaves a history step`() {
        open()

        choose(UPRIGHT_FULL)

        assertFalse(viewModel.state.value.isDetectingUpright)
        assertTrue(viewModel.state.value.canUndo)
        assertEquals(UPRIGHT_FULL, perspective.upright)
    }

    /** Vertical never turns: whatever the photo, its yaw stays the sliders'. */
    @Test
    fun `vertical leaves the turn alone`() {
        open()

        choose(UPRIGHT_VERTICAL)

        assertEquals(0f, perspective.uprightYaw)
    }

    /** An automatic mode replaces the guided one, guides included. */
    @Test
    fun `an automatic mode drops the guides`() {
        open()
        viewModel.setUprightMode(UPRIGHT_GUIDED)
        viewModel.beginGuideGesture()
        viewModel.updateGuides(listOf(UprightGuide(0.3f, 0.8f, 0.32f, 0.2f)))
        viewModel.finishGuideGesture()

        choose(UPRIGHT_FULL)

        assertEquals(emptyList<UprightGuide>(), perspective.guides)
    }

    @Test
    fun `off after an automatic mode corrects nothing`() {
        open()
        choose(UPRIGHT_FULL)

        viewModel.setUprightMode(UPRIGHT_OFF)

        assertEquals(UPRIGHT_OFF, perspective.upright)
        assertEquals(0f, perspective.uprightRoll)
        assertEquals(0f, perspective.uprightPitch)
        assertEquals(0f, perspective.uprightYaw)
    }

    /** A quarter turn solves again from the lines already found, with the mode kept. */
    @Test
    fun `turning keeps the mode`() {
        open()
        choose(UPRIGHT_FULL)

        viewModel.rotateClockwise()
        awaitState { !it.isDetectingUpright }

        assertEquals(UPRIGHT_FULL, perspective.upright)
        assertEquals(90, viewModel.state.value.recipe!!.geometry.rotation)
    }
}
