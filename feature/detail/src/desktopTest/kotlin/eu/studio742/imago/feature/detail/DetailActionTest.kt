package eu.studio742.imago.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import eu.studio742.imago.core.designsystem.ImagoDarkPalette
import eu.studio742.imago.core.designsystem.ImagoLightPalette
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.ImagoUiPalette
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class DetailActionTest {
    /** How many pixels of the action are exactly the red of the fill. */
    private fun redPixels(palette: ImagoUiPalette, destructive: Boolean): Int {
        val scene = ImageComposeScene(width = 120, height = 120, density = Density(1f)) {
            ImagoTheme(palette) {
                Box(Modifier.fillMaxSize()) {
                    DetailAction(Icons.Outlined.Delete, "Delete", {}, destructive = destructive)
                }
            }
        }
        return try {
            val bitmap = Bitmap().also { it.allocN32Pixels(120, 120); scene.render().readPixels(it) }
            val red = palette.dangerFill.toArgb()
            (0 until 120).sumOf { x -> (0 until 120).count { y -> bitmap.getColor(x, y) == red } }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a destructive action is a filled square in both themes`() {
        for (palette in listOf(ImagoDarkPalette, ImagoLightPalette)) {
            val red = redPixels(palette, destructive = true)
            // The 32 by 32 square, less the icon and the rounded corners.
            assertTrue("$red red pixels with isLight=${palette.isLight}", red in 500..1024)
        }
    }

    @Test
    fun `the other actions have no square`() {
        assertEquals(0, redPixels(ImagoLightPalette, destructive = false))
    }
}
