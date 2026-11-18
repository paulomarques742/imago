package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.ml.MaskModel
import eu.studio742.imago.core.ml.ModelSource
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Whether Subject, Sky and Background are offered is the build's, and survives opening a photo. */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratedMaskAvailabilityTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        if (method.name == "getRecipePartsChoice" || method.name == "getBrushTool" || method.name == "getRetouchTool") return@newProxyInstance null
        error("${T::class.simpleName}.${method.name} is not used here")
    } as T

    private val saved = SavedRecipe(
        id = "plain",
        name = "Plain",
        collection = "General",
        recipe = EditRecipe(assetId = "imago:library:photo", originalChecksum = "checksum", createdAt = "", updatedAt = ""),
        createdAt = "",
        updatedAt = "",
    )

    /** Never asked for a model: these tests only open a photo. */
    private val models = object : ModelSource {
        override fun localPath(model: MaskModel): String? = null
        override fun download(model: MaskModel, progress: (Long, Long) -> Unit): String = error("not downloaded here")
    }

    private fun viewModel(tools: GeneratedMaskTools) = EditorViewModel(
        context = PlatformContext.INSTANCE,
        recipes = unused<RecipeRepository>(),
        savedRecipes = object : SavedRecipeRepository {
            override suspend fun list() = listOf(saved)
            override suspend fun save(recipe: SavedRecipe) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun builtInMarks() = emptyList<BuiltInRecipeMark>()
            override suspend fun saveBuiltInMark(mark: BuiltInRecipeMark) = Unit
        },
        derivedAssets = unused<DerivedAssetRepository>(),
        configuration = unused<ConfigurationRepository>(),
        immichApi = unused<ImmichApi>(),
        exporter = unused<EditorExporter>(),
        generatedMaskTools = tools,
    )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun EditorViewModel.openSample(): EditorUiState {
        open(PhotoEditTarget.Recipe(sampleEditorAsset(0), RecipeSource.Saved(saved)))
        return runBlocking { withTimeoutOrNull(30_000) { state.first { it.bitmap != null } } } ?: error("The photo did not open")
    }

    @Test
    fun `a build with the models still offers them once a photo is open`() {
        val editor = viewModel(GeneratedMaskTools(models))
        assertTrue(editor.state.value.generatedMasksAvailable)
        assertTrue(editor.openSample().generatedMasksAvailable)
    }

    @Test
    fun `a build without them never offers them`() {
        assertFalse(viewModel(GeneratedMaskTools(null)).openSample().generatedMasksAvailable)
    }
}
