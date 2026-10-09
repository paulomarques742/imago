package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.BUILT_IN_RECIPES
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.render.toRenderParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The recipe editor: a recipe opened over a sample, edited, and saved back to the recipe — through
 * the real view model and the real sample photos, decoded by Coil as the app does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditorTest {
    private class MemorySavedRecipes(initial: List<SavedRecipe>) : SavedRecipeRepository {
        val stored = initial.associateBy { it.id }.toMutableMap()
        override suspend fun list() = stored.values.toList()
        override suspend fun save(recipe: SavedRecipe) { stored[recipe.id] = recipe }
        override suspend fun delete(id: String) { stored.remove(id) }
        val marks = mutableMapOf<String, BuiltInRecipeMark>()
        override suspend fun builtInMarks() = marks.values.toList()
        override suspend fun saveBuiltInMark(mark: BuiltInRecipeMark) { marks[mark.id] = mark }
    }

    /** What this path never touches: any call is a test failure, not a silent default. */
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${T::class.simpleName}.${method.name} is not used by the recipe editor")
    } as T

    private val original = SavedRecipe(
        id = "mine",
        name = "Mine",
        collection = "General",
        recipe = EditRecipe(
            assetId = "imago:library:photo",
            originalChecksum = "checksum",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            tone = Tone(exposure = 0.5f),
        ),
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        isFavorite = true,
    )
    private val repository = MemorySavedRecipes(listOf(original))
    private val viewModel by lazy {
        EditorViewModel(
            context = PlatformContext.INSTANCE,
            recipes = unused<RecipeRepository>(),
            savedRecipes = repository,
            derivedAssets = unused<DerivedAssetRepository>(),
            configuration = unused<ConfigurationRepository>(),
            immichApi = unused<ImmichApi>(),
            exporter = unused<EditorExporter>(),
        )
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun awaitState(condition: (EditorUiState) -> Boolean) = runBlocking {
        withTimeoutOrNull(20_000) { viewModel.state.first(condition) }
            ?: error("Timed out; the state was ${viewModel.state.value.copy(bitmap = null, history = emptyList())}")
    }

    private fun openRecipe(source: RecipeSource, sample: Int = 0) {
        viewModel.open(PhotoEditTarget.Recipe(sampleEditorAsset(sample), source))
        awaitState { it.asset?.id == sampleEditorAsset(sample).id && it.bitmap != null && it.recipeEdit != null }
    }

    private fun setExposure(value: Float) {
        viewModel.updateAdjustment(Adjustment.EXPOSURE, value)
        viewModel.finishAdjustment()
    }

    @Test
    fun `a saved recipe opens as it is, with nothing to save`() {
        openRecipe(RecipeSource.Saved(original))

        val state = viewModel.state.value
        assertEquals(0.5f, state.recipe!!.tone.exposure)
        assertFalse(state.hasUnsavedRecipeChanges)
        assertEquals(original, state.recipeEdit!!.saved)
    }

    @Test
    fun `another sample keeps the edits and what is unsaved`() {
        openRecipe(RecipeSource.Saved(original), sample = 0)
        setExposure(1.2f)

        openRecipe(RecipeSource.Saved(original), sample = 3)

        val state = viewModel.state.value
        assertEquals(1.2f, state.recipe!!.tone.exposure)
        assertTrue(state.hasUnsavedRecipeChanges)
        assertTrue("the history survives the swap", state.canUndo)
    }

    @Test
    fun `undoing back to the opened look is not a change`() {
        openRecipe(RecipeSource.Saved(original))
        setExposure(1.2f)
        assertTrue(viewModel.state.value.hasUnsavedRecipeChanges)

        viewModel.undo()

        assertFalse(viewModel.state.value.hasUnsavedRecipeChanges)
    }

    @Test
    fun `nothing is written until saved, and saving writes over the same recipe`() {
        openRecipe(RecipeSource.Saved(original))
        setExposure(1.2f)
        assertEquals("edits alone do not touch the recipe", original, repository.stored.getValue("mine"))

        viewModel.saveRecipeEdits()
        awaitState { !it.hasUnsavedRecipeChanges }

        val saved = repository.stored.getValue("mine")
        assertEquals(1, repository.stored.size)
        assertEquals(1.2f, saved.recipe.tone.exposure)
        // What ties the record to its origin stays the record's, and so do the user's marks on it.
        assertEquals(original.recipe.assetId, saved.recipe.assetId)
        assertEquals(original.recipe.createdAt, saved.recipe.createdAt)
        assertEquals(original.name, saved.name)
        assertTrue(saved.isFavorite)
        assertNotEquals(original.updatedAt, saved.updatedAt)
    }

    @Test
    fun `a filter is saved as a new recipe and never changes`() {
        val filter = BUILT_IN_RECIPES.first()
        val before = filter.recipe
        openRecipe(RecipeSource.BuiltIn(filter))
        assertNull(viewModel.state.value.recipeEdit!!.saved)
        assertNotNull(viewModel.state.value.recipeEdit!!.suggestedName)
        setExposure(0.8f)

        viewModel.saveRecipeEdits("My filter", "Mine")
        awaitState { it.recipeEdit?.saved != null && !it.hasUnsavedRecipeChanges }

        val created = viewModel.state.value.recipeEdit!!.saved!!
        assertEquals(2, repository.stored.size)
        assertEquals("My filter", created.name)
        assertEquals(0.8f, repository.stored.getValue(created.id).recipe.tone.exposure)
        assertEquals(before, filter.recipe)
    }

    @Test
    fun `a recipe that is not the user's yet is not saved without a name`() {
        openRecipe(RecipeSource.New())
        setExposure(0.3f)

        viewModel.saveRecipeEdits(" ", "Mine")

        assertEquals(1, repository.stored.size)
        assertTrue(viewModel.state.value.hasUnsavedRecipeChanges)
    }

    @Test
    fun `duplicating saves a copy and goes on editing the copy`() {
        openRecipe(RecipeSource.Saved(original))
        setExposure(1.2f)

        viewModel.duplicateRecipeEdit()
        awaitState { it.recipeEdit?.saved?.id.let { id -> id != null && id != "mine" } }

        val copy = viewModel.state.value.recipeEdit!!.saved!!
        assertEquals(2, repository.stored.size)
        assertTrue(copy.name.contains("Mine"))
        assertEquals(1.2f, copy.recipe.tone.exposure)
        assertEquals("the original stays as last saved", original, repository.stored.getValue("mine"))
        assertFalse(viewModel.state.value.hasUnsavedRecipeChanges)
    }

    @Test
    fun `deleting removes the recipe being edited`() {
        openRecipe(RecipeSource.Saved(original))
        var left = false

        viewModel.deleteRecipeEdit(onDeleted = { left = true })
        runBlocking { withTimeout(5_000) { while (!left) kotlinx.coroutines.delay(10) } }

        assertTrue(repository.stored.isEmpty())
    }

    @Test
    fun `reopening after closing starts again from the saved recipe`() {
        openRecipe(RecipeSource.Saved(original))
        setExposure(1.2f)

        viewModel.closeRecipe()
        openRecipe(RecipeSource.Saved(original))

        assertEquals(0.5f, viewModel.state.value.recipe!!.tone.exposure)
        assertFalse(viewModel.state.value.hasUnsavedRecipeChanges)
    }

    @Test
    fun `a point on the blue curve leaves the composite alone, and reset puts back only blue`() {
        openRecipe(RecipeSource.Saved(original))
        viewModel.moveCurvePoint(1, 255, 255)
        viewModel.addCurvePoint(128, 160)
        viewModel.finishAdjustment()
        val composite = viewModel.state.value.recipe!!.toneCurve.rgb

        viewModel.selectCurveChannel(CurveChannel.BLUE)
        viewModel.addCurvePoint(128, 90)
        viewModel.finishAdjustment()

        val edited = viewModel.state.value.recipe!!
        assertEquals(composite, edited.toneCurve.rgb)
        assertEquals(listOf(0, 128, 255), edited.toneCurve.blue!!.map { it.x })
        assertNull(edited.toneCurve.red)
        assertEquals(12, edited.processVersion)
        assertTrue(edited.toRenderParameters().toneCurveBlue[128] < 100f / 255f)

        viewModel.resetCurve()

        assertNull("the straight line is no channel curve", viewModel.state.value.recipe!!.toneCurve.blue)
        assertEquals(composite, viewModel.state.value.recipe!!.toneCurve.rgb)
    }

    @Test
    fun `applying an app filter records its last use, and its heart is kept apart from it`() {
        val filter = BUILT_IN_RECIPES.first()
        openRecipe(RecipeSource.Saved(original))

        viewModel.applyBuiltInRecipe(filter.id)
        awaitState { state -> state.builtInMarks.any { it.id == filter.id && it.usedAt != null } }
        viewModel.toggleBuiltInRecipeFavorite(filter.id)
        awaitState { state -> state.builtInMarks.any { it.id == filter.id && it.isFavorite } }

        val mark = repository.marks.getValue(filter.id)
        assertTrue(mark.isFavorite)
        assertNotNull("the heart does not erase the last use", mark.usedAt)
        assertEquals("the filter is not copied into the person's recipes", 1, repository.stored.size)
    }

    @Test
    fun `the library marks an app filter as a favourite and back`() {
        val filter = BUILT_IN_RECIPES.last()
        val library = RecipeLibraryViewModel(repository)
        runBlocking { withTimeout(5_000) { library.state.first { !it.isLoading } } }

        library.toggleBuiltInFavorite(filter.id)
        runBlocking { withTimeout(5_000) { library.state.first { s -> s.builtInMarks.any { it.id == filter.id && it.isFavorite } } } }
        library.toggleBuiltInFavorite(filter.id)
        runBlocking { withTimeout(5_000) { library.state.first { s -> s.builtInMarks.any { it.id == filter.id && !it.isFavorite } } } }

        assertFalse(repository.marks.getValue(filter.id).isFavorite)
    }

    @Test
    fun `the library's duplicate is a new recipe of its own`() {
        val library = RecipeLibraryViewModel(repository)
        runBlocking { withTimeout(5_000) { library.state.first { !it.isLoading } } }

        library.duplicate("mine")
        runBlocking { withTimeout(5_000) { library.state.first { it.recipes.size == 2 } } }

        val copy = repository.stored.values.first { it.id != "mine" }
        assertTrue(copy.name.contains("Mine"))
        assertFalse(copy.isFavorite)
        assertNull(copy.usedAt)
        assertEquals(original.recipe.tone, copy.recipe.tone)
    }
}
