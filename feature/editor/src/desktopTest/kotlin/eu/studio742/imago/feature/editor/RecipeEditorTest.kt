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
import eu.studio742.imago.core.model.Effects
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.model.WhiteBalance
import eu.studio742.imago.core.render.NeutralWhiteBalance
import eu.studio742.imago.core.render.autoWhiteBalanceOf
import eu.studio742.imago.core.render.toPixelBuffer
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

    /** What the recipe dialogs last ticked, as the configuration keeps it. */
    private var partsChoice: String? = null

    /** What this path never touches: any call is a test failure, not a silent default. */
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        when (method.name) {
            "getRecipePartsChoice" -> return@newProxyInstance partsChoice
            "setRecipePartsChoice" -> return@newProxyInstance Unit.also { partsChoice = args[0] as String? }
            "getBrushTool" -> return@newProxyInstance null
        }
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
    fun `pasting writes only what was ticked when copying, and the geometry starts unticked`() {
        openRecipe(RecipeSource.New())
        assertEquals(RecipePart.LOOK, viewModel.state.value.recipeParts)
        setExposure(1.2f)
        viewModel.updateStraighten(5f)
        viewModel.finishAdjustment()

        viewModel.copyCurrentRecipe(viewModel.state.value.recipeParts)
        setExposure(0f)
        viewModel.updateStraighten(2f)
        viewModel.finishAdjustment()
        viewModel.pasteCopiedRecipe()

        val pasted = viewModel.state.value.recipe!!
        assertEquals(1.2f, pasted.tone.exposure)
        assertEquals(2f, pasted.geometry.straighten)
    }

    @Test
    fun `the last choice is remembered, geometry included`() {
        openRecipe(RecipeSource.New())
        val chosen = setOf(RecipePart.EXPOSURE, RecipePart.STRAIGHTEN)

        viewModel.copyCurrentRecipe(chosen)

        assertEquals(chosen, viewModel.state.value.recipeParts)
        assertEquals("EXPOSURE,STRAIGHTEN", partsChoice)
    }

    @Test
    fun `a recipe saved before the choice opens on the sample without its geometry`() {
        val tilted = original.copy(recipe = original.recipe.copy(geometry = Geometry(straighten = 7f)))
        repository.stored["mine"] = tilted

        openRecipe(RecipeSource.Saved(tilted))

        assertEquals(0.5f, viewModel.state.value.recipe!!.tone.exposure)
        assertEquals(0f, viewModel.state.value.recipe!!.geometry.straighten)
        assertFalse(viewModel.state.value.hasUnsavedRecipeChanges)
    }

    @Test
    fun `a new recipe keeps what was ticked`() {
        openRecipe(RecipeSource.New())
        setExposure(0.7f)

        viewModel.saveRecipeEdits("Exposure only", "Mine", setOf(RecipePart.EXPOSURE))
        awaitState { it.recipeEdit?.saved != null }

        val created = repository.stored.getValue(viewModel.state.value.recipeEdit!!.saved!!.id)
        assertEquals(setOf(RecipePart.EXPOSURE), created.parts)
        assertEquals(setOf(RecipePart.EXPOSURE), viewModel.state.value.recipeParts)
    }

    @Test
    fun `what is touched on the sample goes with the recipe from then on, and what was left out is kept`() {
        val tilted = original.copy(recipe = original.recipe.copy(geometry = Geometry(straighten = 7f), effects = Effects(grainAmount = 30f)))
        repository.stored["mine"] = tilted
        openRecipe(RecipeSource.Saved(tilted))
        viewModel.updateStraighten(3f)
        viewModel.finishAdjustment()

        viewModel.saveRecipeEdits()
        awaitState { !it.hasUnsavedRecipeChanges }

        val saved = repository.stored.getValue("mine")
        assertEquals(RecipePart.LEGACY + RecipePart.STRAIGHTEN, saved.parts)
        assertEquals(3f, saved.recipe.geometry.straighten)
        assertEquals(30f, saved.recipe.effects.grainAmount)
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
    fun `the white balance selector is one history step, and cancel puts back what there was`() {
        openRecipe(RecipeSource.Saved(original))
        val before = viewModel.state.value.recipe!!.whiteBalance

        viewModel.startWhiteBalancePick()
        viewModel.pickWhiteBalance(NeutralWhiteBalance(temperature = -30f, tint = 12f))
        assertEquals(-30f, viewModel.state.value.recipe!!.whiteBalance.temp)
        viewModel.pickWhiteBalance(null)
        assertTrue(viewModel.state.value.whiteBalanceUnreadable)
        assertEquals("an unreadable area changes nothing", -30f, viewModel.state.value.recipe!!.whiteBalance.temp)
        viewModel.cancelWhiteBalancePick()

        assertFalse(viewModel.state.value.isPickingWhiteBalance)
        assertEquals(before, viewModel.state.value.recipe!!.whiteBalance)
        assertFalse(viewModel.state.value.canUndo)

        viewModel.startWhiteBalancePick()
        viewModel.pickWhiteBalance(NeutralWhiteBalance(temperature = 20f, tint = -5f))
        viewModel.pickWhiteBalance(NeutralWhiteBalance(temperature = 25f, tint = -8f))
        viewModel.acceptWhiteBalancePick()

        val kept = viewModel.state.value.recipe!!
        assertEquals(25f, kept.whiteBalance.temp)
        assertEquals(-8f, kept.whiteBalance.tint)
        assertTrue(viewModel.state.value.canUndo)
        viewModel.undo()
        assertEquals("the two picks were one step", before, viewModel.state.value.recipe!!.whiteBalance)
    }

    /** What Auto finds on the photo open now. */
    private fun autoOnOpenPhoto(): NeutralWhiteBalance = checkNotNull(autoWhiteBalanceOf(viewModel.state.value.bitmap!!.toPixelBuffer()))

    @Test
    fun `Auto finds the photo's light, As Shot goes back to zero, and each is a history step`() {
        openRecipe(RecipeSource.Saved(original))
        val expected = autoOnOpenPhoto()

        viewModel.selectWhiteBalancePreset(WhiteBalancePreset.AUTO)
        awaitState { it.recipe!!.whiteBalance.auto }
        assertEquals(WhiteBalance(expected.temperature, expected.tint, auto = true), viewModel.state.value.recipe!!.whiteBalance)
        assertEquals(WhiteBalancePreset.AUTO, WhiteBalancePreset.of(viewModel.state.value.recipe!!.whiteBalance))

        viewModel.selectWhiteBalancePreset(WhiteBalancePreset.AS_SHOT)
        assertEquals(WhiteBalance(), viewModel.state.value.recipe!!.whiteBalance)
        assertEquals(WhiteBalancePreset.AS_SHOT, WhiteBalancePreset.of(viewModel.state.value.recipe!!.whiteBalance))

        viewModel.undo()
        assertTrue("undo goes back to Auto", viewModel.state.value.recipe!!.whiteBalance.auto)
    }

    @Test
    fun `the profile is a step of its own, and a JPEG sample has no menu for it`() {
        openRecipe(RecipeSource.Saved(original))
        assertFalse("a JPEG has no profile to choose", viewModel.state.value.showsRawProfile)

        viewModel.selectProfile(RawProfileChoice.MONOCHROME)
        assertEquals(eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME, viewModel.state.value.recipe!!.profile)
        assertTrue(viewModel.state.value.hasUnsavedRecipeChanges)

        viewModel.undo()
        assertEquals(eu.studio742.imago.core.model.RAW_PROFILE_COLOR, viewModel.state.value.recipe!!.profile)
    }

    @Test
    fun `a hand on a slider or the selector makes Auto Custom`() {
        openRecipe(RecipeSource.Saved(original))
        viewModel.selectWhiteBalancePreset(WhiteBalancePreset.AUTO)
        awaitState { it.recipe!!.whiteBalance.auto }

        viewModel.updateAdjustment(Adjustment.TINT, 20f)
        viewModel.finishAdjustment()
        assertEquals(WhiteBalancePreset.CUSTOM, WhiteBalancePreset.of(viewModel.state.value.recipe!!.whiteBalance))

        viewModel.selectWhiteBalancePreset(WhiteBalancePreset.AUTO)
        awaitState { it.recipe!!.whiteBalance.auto }
        viewModel.startWhiteBalancePick()
        viewModel.pickWhiteBalance(NeutralWhiteBalance(temperature = 10f, tint = 2f))
        viewModel.acceptWhiteBalancePick()
        assertFalse(viewModel.state.value.recipe!!.whiteBalance.auto)

        viewModel.selectWhiteBalancePreset(WhiteBalancePreset.AUTO)
        awaitState { it.recipe!!.whiteBalance.auto }
        viewModel.selectWhiteBalancePreset(WhiteBalancePreset.CUSTOM)
        val custom = viewModel.state.value.recipe!!.whiteBalance
        assertFalse("Custom lets go of Auto and keeps its values", custom.auto)
        assertEquals(WhiteBalancePreset.CUSTOM, WhiteBalancePreset.of(custom))
    }

    @Test
    fun `a recipe with Auto finds the light again on every photo it opens on`() {
        val auto = original.copy(recipe = original.recipe.copy(whiteBalance = WhiteBalance(temp = 99f, tint = -99f, auto = true)))
        val found = (0 until 2).map { sample ->
            openRecipe(RecipeSource.Saved(auto), sample)
            val shown = viewModel.state.value.recipe!!.whiteBalance
            val expected = autoOnOpenPhoto()
            assertEquals(WhiteBalance(expected.temperature, expected.tint, auto = true), shown)
            viewModel.closeRecipe()
            shown
        }
        assertNotEquals("two photos, two lights", found[0], found[1])
    }

    @Test
    fun `leaving the panel while picking keeps what was picked`() {
        openRecipe(RecipeSource.Saved(original))
        viewModel.startWhiteBalancePick()
        viewModel.pickWhiteBalance(NeutralWhiteBalance(temperature = 15f, tint = 0f))

        viewModel.selectPanel(EditorPanel.LIGHT)

        assertFalse(viewModel.state.value.isPickingWhiteBalance)
        assertEquals(15f, viewModel.state.value.recipe!!.whiteBalance.temp)
        assertTrue(viewModel.state.value.canUndo)
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
        assertEquals(eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION, edited.processVersion)
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
