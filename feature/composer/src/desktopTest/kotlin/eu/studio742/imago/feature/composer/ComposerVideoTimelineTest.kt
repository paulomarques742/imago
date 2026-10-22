package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.composition.PageVideoPlan
import eu.studio742.imago.core.composition.VideoTiming
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val NOW = "2026-10-22T10:00:00Z"

/**
 * The page's timeline and the export dialog of a composition with video.
 *
 * The timeline edits go to the model in page time and leave the page's length alone; the drags are
 * played with a finger over the real composable; and the export of a page with video asks for a
 * quality and never for JPEG or PNG.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class ComposerVideoTimelineTest {
    private fun video(id: String, timing: VideoTiming, zIndex: Int = 0) = CompositionElement.Video(
        id = id,
        transform = ElementTransform(NormalizedRect(.1f, .1f, .5f, .5f)),
        zIndex = zIndex,
        pageIndex = 0,
        media = MediaReference(assetId = "asset-$id", checksum = "", fileName = "$id.mp4", durationMs = 10_000),
        timing = timing,
    )

    private fun project(pageMs: Long = 5_000, pages: Int = 1, vararg elements: CompositionElement) = CompositionProject(
        id = "p",
        name = "Clips",
        format = PageFormatPreset.STORY_9_16.format,
        pages = List(pages) { CompositionPage(id = "page-$it", index = it, durationMs = pageMs) },
        elements = elements.toList(),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private class OneProject(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll(): Flow<List<CompositionProject>> = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored.takeIf { it.id == id }
        override suspend fun save(project: CompositionProject) { stored = project }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (original: String) -> String): CompositionProject = error("not used")
    }

    private fun opened(project: CompositionProject): ComposerEditorViewModel {
        val fakes = FakeComposerRepos
        return ComposerEditorViewModel(OneProject(project), fakes.templates, fakes.media, fakes.recipes, fakes.exports, fakes.brandKits)
            .also { it.open(project.id) }
    }

    private val ComposerEditorViewModel.project get() = checkNotNull(state.value.project)
    private fun ComposerEditorViewModel.timing(id: String) =
        (project.elements.first { it.id == id } as CompositionElement.Video).timing

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `moving a clip past the end does not stretch the page, and its end sets it`() {
        val viewModel = opened(project(5_000, 1, video("v", VideoTiming(trimEndMs = 3_000))))
        viewModel.moveVideoClip("v", 4_000)
        assertEquals(4_000, viewModel.timing("v").startOffsetMs)
        assertEquals(5_000, viewModel.project.pages[0].durationMs)

        viewModel.setPageDuration(0, 7_000)
        assertEquals(7_000, viewModel.project.pages[0].durationMs)
        viewModel.setPageDuration(0, 100)
        assertEquals(1_000, viewModel.project.pages[0].durationMs)
    }

    @Test
    fun `trimming from the left moves the in-point and the start together`() {
        val viewModel = opened(project(5_000, 1, video("v", VideoTiming(trimEndMs = 3_000))))
        viewModel.trimVideoClipStart("v", 1_000)
        assertEquals(VideoTiming(trimStartMs = 1_000, trimEndMs = 3_000, startOffsetMs = 1_000), viewModel.timing("v"))
        viewModel.trimVideoClipEnd("v", 2_500)
        assertEquals(2_500, viewModel.timing("v").trimEndMs)
    }

    @Test
    fun `a whole drag is a single undo`() {
        val viewModel = opened(project(5_000, 1, video("v", VideoTiming(trimEndMs = 3_000))))
        viewModel.beginGesture(stage = false)
        listOf(500L, 900L, 1_300L).forEach { viewModel.moveVideoClip("v", it) }
        viewModel.endGesture(stage = false)
        assertEquals(1_300, viewModel.timing("v").startOffsetMs)
        viewModel.undo()
        assertEquals(0, viewModel.timing("v").startOffsetMs)
    }

    @Test
    fun `turning a clip's sound on takes away the solo that kept it silent`() {
        val viewModel = opened(
            project(
                5_000, 1,
                video("a", VideoTiming(trimEndMs = 3_000, muted = true), zIndex = 0),
                video("b", VideoTiming(trimEndMs = 3_000, solo = true), zIndex = 1),
            ),
        )
        fun heard() = PageVideoPlan.of(viewModel.project, 0).clips.filter { it.audible }.map { it.video.id }.toSet()
        assertEquals(setOf("b"), heard())
        viewModel.setVideoAudible("a", true)
        assertEquals(setOf("a", "b"), heard())
        viewModel.setVideoAudible("b", false)
        assertEquals(setOf("a"), heard())
    }

    @Test
    fun `dragging the middle of a clip moves it, and dragging the gold line sets the page's length`() = runComposeUiTest {
        val viewModel = opened(project(5_000, 1, video("v", VideoTiming(trimEndMs = 2_000))))
        setContent {
            PageVideoTimeline(viewModel.project, 0, "v", viewModel, Modifier.width(440.dp))
        }
        val description = runBlocking { getString(Res.string.composer_clip_description, "v.mp4", tenths(0f), tenths(2f), tenths(0f)) }
        onNodeWithContentDescription(description).performTouchInput {
            swipe(center, center + Offset(80f, 0f), durationMillis = 300)
        }
        waitForIdle()
        val moved = viewModel.timing("v")
        assertTrue("the clip should have moved later: $moved", moved.startOffsetMs > 0)
        assertEquals("moving does not trim", 2_000, moved.clipDurationMs())

        val endLabel = runBlocking { getString(Res.string.composer_timeline_video_end) }
        val length = runBlocking { getString(Res.string.composer_timeline_length, tenths(5f)) }
        onNodeWithContentDescription("$endLabel, $length").performTouchInput {
            // The gold line sits at 5 s on a ruler that shows more than that; start right on it.
            val span = timelineSpan(5_000, 2_000 + moved.startOffsetMs).toFloat()
            val x = width * 5_000 / span
            swipe(Offset(x, centerY), Offset(x - 60f, centerY), durationMillis = 300)
        }
        waitForIdle()
        assertTrue("the page should be shorter", viewModel.project.pages[0].durationMs < 5_000)
    }

    @Test
    fun `exporting a page with video asks for the quality and never for JPEG or PNG`() = runComposeUiTest {
        val project = project(5_000, 1, video("v", VideoTiming(trimEndMs = 2_000)))
        setContent { ExportDialog(project, currentPage = 0, onDismiss = {}, onExport = { _, _, _, _ -> }) }
        val high = runBlocking { getString(Res.string.composer_video_quality_high) }
        onNodeWithText(high).assertExists()
        // High and Maximum are both 1080 × 1920 on a story page; the second says it compresses less.
        val finer = runBlocking { getString(Res.string.composer_video_quality_detail_finer, 1080, 1920) }
        onNodeWithText(finer).assertExists()
        onNodeWithText(runBlocking { getString(Res.string.composer_video_quality_detail, 720, 1280) }).assertExists()
        assertFalse(hasText("JPEG"))
        assertFalse(hasText("PNG"))
    }

    @Test
    fun `a project mixing both offers each kind its own choice`() = runComposeUiTest {
        val project = project(5_000, 2, video("v", VideoTiming(trimEndMs = 2_000)))
        setContent { ExportDialog(project, currentPage = 0, onDismiss = {}, onExport = { _, _, _, _ -> }) }
        val withoutVideo = runBlocking { getString(Res.string.composer_export_image_format) }
        onNodeWithText(withoutVideo).assertExists()
        assertTrue(hasText("JPEG"))
        // Only this page — the one with the video — and the image formats go away.
        onNodeWithText(runBlocking { getString(Res.string.composer_export_scope_current) }).performClick()
        waitForIdle()
        assertFalse(hasText("JPEG"))
    }

    private fun VideoTiming.clipDurationMs() = trimEndMs - trimStartMs

    private fun androidx.compose.ui.test.ComposeUiTest.hasText(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** As the timeline writes seconds: in the machine's locale, with one decimal. */
    private fun tenths(seconds: Float) = String.format(java.util.Locale.getDefault(), "%.1f", seconds)
}
