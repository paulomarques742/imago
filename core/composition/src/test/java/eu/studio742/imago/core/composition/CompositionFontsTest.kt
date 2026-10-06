package eu.studio742.imago.core.composition

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The font catalogue and the reading of the names saved before it. */
class CompositionFontsTest {

    @Test
    fun `ids are unique, because they are what is saved`() {
        val ids = CompositionFonts.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `resolve accepts the id and the displayed name`() {
        assertEquals(CompositionFonts.PlayfairDisplay, CompositionFonts.resolve("playfair-display"))
        assertEquals(CompositionFonts.PlayfairDisplay, CompositionFonts.resolve("Playfair Display"))
        assertEquals(CompositionFonts.PlayfairDisplay, CompositionFonts.resolve("  playfair display "))
    }

    @Test
    fun `the generic names from before the catalogue still open`() {
        assertEquals(CompositionFonts.Inter, CompositionFonts.resolve("Sans"))
        assertEquals(CompositionFonts.Lora, CompositionFonts.resolve("Serif"))
        assertEquals(CompositionFonts.JetBrainsMono, CompositionFonts.resolve("Mono"))
    }

    @Test
    fun `an unknown or empty name falls back to the default font`() {
        // "oi" is what the free-text dialog left saved in a real kit.
        assertEquals(CompositionFonts.Default, CompositionFonts.resolve("oi"))
        assertEquals(CompositionFonts.Default, CompositionFonts.resolve(""))
        assertEquals(CompositionFonts.Default, CompositionFonts.resolve(null))
    }

    @Test
    fun `the weight stays within the file's axis`() {
        assertEquals(700, CompositionFonts.Lora.weight(900))
        assertEquals(400, CompositionFonts.Lora.weight(100))
        assertEquals(400, CompositionFonts.BebasNeue.weight(700))
        assertEquals(650, CompositionFonts.Inter.weight(650))
        assertTrue(CompositionFonts.Montserrat.isVariable)
        assertFalse(CompositionFonts.GreatVibes.isVariable)
    }

    @Test
    fun `an old kit and text, without saved fonts, open in the catalogue fonts`() {
        val json = Json { ignoreUnknownKeys = true }
        val kit = json.decodeFromString(BrandKit.serializer(), """{"updatedAt":"2026-08-25T15:57:04Z"}""")
        assertEquals(CompositionFonts.Inter, CompositionFonts.resolve(kit.primaryFont))
        assertEquals(CompositionFonts.Lora, CompositionFonts.resolve(kit.secondaryFont))
        assertEquals(BrandKit.DefaultPalette, kit.paletteArgb)
    }
}
