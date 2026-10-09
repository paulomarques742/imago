package eu.studio742.imago.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.render.NeutralWhiteBalance
import eu.studio742.imago.core.render.PhotoBounds
import eu.studio742.imago.core.render.RenderParameters
import eu.studio742.imago.core.render.frameGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selector's loupe on a photo that is warm on the left and cool on the right: it reads what is
 * under it, a finger drags it by how far it moves, and a mouse click keeps the pick.
 */
@OptIn(ExperimentalTestApi::class)
class WhiteBalancePickerOverlayTest {
    private val photo = ImageBitmap(200, 100).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 100f, 100f, Paint().apply { color = Color(0xFFA0968C) })
        canvas.drawRect(100f, 0f, 200f, 100f, Paint().apply { color = Color(0xFF8C96A0) })
    }

    private fun picker(onPick: (NeutralWhiteBalance?) -> Unit, onAccept: () -> Unit = {}) = runComposeUiTest {
        setContent {
            val scale = LocalDensity.current.density
            Box(Modifier.size(200.dp, 100.dp)) {
                WhiteBalancePickerOverlay(
                    bounds = PhotoBounds(0f, 0f, 200f * scale, 100f * scale),
                    geometry = RenderParameters().frameGeometry(200, 100),
                    photo = photo,
                    onPick = onPick,
                    onAccept = onAccept,
                    modifier = Modifier.fillMaxSize().testTag("picker"),
                )
            }
        }
        val node = onNodeWithTag("picker")
        node.performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        // From anywhere, the drag carries the loupe by its own length: half the width to the left.
        node.performTouchInput { swipe(Offset(width * 0.9f, height * 0.8f), Offset(width * 0.4f, height * 0.8f), 300) }
        node.performMouseInput { click(Offset(width * 0.25f, height / 2f)) }
    }

    @Test fun theLoupeReadsWhatIsUnderItAndAMouseClickKeepsIt() {
        val picks = mutableListOf<NeutralWhiteBalance?>()
        var accepted = 0
        picker(onPick = { picks += it }, onAccept = { accepted++ })

        assertTrue("a tap on the cool side warms it", picks.first()!!.temperature > 0f)
        assertTrue("dragged onto the warm side, it cools", picks.last()!!.temperature < 0f)
        assertEquals(1, accepted)
    }
}
