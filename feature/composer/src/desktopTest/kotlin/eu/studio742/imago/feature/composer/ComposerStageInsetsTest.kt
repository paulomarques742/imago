package eu.studio742.imago.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.composition.StrokeKind
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage draws edge to edge, under the bars.
 *
 * With the bars in view, the page settles in what is left between them — none of it is hidden. While
 * a value is dragged the insets go to zero, the page grows and takes the whole screen: that is what
 * gives something to see under the panel that turned transparent.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposerStageInsetsTest {
    // A real phone window: with one too small, the page hits the 220dp minimum and that minimum — and
    // not the insets — starts deciding where it ends.
    private val width = 840
    private val height = 1600
    private val density = 2f
    private val pageArgb = 0xFF2B3A55

    private val project = CompositionProject(
        id = "p",
        name = "amostra",
        format = PageFormatPreset.STORY_9_16.format,
        pages = listOf(CompositionPage("pagina", 0)),
        createdAt = "",
        updatedAt = "",
        background = CompositionBackground.Solid(pageArgb),
    )

    /** The rows where the page appears, and how many of its pixels show. */
    private fun pageRows(insets: ComposerStageInsets): Triple<Int, Int, Int> {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ComposerCanvas(
                    project = project,
                    state = ComposerEditorUiState(project = project, isLoading = false),
                    drawing = false,
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
                    onDrawing = {},
                    onPickMediaFor = {},
                    onFrameMedia = {},
                    stageInsets = insets,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val image = try { scene.render() } finally { scene.close() }
        val bitmap = Bitmap().also {
            it.allocN32Pixels(width, height)
            image.readPixels(it)
        }
        var first = height
        var last = -1
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            if ((bitmap.getColor(x, y) and 0xFFFFFF) == (pageArgb and 0xFFFFFF).toInt()) {
                if (y < first) first = y
                if (y > last) last = y
                count++
            }
        }
        return Triple(first, last, count)
    }

    @Test
    fun `with the bars in view, the page settles between them`() {
        val top = 64
        val bottom = 200
        val (first, last, count) = pageRows(ComposerStageInsets(top = top.dp, bottom = bottom.dp))
        assertTrue("the page was not drawn", count > 0)
        assertTrue("the page went under the top bar: it starts at $first", first >= top * density)
        assertTrue(
            "the page was hidden behind the panel: it ends at $last",
            last <= height - bottom * density,
        )
    }

    @Test
    fun `without the bars, the page grows to the whole screen`() {
        val (_, _, withBars) = pageRows(ComposerStageInsets(top = 64.dp, bottom = 200.dp))
        val (first, last, withoutBars) = pageRows(ComposerStageInsets())
        assertTrue("the page had to grow: $withoutBars against $withBars", withoutBars > withBars * 1.8)
        // And it grows both ways: it comes to take almost the whole height of the window.
        assertTrue("it should start near the top, it starts at $first", first < 40)
        assertTrue("it should end near the bottom, it ends at $last", last > height - 40)
    }
}
