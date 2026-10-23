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
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.composition.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drawing on the stage with the pen.
 *
 * The stage froze as soon as a stroke ended: the background-tap gesture, told to stand aside in
 * drawing mode, spun on the main thread instead. A frozen stage never returns from the swipe, so the
 * timeout is what turns the freeze into a failure.
 */
@OptIn(ExperimentalTestApi::class)
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
