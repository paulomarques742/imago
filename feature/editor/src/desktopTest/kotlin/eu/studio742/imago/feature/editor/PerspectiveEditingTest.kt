package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Perspective
import eu.studio742.imago.core.model.SavedRecipe
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

/** The perspective sliders through the real view model, over a recipe saved before they existed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PerspectiveEditingTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${T::class.simpleName}.${method.name} is not used by the perspective")
    } as T

    private val legacy = SavedRecipe(
        id = "legacy",
        name = "Legacy",
        collection = "General",
        recipe = EditRecipe(
            assetId = "imago:library:photo",
            originalChecksum = "checksum",
            processVersion = 9,
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
                override suspend fun list() = listOf(legacy)
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

    private fun open() = runBlocking {
        viewModel.open(PhotoEditTarget.Recipe(sampleEditorAsset(0), RecipeSource.Saved(legacy)))
        withTimeoutOrNull(20_000) { viewModel.state.first { it.bitmap != null && it.recipeEdit != null } }
            ?: error("The sample never opened")
    }

    private val perspective get() = viewModel.state.value.recipe!!.geometry.perspective

    private fun slide(adjustment: Adjustment, value: Float) {
        viewModel.updateAdjustment(adjustment, value)
        viewModel.finishAdjustment()
    }

    /**
     * Moving a slider used to clear the dormant geometry *after* applying the change: on a recipe from
     * version 9 the perspective was the one change that step threw away.
     */
    @Test
    fun `a perspective slider sticks on a recipe from before it existed`() {
        open()

        slide(Adjustment.PERSPECTIVE_VERTICAL, -35f)

        assertEquals(-35f, perspective.vertical)
        assertEquals(CURRENT_PROCESS_VERSION, viewModel.state.value.recipe!!.processVersion)
        assertTrue(viewModel.state.value.canUndo)
    }

    /** Constrained, the frame cannot shrink below the cover: the slider stops at zero. */
    @Test
    fun `the scale stops at zero while the crop is constrained`() {
        open()

        slide(Adjustment.PERSPECTIVE_SCALE, -30f)
        assertEquals(0f, perspective.scale)

        viewModel.setConstrainCrop(false)
        slide(Adjustment.PERSPECTIVE_SCALE, -30f)
        assertEquals(-30f, perspective.scale)
        assertFalse(perspective.constrainCrop)

        // Back on, a scale below the cover would have no effect: it is brought back to zero.
        viewModel.setConstrainCrop(true)
        assertEquals(0f, perspective.scale)
    }

    @Test
    fun `resetting the perspective leaves the rest of the geometry`() {
        open()
        viewModel.rotateClockwise()
        slide(Adjustment.PERSPECTIVE_HORIZONTAL, 40f)

        viewModel.resetPerspective()

        assertEquals(Perspective(), perspective)
        assertEquals(90, viewModel.state.value.recipe!!.geometry.rotation)
    }
}
