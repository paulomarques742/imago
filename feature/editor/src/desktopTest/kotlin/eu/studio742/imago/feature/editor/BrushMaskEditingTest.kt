package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape
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

/** The brush through the real view model: strokes, history, the eraser and what the device remembers. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrushMaskEditingTest {
    /** The brush the device keeps, as the configuration would. */
    private var storedBrush: String? = null

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        when (method.name) {
            "getRecipePartsChoice" -> return@newProxyInstance null
            "getBrushTool" -> return@newProxyInstance storedBrush
            "setBrushTool" -> return@newProxyInstance Unit.also { storedBrush = args[0] as String }
        }
        error("${T::class.simpleName}.${method.name} is not used by the brush")
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
    }

    private val state get() = viewModel.state.value
    private val brush get() = state.selectedMask!!.components[state.selectedComponent].brush!!

    private fun paint(vararg points: Float, radius: Float = 0.05f) {
        viewModel.beginBrushStroke(state.selectedMaskId!!, points[0], points[1], radius)
        for (index in 2 until points.size step 2) viewModel.extendBrushStroke(state.selectedMaskId!!, points[index], points[index + 1])
        viewModel.finishAdjustment()
    }

    @Test
    fun `a brush mask paints one stroke per gesture, undone in one step`() {
        open()
        viewModel.selectPanel(EditorPanel.MASKS)
        viewModel.addMask(MaskShape.BRUSH)
        assertTrue(state.isPainting)
        val before = state.history.size

        paint(0.2f, 0.5f, 0.4f, 0.5f, 0.6f, 0.55f)

        assertEquals(1, brush.strokes.size)
        assertEquals(listOf(0.2f, 0.5f, 0.4f, 0.5f, 0.6f, 0.55f), brush.strokes.single().points)
        assertEquals(before + 1, state.history.size)
        viewModel.undo()
        assertTrue(brush.strokes.isEmpty())
    }

    /** The brush in the hand goes into the stroke: the Eraser's settings are its own. */
    @Test
    fun `the eraser paints erasing strokes with its own settings`() {
        open()
        viewModel.selectPanel(EditorPanel.MASKS)
        viewModel.addMask(MaskShape.BRUSH)
        viewModel.setBrushSetting(BrushSetting.FLOW, 40f)
        viewModel.finishBrushSetting()
        viewModel.setBrushErasing(true)
        viewModel.setBrushSetting(BrushSetting.FEATHER, 0f)
        viewModel.finishBrushSetting()

        paint(0.5f, 0.5f)

        val stroke = brush.strokes.single()
        assertTrue(stroke.erase)
        assertEquals(0f, stroke.feather)
        assertEquals(100f, stroke.flow)
        assertEquals(40f, state.brushTool.paint.flow)
    }

    /** A second finger turns the stroke into a pinch, and what it had painted goes away with no history. */
    @Test
    fun `a cancelled stroke leaves nothing`() {
        open()
        viewModel.selectPanel(EditorPanel.MASKS)
        viewModel.addMask(MaskShape.BRUSH)
        val before = state.history.size

        viewModel.beginBrushStroke(state.selectedMaskId!!, 0.5f, 0.5f, 0.05f)
        viewModel.extendBrushStroke(state.selectedMaskId!!, 0.6f, 0.5f)
        viewModel.cancelBrushStroke()
        viewModel.finishAdjustment()

        assertTrue(brush.strokes.isEmpty())
        assertEquals(before, state.history.size)
    }

    /** A brush added to a radial is a shape of it, and the stage paints into that shape. */
    @Test
    fun `a brush subtracted from a radial is painted into`() {
        open()
        viewModel.selectPanel(EditorPanel.MASKS)
        viewModel.addMask(MaskShape.RADIAL)
        assertFalse(state.isPainting)
        viewModel.addMaskComponent(state.selectedMaskId!!, MaskShape.BRUSH, MaskOp.SUBTRACT)

        assertTrue(state.isPainting)
        paint(0.5f, 0.5f)
        assertEquals(MaskOp.SUBTRACT, state.selectedMask!!.components[1].op)
        assertEquals(1, brush.strokes.size)
    }

    /** The open mask reaches the stage with no adjustments, so its red shows while it is drawn. */
    @Test
    fun `the open mask is drawn before it has adjustments`() {
        open()
        viewModel.selectPanel(EditorPanel.MASKS)
        viewModel.addMask(MaskShape.BRUSH)
        paint(0.5f, 0.5f)

        assertEquals(state.selectedMaskId, state.renderParameters.masks.single().id)
        assertTrue(state.recipe!!.masks.single().adjustments.isNeutral)
    }

    /** What is in the hand is remembered by the device, and read back by the next editor. */
    @Test
    fun `the brush settings are remembered`() {
        open()
        viewModel.setBrushSetting(BrushSetting.SIZE, 60f)
        viewModel.finishBrushSetting()
        viewModel.setBrushAutoMask(true)

        val restored = BrushTool.decode(storedBrush)
        assertEquals(60f, restored.paint.size)
        assertTrue(restored.paint.autoMask)
        assertFalse(restored.erase.autoMask)
    }

    @Test
    fun `an unreadable stored brush falls back to the defaults`() {
        assertEquals(BrushTool(), BrushTool.decode("nonsense"))
        assertEquals(BrushTool(), BrushTool.decode(null))
        val tool = BrushTool(paint = BrushSettings(size = 10f, autoMask = true), erasing = true)
        assertEquals(tool, BrushTool.decode(tool.encode()))
    }
}
