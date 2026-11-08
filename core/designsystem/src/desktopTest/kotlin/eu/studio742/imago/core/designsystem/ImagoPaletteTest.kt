package eu.studio742.imago.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ImagoPaletteTest {
    private fun contrast(foreground: Color, background: Color): Double {
        val solid = foreground.compositeOver(background).luminance().toDouble()
        val under = background.luminance().toDouble()
        return (max(solid, under) + .05) / (min(solid, under) + .05)
    }

    private val palettes = listOf(ImagoDarkPalette, ImagoLightPalette)

    @Test
    fun `no choice and an unknown one read as the system`() {
        assertEquals(AppearancePreference.SYSTEM, AppearancePreference.of(null))
        assertEquals(AppearancePreference.SYSTEM, AppearancePreference.of(""))
        assertEquals(AppearancePreference.SYSTEM, AppearancePreference.of("SEPIA"))
    }

    @Test
    fun `a stored choice reads back`() {
        for (preference in AppearancePreference.entries) assertEquals(preference, AppearancePreference.of(preference.name))
    }

    @Test
    fun `the system decides only while it is the choice`() {
        assertSame(ImagoDarkPalette, AppearancePreference.SYSTEM.palette(systemIsDark = true))
        assertSame(ImagoLightPalette, AppearancePreference.SYSTEM.palette(systemIsDark = false))
        assertSame(ImagoLightPalette, AppearancePreference.LIGHT.palette(systemIsDark = true))
        assertSame(ImagoDarkPalette, AppearancePreference.DARK.palette(systemIsDark = false))
    }

    @Test
    fun `each palette says which way it goes`() {
        assertFalse(ImagoDarkPalette.isLight)
        assertTrue(ImagoLightPalette.isLight)
    }

    @Test
    fun `text and the roles that carry meaning read on every surface`() {
        for (palette in palettes) for (surface in listOf(palette.background, palette.surface, palette.elevated, palette.navigation)) {
            assertTrue("text ${palette.isLight}", contrast(palette.text, surface) >= 7.0)
            assertTrue("secondary ${palette.isLight}", contrast(palette.secondaryText, surface) >= 4.5)
            assertTrue("accent ${palette.isLight}", contrast(palette.accent, surface) >= 4.5)
            assertTrue("emphasis ${palette.isLight}", contrast(palette.emphasis, surface) >= 4.5)
            assertTrue("error ${palette.isLight}", contrast(palette.error, surface) >= 4.5)
            assertTrue("danger ${palette.isLight}", contrast(palette.danger, surface) >= 4.5)
        }
    }

    /** The chrome over the stage uses the same roles as anywhere else, so the neutral grey has to carry them. */
    @Test
    fun `the text reads on the workspace`() {
        for (palette in palettes) assertTrue(contrast(palette.text, palette.workspace) >= 4.5)
    }

    @Test
    fun `what sits on a filled control reads`() {
        for (palette in palettes) {
            assertTrue(contrast(palette.onSelectedChoiceSurface, palette.selectedChoiceSurface) >= 7.0)
            assertTrue(contrast(palette.onEmphasis, palette.emphasis) >= 7.0)
            assertTrue(contrast(palette.onAccent, palette.accent) >= 4.5)
            // An icon, not text: 3:1 is what a graphic needs.
            assertTrue(contrast(palette.onDanger, palette.dangerFill) >= 3.0)
        }
    }

    @Test
    fun `the workspace of the light theme has no colour of its own`() {
        val grey = ImagoLightPalette.workspace
        assertEquals(grey.red, grey.green, 1e-3f)
        assertEquals(grey.green, grey.blue, 1e-3f)
    }

    /** A photo does not change with the theme, so what is drawn on it must not either. */
    @Test
    fun `what is drawn on a photo is the same in every theme`() {
        assertEquals(ImagoDarkPalette.onImage, ImagoLightPalette.onImage)
        assertEquals(ImagoDarkPalette.imageShade, ImagoLightPalette.imageShade)
    }
}
