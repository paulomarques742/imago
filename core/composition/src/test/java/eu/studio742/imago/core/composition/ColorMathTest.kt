package eu.studio742.imago.core.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorMathTest {

    @Test
    fun `a round trip keeps the colour`() {
        listOf(0xFFFFFFFF, 0xFF000000, 0xFFD4AF37, 0xFF111111, 0x80336699, 0xFFFF0000).forEach { argb ->
            assertEquals("failed on ${argb.toColorHex()}", argb, argb.toHsva().toArgb())
        }
    }

    @Test
    fun `the brand gold has the expected hue`() {
        val hsva = 0xFFD4AF37L.toHsva()
        assertEquals(46f, hsva.hue, .5f)
        assertEquals(.74f, hsva.saturation, .01f)
        assertEquals(.83f, hsva.value, .01f)
    }

    @Test
    fun `alpha survives the conversion`() {
        assertEquals(.5f, 0x80FFFFFFL.toHsva().alpha, .01f)
        assertEquals(0x80D4AF37L, 0xFFD4AF37L.withColorAlpha(.5f))
    }

    @Test
    fun `the channels never leave the range`() {
        val exagerado = Hsva(hue = 720f, saturation = 4f, value = 9f, alpha = -2f).toArgb()
        assertEquals(0x00FF0000L, exagerado)
    }

    /** ARGB has to fit in 32 unsigned bits — if it slipped into negative, `toInt()` in the exporter
     *  and Compose's packing would give different colours. */
    @Test
    fun `the packed value fits in 32 bits`() {
        val branco = Hsva(0f, 0f, 1f, 1f).toArgb()
        assertEquals(0xFFFFFFFFL, branco)
        assertEquals(0xFFFFFFFFL and 0xFFFFFFFFL, branco)
    }

    @Test
    fun `hexadecimal round trip`() {
        assertEquals("#D4AF37", 0xFFD4AF37L.toColorHex())
        assertEquals("#80D4AF37", 0x80D4AF37L.toColorHex())
        assertEquals(0xFFD4AF37L, parseColorHex("#D4AF37"))
        assertEquals(0xFFD4AF37L, parseColorHex("d4af37"))
        assertEquals(0xFFFFFFFFL, parseColorHex("#FFF"))
        assertEquals(0x80D4AF37L, parseColorHex("#80D4AF37"))
    }

    @Test
    fun `invalid hexadecimal does not invent a colour`() {
        assertNull(parseColorHex("#GG0000"))
        assertNull(parseColorHex("#12345"))
        assertNull(parseColorHex(""))
    }
}
