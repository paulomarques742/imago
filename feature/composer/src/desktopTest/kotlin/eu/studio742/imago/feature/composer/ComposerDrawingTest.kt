package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
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
        setContent {
            ComposerCanvas(
                project = project,
                state = ComposerEditorUiState(project = project, isLoading = false),
                drawing = true,
                drawingKind = StrokeKind.PEN,
                onCancelDrawing = {},
                multiSelect = false,
                positioningBackground = false,
                positioningMedia = false,
                previewUrl = { "" },
                thumbnailUrl = { "" },
                videoPlaybackUrl = { "" },
                apiKey = { "" },
                actions = FakeComposerRepos.viewModel(),
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
        assertEquals(2, strokes.size)
        assertTrue("each stroke should carry its points: ${strokes.map { it.size }}", strokes.all { it.size > 1 })
    }
}
