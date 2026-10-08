package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focused adjustment mode: while a value is being dragged, the others leave the scene.
 *
 * It is what makes the interface disappear under the finger, in the photo editor and in the composer
 * — both use this same component, which is why the test lives in the design system.
 *
 * The composition is drawn to an image and the pixels that are not background are counted. The look
 * of each row is not checked: what is checked is that the row not being moved really stops being
 * visible.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ImagoParameterSliderTest {
    private val width = 400
    private val height = 200

    private fun render(editingKey: Any?): Image {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            Column(Modifier.fillMaxSize().background(Color.Black).padding(ImagoSpacing.Sm)) {
                Slider("Exposure", "exposure", editingKey)
                Slider("Contrast", "contrast", editingKey)
            }
        }
        return try { scene.render() } finally { scene.close() }
    }

    @Composable
    private fun Slider(label: String, key: String, editingKey: Any?) {
        ImagoParameterSlider(
            label = label,
            value = 0f,
            neutral = 0f,
            range = -100f..100f,
            pointerKey = key,
            editingKey = editingKey,
            onEditing = {},
            valueText = { "0" },
            onValueChange = {},
            onValueChangeFinished = {},
            onReset = {},
        )
    }

    /** How many pixels show in each half of the image: the first slider at the top, the second at the bottom. */
    private fun Image.inkPerHalf(): Pair<Int, Int> {
        val bitmap = org.jetbrains.skia.Bitmap().also {
            it.allocN32Pixels(width, height)
            readPixels(it)
        }
        var top = 0
        var bottom = 0
        for (y in 0 until height) for (x in 0 until width) {
            // The background is opaque black; everything that stands out from it counts.
            val pixel = bitmap.getColor(x, y)
            val luma = (0..2).sumOf { (pixel shr (it * 8)) and 0xFF }
            if (luma > 90) if (y < height / 2) top++ else bottom++
        }
        return top to bottom
    }

    @Test
    fun `at rest, both adjustments show`() {
        val (top, bottom) = render(editingKey = null).inkPerHalf()
        assertTrue("nothing drawn at the top", top > 200)
        assertTrue("nothing drawn at the bottom", bottom > 200)
    }

    /**
     * The row's rounded background used to be a clip, and at rest the row has no side padding: the
     * corner cut off the first letter of every name. This counts the ink in the bit of the corner a clip
     * would cut away — a letter drawn whole leaves some there.
     */
    @Test
    fun `at rest, the rounded corner does not cut the first letter`() {
        val density = 3f
        val sceneWidth = 600
        val sceneHeight = 240
        val scene = ImageComposeScene(width = sceneWidth, height = sceneHeight, density = Density(density)) {
            Column(Modifier.fillMaxSize().background(Color.Black)) { Slider("W", "w", editingKey = null) }
        }
        val image = try { scene.render() } finally { scene.close() }
        val bitmap = org.jetbrains.skia.Bitmap().also {
            it.allocN32Pixels(sceneWidth, sceneHeight)
            image.readPixels(it)
        }
        val radius = ImagoRadii.Medium.value * density
        var cutAway = 0
        for (y in 0 until radius.toInt()) for (x in 0 until radius.toInt()) {
            val dx = radius - x - 0.5f
            val dy = radius - y - 0.5f
            // A clip is anti-aliased: the pixels it half covers still show. Only those well clear of
            // the arc are ones a clip can never paint.
            val clear = radius + 1.5f
            if (dx * dx + dy * dy <= clear * clear) continue
            val pixel = bitmap.getColor(x, y)
            val luma = (0..2).sumOf { (pixel shr (it * 8)) and 0xFF }
            if (luma > 90) cutAway++
        }
        assertTrue("the corner cut the first letter of the name", cutAway > 0)
    }

    @Test
    fun `while dragging a value, the other adjustment disappears`() {
        val (top, bottom) = render(editingKey = "exposure").inkPerHalf()
        assertTrue("the active adjustment had to stay in view", top > 200)
        assertEquals("the adjustment not being moved had to leave the scene", 0, bottom)
    }
}
