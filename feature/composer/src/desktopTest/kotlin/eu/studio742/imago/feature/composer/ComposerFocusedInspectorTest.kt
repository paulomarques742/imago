package eu.studio742.imago.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.designsystem.ImagoSpacing
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer in focused adjustment mode, with the controls the text tool uses.
 *
 * While a value is being dragged, only that value stays on scene: the alignment, the weight chips, the
 * effect switches and the other sliders all leave. It is the same the photo editor does, and it is what
 * keeps the composer from looking like another app.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposerFocusedInspectorTest {
    private val width = 420
    private val height = 460

    private val text = CompositionElement.Text(
        id = "t",
        transform = ElementTransform(NormalizedRect(.1f, .1f, .5f, .2f)),
        zIndex = 0,
        text = "Tarde no rio",
    )

    private fun render(focus: SliderFocus?): Image {
        val actions = FakeComposerRepos.viewModel()
        val focusState = ComposerFocusState().apply { this.focus = focus }
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CompositionLocalProvider(LocalComposerFocus provides focusState) {
                Column(Modifier.fillMaxSize().background(Color.Black).padding(ImagoSpacing.Sm)) {
                    TextStyleControls(text, actions, rail = false, onPickColor = {})
                }
            }
        }
        return try { scene.render() } finally { scene.close() }
    }

    /** How many pixels stand out from the black background. */
    private fun Image.ink(fromY: Int = 0, toY: Int = height): Int {
        val bitmap = Bitmap().also {
            it.allocN32Pixels(width, height)
            readPixels(it)
        }
        var count = 0
        for (y in fromY until toY) for (x in 0 until width) {
            val pixel = bitmap.getColor(x, y)
            if ((0..2).sumOf { (pixel shr (it * 8)) and 0xFF } > 90) count++
        }
        return count
    }

    @Test
    fun `at rest, the text controls are all in view`() {
        assertTrue("the text inspector drew nothing", render(focus = null).ink() > 2000)
    }

    @Test
    fun `while dragging a value, only that value stays on scene`() {
        val idle = render(focus = null).ink()
        val focused = render(focus = SliderFocus("Tamanho", "Tamanho", 48f, MIN_TEXT_SIZE..MAX_TEXT_SIZE, DEFAULT_TEXT_SIZE)).ink()
        assertTrue("the active adjustment had to stay visible", focused > 0)
        // A row of twenty-odd stays: less than a fifth of what the whole inspector draws.
        assertTrue("the rest of the inspector had to leave the scene: $focused against $idle", focused < idle / 5)
    }
}
