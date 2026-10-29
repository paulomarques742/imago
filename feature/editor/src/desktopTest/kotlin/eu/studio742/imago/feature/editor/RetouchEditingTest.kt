package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RetouchMode
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** The Remove tool through the real view model: strokes, their content, the history and the device's tool. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetouchEditingTest {
    /** The tool the device keeps, as the configuration would. */
    private var storedTool: String? = null

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        when (method.name) {
            "getRecipePartsChoice", "getBrushTool" -> return@newProxyInstance null
            "getRetouchTool" -> return@newProxyInstance storedTool
            "setRetouchTool" -> return@newProxyInstance Unit.also { storedTool = args[0] as String }
        }
        error("${T::class.simpleName}.${method.name} is not used by the Remove tool")
    } as T

    private val blank = SavedRecipe(
        id = "blank",
        name = "Blank",
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
                override suspend fun list() = listOf(blank)
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

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun open() = runBlocking {
        viewModel.open(PhotoEditTarget.Recipe(sampleEditorAsset(0), RecipeSource.Saved(blank)))
        withTimeoutOrNull(20_000) { viewModel.state.first { it.bitmap != null && it.recipeEdit != null } }
            ?: error("The sample never opened")
        viewModel.selectSheet(EditorSheet.REMOVE)
    }

    private val state get() = viewModel.state.value
    private val retouches get() = state.recipe!!.retouches

    private fun solved() = runBlocking {
        withTimeoutOrNull(20_000) { viewModel.state.first { !it.isSolvingRetouch } } ?: error("The correction was never worked out")
    }

    private fun paint(mode: RetouchMode, vararg points: Float, radius: Float = 0.04f) {
        viewModel.setRetouchMode(mode)
        viewModel.beginRetouchStroke(points[0], points[1], radius)
        for (index in 2 until points.size step 2) viewModel.extendRetouchStroke(points[index], points[index + 1])
        viewModel.finishRetouchGesture()
        solved()
    }

    @Test
    fun `a heal finds its own source and is one history entry, undone in one step`() {
        open()
        assertTrue(state.isRetouching)
        val before = state.history.size

        paint(RetouchMode.HEAL, 0.5f, 0.5f, 0.52f, 0.5f)

        val heal = retouches.single()
        assertEquals(RetouchMode.HEAL, heal.mode)
        assertTrue(heal.sourceX != 0f || heal.sourceY != 0f)
        assertEquals(listOf(0.5f, 0.5f, 0.52f, 0.5f), heal.strokes.single().points)
        assertEquals(heal.id, state.selectedRetouchId)
        assertEquals(listOf(heal), state.renderParameters.retouches)
        assertEquals(before + 1, state.history.size)
        viewModel.undo()
        assertTrue(retouches.isEmpty())
    }

    @Test
    fun `a remove keeps where its content comes from, and a refresh fills it again`() {
        open()
        paint(RetouchMode.REMOVE, 0.3f, 0.6f)
        val first = retouches.single().fill
        assertNotNull(first)
        val before = state.history.size

        viewModel.refreshRetouch(retouches.single().id)
        solved()

        assertNotNull(retouches.single().fill)
        assertNotEquals(first, retouches.single().fill)
        assertEquals(before + 1, state.history.size)
    }

    /** While it is painted a correction has no content: the photo is not worked out again at every point. */
    @Test
    fun `a stroke being painted does not reach the render`() {
        open()
        viewModel.setRetouchMode(RetouchMode.CLONE)
        viewModel.beginRetouchStroke(0.4f, 0.4f, 0.04f)
        viewModel.extendRetouchStroke(0.45f, 0.4f)

        assertEquals(1, retouches.size)
        assertTrue(state.renderParameters.retouches.isEmpty())
        viewModel.finishRetouchGesture()
        solved()
        assertEquals(1, state.renderParameters.retouches.size)
    }

    /** A quick hand paints the next stroke before the last one is solved: both stay, each with its content. */
    @Test
    fun `a stroke painted while another is being solved is kept, and neither is lost`() {
        open()
        val before = state.history.size
        viewModel.setRetouchMode(RetouchMode.REMOVE)
        viewModel.beginRetouchStroke(0.3f, 0.6f, 0.04f)
        viewModel.finishRetouchGesture()
        viewModel.beginRetouchStroke(0.7f, 0.3f, 0.04f)
        viewModel.extendRetouchStroke(0.72f, 0.3f)
        viewModel.finishRetouchGesture()
        solved()

        assertEquals(2, retouches.size)
        assertTrue(retouches.all { it.fill != null })
        assertEquals(2, state.renderParameters.retouches.size)
        assertEquals(before + 2, state.history.size)
    }

    @Test
    fun `the eye hides the corrections and painting shows them again`() {
        open()
        paint(RetouchMode.HEAL, 0.5f, 0.5f)
        viewModel.setShowsRetouches(false)
        assertFalse(state.showsRetouches)
        assertEquals(1, state.renderParameters.retouches.size)

        paint(RetouchMode.HEAL, 0.3f, 0.3f)
        assertTrue(state.showsRetouches)
    }

    /** The source set right, the area is painted wider: a stroke within the window joins the same Clone. */
    @Test
    fun `a stroke soon after a gesture on a clone widens it, and a later one starts another`() {
        open()
        var now = 1_000_000L
        viewModel.clock = { now }
        paint(RetouchMode.CLONE, 0.5f, 0.5f)
        val clone = retouches.single()
        viewModel.moveRetouchSource(clone.id, -0.2f, 0f)
        viewModel.finishRetouchGesture()
        // The screen shrinks its pin and lets a touch beside it paint.
        assertEquals(clone.id, state.extendableRetouchId)
        val before = state.history.size

        now += RETOUCH_EXTEND_MILLIS - 100
        viewModel.beginRetouchStroke(0.55f, 0.5f, 0.04f)
        viewModel.extendRetouchStroke(0.58f, 0.52f)
        viewModel.finishRetouchGesture()
        solved()

        val widened = retouches.single()
        assertEquals(2, widened.strokes.size)
        assertEquals(listOf(0.55f, 0.5f, 0.58f, 0.52f), widened.strokes[1].points)
        assertEquals(-0.2f, widened.sourceX)
        assertEquals(before + 1, state.history.size)

        now += RETOUCH_EXTEND_MILLIS + 500
        paint(RetouchMode.CLONE, 0.2f, 0.3f)
        assertEquals(2, retouches.size)
    }

    /** Joining a Heal still being solved: the source found for it lands on the whole area, in one entry. */
    @Test
    fun `a stroke that joins a heal still being solved keeps both strokes`() {
        open()
        viewModel.clock = { 5_000L }
        val before = state.history.size
        viewModel.setRetouchMode(RetouchMode.HEAL)
        viewModel.beginRetouchStroke(0.4f, 0.5f, 0.04f)
        viewModel.finishRetouchGesture()
        viewModel.beginRetouchStroke(0.45f, 0.5f, 0.04f)
        viewModel.finishRetouchGesture()
        solved()

        val heal = retouches.single()
        assertEquals(2, heal.strokes.size)
        assertTrue(heal.sourceX != 0f || heal.sourceY != 0f)
        assertEquals(before + 1, state.history.size)
    }

    @Test
    fun `another mode in the hand starts another correction, and a remove is never widened`() {
        open()
        viewModel.clock = { 7_000L }
        paint(RetouchMode.HEAL, 0.5f, 0.5f)
        paint(RetouchMode.CLONE, 0.52f, 0.5f)
        assertEquals(2, retouches.size)
        paint(RetouchMode.REMOVE, 0.3f, 0.6f)
        paint(RetouchMode.REMOVE, 0.32f, 0.6f)
        assertEquals(4, retouches.size)
    }

    /** Opening another photo built a new state from nothing, and the tool went back to Remove at 25. */
    @Test
    fun `the tool in the hand carries on to the next photo`() {
        open()
        viewModel.setRetouchMode(RetouchMode.CLONE)
        viewModel.setRetouchSetting(RetouchSetting.SIZE, 60f)
        viewModel.finishRetouchSetting()

        runBlocking {
            viewModel.open(PhotoEditTarget.Recipe(sampleEditorAsset(1), RecipeSource.Saved(blank)))
            withTimeoutOrNull(20_000) { viewModel.state.first { it.bitmap != null && it.asset?.id == sampleEditorAsset(1).id } }
                ?: error("The second sample never opened")
        }

        assertEquals(RetouchMode.CLONE, state.retouchTool.mode)
        assertEquals(60f, state.retouchTool.size)
    }

    @Test
    fun `a cancelled stroke leaves nothing`() {
        open()
        val before = state.history.size
        viewModel.beginRetouchStroke(0.4f, 0.4f, 0.04f)
        viewModel.extendRetouchStroke(0.45f, 0.4f)
        viewModel.cancelRetouchStroke()

        assertTrue(retouches.isEmpty())
        assertNull(state.selectedRetouchId)
        assertEquals(before, state.history.size)
    }

    @Test
    fun `the source is dragged in one entry, and the mode buttons leave a correction as it is`() {
        open()
        paint(RetouchMode.HEAL, 0.5f, 0.5f)
        val id = retouches.single().id
        val before = state.history.size

        viewModel.moveRetouchSource(id, -0.1f, 0.05f)
        viewModel.moveRetouchSource(id, -0.2f, 0.1f)
        viewModel.finishRetouchGesture()

        assertEquals(-0.2f, retouches.single().sourceX)
        assertEquals(0.1f, retouches.single().sourceY)
        assertEquals(before + 1, state.history.size)

        viewModel.setRetouchMode(RetouchMode.CLONE)
        assertEquals(RetouchMode.HEAL, retouches.single().mode)
        assertEquals(RetouchMode.CLONE, state.retouchTool.mode)
        assertEquals(before + 1, state.history.size)
    }

    /** Moving a Heal's area leaves its source where it was; a moved Remove is filled again for the new place. */
    @Test
    fun `moving a correction keeps its source and fills a remove again`() {
        open()
        paint(RetouchMode.HEAL, 0.5f, 0.5f)
        val heal = retouches.single()
        viewModel.moveRetouch(heal.id, 0.1f, 0f)
        viewModel.finishRetouchGesture()
        val moved = retouches.single()
        assertEquals(0.6f, moved.strokes.single().points[0], 1e-4f)
        assertEquals(heal.strokes.single().points[0] + heal.sourceX, moved.strokes.single().points[0] + moved.sourceX, 1e-4f)

        viewModel.deleteRetouch(moved.id)
        paint(RetouchMode.REMOVE, 0.3f, 0.6f)
        val remove = retouches.single()
        viewModel.moveRetouch(remove.id, 0f, -0.1f)
        assertNull(retouches.single().fill)
        viewModel.finishRetouchGesture()
        solved()
        assertNotNull(retouches.single().fill)
    }

    @Test
    fun `the selected correction's opacity follows the slider, and the device keeps the tool`() {
        open()
        paint(RetouchMode.CLONE, 0.5f, 0.5f)
        viewModel.setRetouchSetting(RetouchSetting.OPACITY, 40f)
        viewModel.finishRetouchSetting()

        assertEquals(40f, retouches.single().opacity)
        assertEquals(40f, state.retouchValue(RetouchSetting.OPACITY))
        assertEquals(RetouchTool(mode = RetouchMode.CLONE, opacity = 40f), RetouchTool.decode(storedTool))

        viewModel.deleteRetouch(retouches.single().id)
        assertTrue(retouches.isEmpty())
        viewModel.undo()
        assertEquals(1, retouches.size)
    }
}
