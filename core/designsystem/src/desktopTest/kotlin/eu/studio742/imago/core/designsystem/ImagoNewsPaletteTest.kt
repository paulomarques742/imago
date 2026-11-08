package eu.studio742.imago.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ImagoNewsPaletteTest {
    private fun contrast(foreground: Color, background: Color): Double {
        val solid = foreground.compositeOver(background).luminance().toDouble()
        val under = background.luminance().toDouble()
        return (max(solid, under) + .05) / (min(solid, under) + .05)
    }

    private fun assertReads(palette: ImagoNewsPalette, under: Color, where: String) {
        assertTrue("title on $where", contrast(palette.title, under) >= 7.0)
        assertTrue("body on $where", contrast(palette.body, under) >= 4.5)
        assertTrue("skip on $where", contrast(palette.quiet, under) >= 4.5)
        assertTrue("accent on $where", contrast(palette.accent, under) >= 4.5)
    }

    @Test
    fun `each deck reads on its own background`() {
        for (palette in listOf(ImagoNewsDark, ImagoNewsLight)) assertReads(palette, palette.background, "background ${palette.isLight}")
    }

    /** The light glows are pale enough that the text needs none of the fades over them. */
    @Test
    fun `the light deck reads on every glow`() {
        ImagoNewsLight.backdrop.forEachIndexed { index, glow -> assertReads(ImagoNewsLight, glow, "glow $index") }
    }

    @Test
    fun `the button reads on both decks`() {
        for (palette in listOf(ImagoNewsDark, ImagoNewsLight)) {
            assertTrue(contrast(palette.onAction, palette.action) >= 7.0)
            assertTrue(contrast(palette.onFeatured, palette.featured) >= 4.5)
        }
    }

    @Test
    fun `the decks say which way they go`() {
        assertFalse(ImagoNewsDark.isLight)
        assertTrue(ImagoNewsLight.isLight)
    }
}
