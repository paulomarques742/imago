package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.composition.StrokePoint
import eu.studio742.imago.core.data.CompositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.PhotoRecipeSnapshot
import eu.studio742.imago.core.model.EditRecipe
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drawing on the stage with the pen.
 *
 * The stage froze as soon as a stroke ended: the background-tap gesture, told to stand aside in
 * drawing mode, spun on the main thread instead. A frozen stage never returns from the swipe, so the
 * timeout is what turns the freeze into a failure. And a stylus stroke keeps, once stored, the
 * pressure it was drawn with.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class ComposerDrawingTest {
    private val project = CompositionProject(
        id = "p",
        name = "Drawing",
        format = PageFormatPreset.STORY_9_16.format,
        pages = listOf(CompositionPage("page", 0)),
        createdAt = "",
        updatedAt = "",
        background = CompositionBackground.Solid(0xFF2B3A55),
    )

    private class OneProject(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll(): Flow<List<CompositionProject>> = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored.takeIf { it.id == id }
        override suspend fun save(project: CompositionProject) { stored = project }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (original: String) -> String): CompositionProject = error("not used")
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a stored stroke keeps the stylus pressure of each point`() {
        val fakes = FakeComposerRepos
        val viewModel = ComposerEditorViewModel(OneProject(project), fakes.templates, fakes.media, fakes.recipes, fakes.exports, fakes.brandKits)
            .also { it.open(project.id) }
        viewModel.addDrawing(
            listOf(StrokePoint(.1f, .2f, pressure = .2f), StrokePoint(.3f, .4f, pressure = .6f), StrokePoint(.5f, .3f, pressure = .9f)),
        )
        val drawing = checkNotNull(viewModel.state.value.project).elements.filterIsInstance<CompositionElement.Drawing>().single()
        assertEquals(listOf(.2f, .6f, .9f), drawing.points.map { it.pressure })
    }

    @Test(timeout = 30_000)
    fun `two strokes in a row both reach the model and the stage keeps answering`() = runComposeUiTest {
        val strokes = mutableListOf<List<StrokePoint>>()
        val photo = CompositionElement.Photo(
            id = "photo", transform = ElementTransform(NormalizedRect(0f, 0f, 1f, 1f)), zIndex = 0,
            media = MediaReference("asset", "checksum", "photo.jpg"),
            recipe = PhotoRecipeSnapshot(EditRecipe(assetId = "asset", originalChecksum = "checksum", createdAt = "", updatedAt = "")),
        )
        val photoProject = project.copy(elements = listOf(photo))
        val fakes = FakeComposerRepos
        val viewModel = ComposerEditorViewModel(OneProject(photoProject), fakes.templates, fakes.media, fakes.recipes, fakes.exports, fakes.brandKits)
            .also { it.open(project.id) }
        setContent {
            ComposerCanvas(
                project = photoProject,
                state = ComposerEditorUiState(project = photoProject, isLoading = false),
                drawing = true,
                drawingKind = StrokeKind.PEN,
                multiSelect = false,
                positioningBackground = false,
                positioningMedia = false,
                previewUrl = { "" },
                thumbnailUrl = { "" },
                videoPlaybackUrl = { "" },
                apiKey = { "" },
                actions = viewModel,
                onDrawing = { strokes += it },
                onPickMediaFor = {},
                onFrameMedia = {},
                stageInsets = ComposerStageInsets(top = 0.dp, bottom = 0.dp),
                modifier = Modifier.size(400.dp, 800.dp).testTag("stage"),
            )
        }
        repeat(2) { index ->
            onNodeWithTag("stage").performTouchInput {
                val y = height * (.3f + .2f * index)
                swipe(Offset(width * .2f, y), Offset(width * .6f, y + 60f), durationMillis = 300)
            }
            waitForIdle()
        }
        assertEquals(photo, viewModel.state.value.project!!.elements.single())
        assertEquals(2, strokes.size)
        assertTrue("each stroke should carry its points: ${strokes.map { it.size }}", strokes.all { it.size > 1 })
    }

    @Test
    fun `strokes join until two idle seconds and keep gaps after saving`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fakes = FakeComposerRepos
        val viewModel = ComposerEditorViewModel(OneProject(project), fakes.templates, fakes.media, fakes.recipes, fakes.exports, fakes.brandKits)
            .also { it.open(project.id) }
        val first = listOf(StrokePoint(.1f, .2f, .2f), StrokePoint(.3f, .2f, .6f))
        val second = listOf(StrokePoint(.7f, .8f, .5f), StrokePoint(.9f, .8f, .9f))
        viewModel.beginDrawingStroke()
        viewModel.addDrawing(first, colorArgb = 0xFFFF0000, strokeWidth = 12f)
        advanceTimeBy(1_999)
        viewModel.beginDrawingStroke()
        // Holding the next stroke down for longer than two seconds must not finish the element.
        advanceTimeBy(3_000)
        viewModel.addDrawing(second, colorArgb = 0xFFFF0000, strokeWidth = 12f)
        val saved = viewModel.state.value.project!!
        val joined = saved.elements.filterIsInstance<CompositionElement.Drawing>().single()
        assertEquals(2, joined.points.count { it.startsStroke })
        assertEquals(listOf(.2f, .6f, .5f, .9f), joined.points.map { it.pressure })
        assertEquals(0xFFFF0000, joined.colorArgb)
        assertEquals(12f, joined.strokeWidth, 0f)
        val bounds = joined.transform.bounds
        assertEquals(.1f, bounds.x + joined.points[0].x * bounds.width, .0001f)
        assertEquals(.7f, bounds.x + joined.points[2].x * bounds.width, .0001f)
        advanceTimeBy(2_000)
        runCurrent()
        viewModel.beginDrawingStroke()
        viewModel.addDrawing(first, colorArgb = 0xFFFF0000, strokeWidth = 12f)
        assertEquals(2, viewModel.state.value.project!!.elements.size)
        viewModel.finishDrawingSession()
    }

    @Test
    fun `export does not connect separate strokes`() {
        val drawing = CompositionElement.Drawing(
            "drawing", ElementTransform(NormalizedRect(0f, 0f, 1f, 1f)), 0, StrokeKind.PEN,
            listOf(StrokePoint(.1f, .2f), StrokePoint(.3f, .2f),
                StrokePoint(.7f, .8f, startsStroke = true), StrokePoint(.9f, .8f)),
            0xFFFFFFFF, 12f,
        )
        val image = ImageIO.read(ByteArrayInputStream(CompositionRenderer().render(project.copy(elements = listOf(drawing)), 0, emptyMap(), size = 400 to 800)))
        assertEquals(0xFF2B3A55.toInt(), image.getRGB(200, 400))
        assertEquals(0xFFFFFFFF.toInt(), image.getRGB(80, 160))
        assertEquals(0xFFFFFFFF.toInt(), image.getRGB(320, 640))
    }

    @Test(timeout = 30_000)
    fun `drawing has no stage notice and choosing another toolbar tool leaves the mode`() = runComposeUiTest {
        val fakes = FakeComposerRepos
        val viewModel = ComposerEditorViewModel(OneProject(project), fakes.templates, fakes.media, fakes.recipes, fakes.exports, fakes.brandKits)
            .also { it.open(project.id) }
        var drawingLabel = ""
        var penLabel = ""
        var shapeLabel = ""
        var rectangleLabel = ""
        var textLabel = ""
        var exitLabel = ""
        var hint = ""
        setContent {
            drawingLabel = stringResource(Res.string.composer_drawing)
            penLabel = stringResource(Res.string.composer_pen)
            shapeLabel = stringResource(Res.string.composer_shape)
            rectangleLabel = stringResource(Res.string.composer_rectangle)
            textLabel = stringResource(Res.string.composer_text)
            exitLabel = stringResource(Res.string.composer_exit_drawing)
            hint = stringResource(Res.string.composer_drawing_hint, penLabel)
            ComposerEditorScreen(viewModel.state.collectAsState().value, remember { SnackbarHostState() }, {}, viewModel, null, {})
        }
        fun armDrawing() {
            onNodeWithContentDescription(drawingLabel).performScrollTo().performClick()
            onNodeWithText(penLabel).performClick()
            onNodeWithContentDescription(exitLabel).assertExists()
            onNodeWithText(hint).assertDoesNotExist()
        }
        armDrawing()
        // Opening the shape menu already leaves drawing, before a shape is chosen.
        onNodeWithContentDescription(shapeLabel).performScrollTo().performClick()
        onNodeWithContentDescription(exitLabel).assertDoesNotExist()
        onNodeWithText(rectangleLabel).performClick()
        // Choosing a shape arms the stage, and puts nothing on the page until it is touched.
        onNodeWithContentDescription(exitLabel).assertExists()
        assertEquals(0, viewModel.state.value.project!!.elements.filterIsInstance<CompositionElement.Shape>().size)
        armDrawing()
        onNodeWithContentDescription(textLabel).performScrollTo().performClick()
        onNodeWithContentDescription(exitLabel).assertDoesNotExist()
        assertEquals(1, viewModel.state.value.project!!.elements.filterIsInstance<CompositionElement.Text>().size)
    }

    @Test(timeout = 30_000)
    fun `the eyedropper takes the colour under the finger from the page`() = runComposeUiTest {
        val fakes = FakeComposerRepos
        val viewModel = ComposerEditorViewModel(OneProject(project), fakes.templates, fakes.media, fakes.recipes, fakes.exports, fakes.brandKits)
            .also { it.open(project.id) }
        var drawingLabel = ""
        var penLabel = ""
        var colorLabel = ""
        var eyedropperLabel = ""
        setContent {
            drawingLabel = stringResource(Res.string.composer_drawing)
            penLabel = stringResource(Res.string.composer_pen)
            colorLabel = stringResource(Res.string.composer_color)
            eyedropperLabel = stringResource(Res.string.composer_eyedropper)
            ComposerEditorScreen(viewModel.state.collectAsState().value, remember { SnackbarHostState() }, {}, viewModel, null, {})
        }
        onNodeWithContentDescription(drawingLabel).performScrollTo().performClick()
        onNodeWithText(penLabel).performClick()
        onNodeWithContentDescription(colorLabel).performClick()
        onNodeWithContentDescription(eyedropperLabel).performClick()
        // The dialog steps aside, and a touch on the page is a sample and not a stroke.
        onNodeWithContentDescription(eyedropperLabel).assertDoesNotExist()
        onRoot().performTouchInput { click(Offset(150f, centerY)) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription(eyedropperLabel).fetchSemanticsNodes().isNotEmpty() }
        // The page is the test project's background, #2B3A55.
        onAllNodes(hasText("2B3A55", substring = true, ignoreCase = true)).assertCountEquals(1)
        assertEquals(0, viewModel.state.value.project!!.elements.size)
    }

    @Test
    fun `a shape outline sits outside the shape and is gone at zero`() {
        fun render(strokeWidth: Float): java.awt.image.BufferedImage {
            val shape = CompositionElement.Shape(
                "shape", ElementTransform(NormalizedRect(.25f, .25f, .5f, .5f)), 0, eu.studio742.imago.core.composition.ShapeKind.RECTANGLE,
                fillArgb = 0xFFFF0000, strokeArgb = 0xFF0000FF, strokeWidth = strokeWidth,
            )
            return ImageIO.read(ByteArrayInputStream(CompositionRenderer().render(project.copy(elements = listOf(shape)), 0, emptyMap(), size = 400 to 800)))
        }
        // The rectangle is x 100..300, y 200..600 on this 400 x 800 page; 108 reference units are 40 px.
        val outlined = render(108f)
        assertEquals(0xFF0000FF.toInt(), outlined.getRGB(90, 400))
        assertEquals(0xFFFF0000.toInt(), outlined.getRGB(105, 400))
        assertEquals(0xFF2B3A55.toInt(), outlined.getRGB(50, 400))
        val plain = render(0f)
        assertEquals(0xFFFF0000.toInt(), plain.getRGB(101, 400))
        assertEquals(0xFF2B3A55.toInt(), plain.getRGB(99, 400))
    }
}
