package eu.studio742.imago.feature.composer

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.composition.CompositionFonts

/**
 * The bundled fonts on desktop: the files are read from the resources, and the weight of the variable
 * families reaches the drawing — which is precisely what the resources library's `Font(resource, …)`
 * does not do on this target.
 */
class CompositionFontsDesktopTest {

    @Test
    fun `every catalogue family loads with its name and the Portuguese characters`() = runBlocking {
        for (font in CompositionFonts.all) {
            val face = SkiaCompositionFonts.typeface(font.id, 400)
            assertEquals(font.displayName, face.familyName)
            val glyphs = face.getStringGlyphs("ãõçÇáéêô€")
            assertTrue("${font.displayName} does not have every character", glyphs.none { it.toInt() == 0 })
        }
    }

    @Test
    fun `a variable family's weight changes the drawing`() = runBlocking {
        // Montserrat has Thin as the file's default weight: if the axis were not applied, the two
        // weights would measure exactly the same. Applied, Black is ~9% wider.
        val thin = SkiaCompositionFonts.typeface(CompositionFonts.Montserrat.id, 100)
        val black = SkiaCompositionFonts.typeface(CompositionFonts.Montserrat.id, 900)
        val thinWidth = Font(thin, 48f).use { it.measureTextWidth("Marca") }
        val blackWidth = Font(black, 48f).use { it.measureTextWidth("Marca") }
        assertTrue("900 mede $blackWidth, 100 mede $thinWidth", blackWidth > thinWidth * 1.05f)
    }

    @Test
    fun `the renderer finds the font loaded before drawing`() = runBlocking {
        SkiaCompositionFonts.typeface("playfair-display", 700)
        assertNotNull(SkiaCompositionFonts.loaded("Playfair Display", 700))
        // An old name reaches the same typeface as the catalogue id.
        SkiaCompositionFonts.typeface("inter", 400)
        assertEquals(SkiaCompositionFonts.loaded("inter", 400), SkiaCompositionFonts.loaded("Sans", 400))
    }
}
