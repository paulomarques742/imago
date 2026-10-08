package eu.studio742.imago.feature.editor

import androidx.compose.ui.geometry.Offset
import coil3.PlatformContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.UPRIGHT_GUIDED
import eu.studio742.imago.core.model.UPRIGHT_OFF
import eu.studio742.imago.core.model.UprightGuide
import eu.studio742.imago.core.render.FrameGeometry
import eu.studio742.imago.core.render.Homography
import eu.studio742.imago.core.render.PerspectiveParameters
import eu.studio742.imago.core.render.frameGeometry
import eu.studio742.imago.core.render.pixelHeight
import eu.studio742.imago.core.render.pixelWidth
import kotlin.math.abs
import kotlin.math.hypot
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** The guided Upright through the real view model, over a sample photo. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidedUprightEditingTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${T::class.simpleName}.${method.name} is not used by the guided Upright")
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

    private fun open() = runBlocking {
        viewModel.open(PhotoEditTarget.Recipe(sampleEditorAsset(0), RecipeSource.Saved(saved)))
        withTimeoutOrNull(20_000) { viewModel.state.first { it.bitmap != null && it.recipeEdit != null } }
            ?: error("The sample never opened")
    }

    private val perspective get() = viewModel.state.value.recipe!!.geometry.perspective

    /** Two lines leaning towards each other near the top, as the sides of a building shot from below. */
    private val converging = listOf(
        UprightGuide(0.25f, 0.85f, 0.32f, 0.2f),
        UprightGuide(0.75f, 0.85f, 0.68f, 0.2f),
    )

    private fun draw(guides: List<UprightGuide>) {
        guides.forEachIndexed { index, _ ->
            viewModel.beginGuideGesture()
            viewModel.updateGuides(guides.take(index + 1))
            viewModel.finishGuideGesture()
        }
    }

    /** How far the worst guide leans from its axis on the stage, as the sine of the angle. */
    private fun worstLean(): Float {
        val state = viewModel.state.value
        val bitmap = state.bitmap!!
        val frame = state.renderParameters.frameGeometry(bitmap.pixelWidth, bitmap.pixelHeight)
        val guides = state.recipe!!.geometry.perspective.guides
        val start = FloatArray(2)
        val end = FloatArray(2)
        return guides.maxOf { guide ->
            frame.framedFromImage(guide.x1, guide.y1, start)
            frame.framedFromImage(guide.x2, guide.y2, end)
            val turned = frame.quarterTurns % 2 == 1
            val aspect = (if (turned) bitmap.pixelHeight.toFloat() / bitmap.pixelWidth else bitmap.pixelWidth.toFloat() / bitmap.pixelHeight) *
                frame.cropWidth / frame.cropHeight
            val dx = (end[0] - start[0]) * aspect
            val dy = end[1] - start[1]
            if (abs(dy) > abs(dx)) abs(dx) / hypot(dx, dy) else abs(dy) / hypot(dx, dy)
        }
    }

    @Test
    fun `two guides straighten the photo, one does not yet`() {
        open()
        viewModel.setUprightMode(UPRIGHT_GUIDED)

        draw(converging.take(1))
        assertEquals(0f, perspective.uprightPitch)

        draw(converging)
        assertEquals(UPRIGHT_GUIDED, perspective.upright)
        assertNotEquals(0f, perspective.uprightPitch)
        assertTrue("lean ${worstLean()}", worstLean() < 2e-3f)
        assertTrue(viewModel.state.value.canUndo)
    }

    /** After a quarter turn the angles mean something else: they are solved again from the guides. */
    @Test
    fun `turning the photo keeps the guides straight`() {
        open()
        viewModel.setUprightMode(UPRIGHT_GUIDED)
        draw(converging)

        viewModel.rotateClockwise()

        assertTrue("lean ${worstLean()}", worstLean() < 2e-3f)
    }

    @Test
    fun `a tap is no guide`() {
        open()
        viewModel.setUprightMode(UPRIGHT_GUIDED)

        viewModel.beginGuideGesture()
        viewModel.updateGuides(listOf(UprightGuide(0.5f, 0.5f, 0.5f, 0.5f)))
        viewModel.finishGuideGesture()

        assertEquals(emptyList<UprightGuide>(), perspective.guides)
    }

    @Test
    fun `off forgets the guides and what they found`() {
        open()
        viewModel.setUprightMode(UPRIGHT_GUIDED)
        draw(converging)

        viewModel.setUprightMode(UPRIGHT_OFF)

        assertEquals(UPRIGHT_OFF, perspective.upright)
        assertEquals(emptyList<UprightGuide>(), perspective.guides)
        assertEquals(0f, perspective.uprightPitch)
    }

    @Test
    fun `removing a guide below two undoes the correction`() {
        open()
        viewModel.setUprightMode(UPRIGHT_GUIDED)
        draw(converging)

        viewModel.removeLastGuide()

        assertEquals(1, perspective.guides.size)
        assertEquals(0f, perspective.uprightPitch)
    }

    /** The loupe draws through this conversion: Compose's matrix has to land each point where ours does. */
    @Test
    fun `the compose matrix maps as the homography does`() {
        val homography = FrameGeometry(
            4000,
            3000,
            straighten = 5f,
            quarterTurns = 1,
            perspective = PerspectiveParameters(vertical = -40f, horizontal = 20f),
        ).framedFromImagePixels() * Homography.scale(1.0, 1.0)
        val matrix = homography.toComposeMatrix()
        val expected = FloatArray(2)
        for ((x, y) in listOf(100f to 200f, 2000f to 1500f, 3900f to 2900f)) {
            homography.map(x, y, expected)
            val actual = matrix.map(Offset(x, y))
            assertEquals(expected[0], actual.x, 1e-4f)
            assertEquals(expected[1], actual.y, 1e-4f)
        }
    }
}
