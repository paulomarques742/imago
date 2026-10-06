package eu.studio742.imago.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.MediaReference

/** As regras do editor da marca. */
class BrandKitEditsTest {
    private val kit = BrandKit(paletteArgb = listOf(RED, GREEN, BLUE), updatedAt = "")

    @Test
    fun `changing a colour touches only that one`() {
        assertEquals(listOf(RED, WHITE, BLUE), BrandKitEdits.setColor(kit, 1, WHITE).paletteArgb)
        assertSame(kit, BrandKitEdits.setColor(kit, 5, WHITE))
    }

    @Test
    fun `a new colour goes in at the end`() {
        assertEquals(listOf(RED, GREEN, BLUE, WHITE), BrandKitEdits.addColor(kit, WHITE).paletteArgb)
    }

    @Test
    fun `the palette never loses the main colour`() {
        val one = BrandKitEdits.removeColor(BrandKitEdits.removeColor(kit, 0), 0)
        assertEquals(listOf(BLUE), one.paletteArgb)
        assertSame(one, BrandKitEdits.removeColor(one, 0))
    }

    @Test
    fun `moving takes the colour to the requested place and pushes the others`() {
        assertEquals(listOf(BLUE, RED, GREEN), BrandKitEdits.moveColor(kit, 2, 0).paletteArgb)
        assertEquals(listOf(GREEN, BLUE, RED), BrandKitEdits.moveColor(kit, 0, 2).paletteArgb)
    }

    @Test
    fun `moving outside the list or to the same place changes nothing`() {
        assertEquals(kit, BrandKitEdits.moveColor(kit, 1, 1))
        assertEquals(kit, BrandKitEdits.moveColor(kit, -1, 2))
        assertEquals(kit, BrandKitEdits.moveColor(kit, 0, 3))
    }

    @Test
    fun `logos are added at the end without repeating`() {
        val first = BrandKitEdits.addLogos(kit, listOf(logo("a"), logo("b")))
        val again = BrandKitEdits.addLogos(first, listOf(logo("b"), logo("c")))
        assertEquals(listOf("a", "b", "c"), again.logos.map { it.assetId })
    }

    @Test
    fun `removing and reordering logos`() {
        val three = BrandKitEdits.addLogos(kit, listOf(logo("a"), logo("b"), logo("c")))
        assertEquals(listOf("a", "c"), BrandKitEdits.removeLogo(three, "b").logos.map { it.assetId })
        assertEquals(listOf("c", "a", "b"), BrandKitEdits.moveLogo(three, 2, 0).logos.map { it.assetId })
    }

    @Test
    fun `every catalogue font has its file`() {
        val files = CompositionFonts.all.map { CompositionFontResources.getValue(it.id) }
        assertEquals(CompositionFonts.all.size, files.toSet().size)
        assertTrue(CompositionFontResources.keys == CompositionFonts.all.map { it.id }.toSet())
    }

    private fun logo(id: String) = MediaReference(assetId = id, checksum = "", fileName = "$id.png")

    private companion object {
        const val RED = 0xFFFF0000
        const val GREEN = 0xFF00FF00
        const val BLUE = 0xFF0000FF
        const val WHITE = 0xFFFFFFFF
    }
}
