package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** Saving a photo's recipe while the person keeps adjusting it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeSaveTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        if (method.name == "getRecipePartsChoice" || method.name == "getBrushTool") return@newProxyInstance null
        error("${T::class.simpleName}.${method.name} is not used here")
    } as T

    private val dispatcher = UnconfinedTestDispatcher()

    /** A save that does not finish until the test lets it: a slow one, like a photo on a server. */
    private val gate = CompletableDeferred<Unit>()
    private var saves = 0

    private val viewModel by lazy {
        EditorViewModel(
            context = PlatformContext.INSTANCE,
            recipes = object : RecipeRepository {
                override suspend fun get(assetId: String): EditRecipe? = null
                override suspend fun save(recipe: EditRecipe) {
                    saves++
                    gate.await()
                }
            },
            savedRecipes = object : SavedRecipeRepository {
                override suspend fun list() = emptyList<SavedRecipe>()
                override suspend fun save(recipe: SavedRecipe) = Unit
                override suspend fun delete(id: String) = Unit
                override suspend fun builtInMarks() = emptyList<BuiltInRecipeMark>()
                override suspend fun saveBuiltInMark(mark: BuiltInRecipeMark) = Unit
            },
            derivedAssets = unused<DerivedAssetRepository>(),
            configuration = unused<ConfigurationRepository>(),
            immichApi = unused<ImmichApi>(),
            exporter = unused<EditorExporter>(),
        )
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an adjustment that cancels a save in progress is not an error`() {
        viewModel.open(PhotoEditTarget.Asset(sampleEditorAsset(0)))
        runBlocking { withTimeoutOrNull(30_000) { viewModel.state.first { it.bitmap != null } } ?: error("the photo never opened") }

        viewModel.updateAdjustment(Adjustment.EXPOSURE, 0.3f)
        // The save waits a second for the adjustments to settle, then starts, and hangs on the gate.
        dispatcher.scheduler.advanceTimeBy(1_100)
        assertEquals(1, saves)

        // The next adjustment cancels it to save again: that cancellation is how saving works.
        viewModel.updateAdjustment(Adjustment.EXPOSURE, 0.6f)

        assertNull(viewModel.state.value.error)
    }
}
