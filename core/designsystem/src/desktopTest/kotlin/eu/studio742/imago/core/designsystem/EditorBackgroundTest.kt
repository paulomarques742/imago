package eu.studio742.imago.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class EditorBackgroundTest {
    private fun contrast(foreground: Color, background: Color): Double {
        val solid = foreground.compositeOver(background).luminance().toDouble()
        val under = background.luminance().toDouble()
        return (max(solid, under) + .05) / (min(solid, under) + .05)
    }

    @Test
    fun `no choice and an unknown one read as automatic`() {
        assertEquals(EditorBackground.AUTOMATIC, EditorBackground.of(null))
        assertEquals(EditorBackground.AUTOMATIC, EditorBackground.of("SEPIA"))
    }

    @Test
    fun `a stored choice reads back`() {
        for (background in EditorBackground.entries) assertEquals(background, EditorBackground.of(background.name))
    }

    @Test
    fun `automatic is the theme's own stage`() {
        assertSame(ImagoLightPalette, ImagoLightPalette.onStage(EditorBackground.AUTOMATIC))
        assertSame(ImagoDarkPalette, ImagoDarkPalette.onStage(EditorBackground.AUTOMATIC))
    }

    @Test
    fun `a chosen background is the stage in either theme`() {
        for (theme in listOf(ImagoDarkPalette, ImagoLightPalette)) {
            assertEquals(Color.White, theme.onStage(EditorBackground.WHITE).workspace)
            assertEquals(Color.Black, theme.onStage(EditorBackground.BLACK).workspace)
        }
    }

    /** The case the theme alone gets wrong: white icons of the dark theme on a white stage. */
    @Test
    fun `the bar over the stage reads on every background in both themes`() {
        for (theme in listOf(ImagoDarkPalette, ImagoLightPalette)) for (background in EditorBackground.entries) {
            val stage = theme.onStage(background)
            assertTrue("$background on light=${theme.isLight}", contrast(stage.text, stage.workspace) >= 4.5)
        }
    }

    @Test
    fun `every background is a neutral grey, which does not tint how a photo reads`() {
        for (background in EditorBackground.entries) {
            val grey = background.grey ?: continue
            assertEquals(grey.red, grey.green, 1e-3f)
            assertEquals(grey.green, grey.blue, 1e-3f)
        }
    }
}
