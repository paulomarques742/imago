package eu.studio742.imago.core.raw

import org.junit.Assert.*
import org.junit.Test

class HalfFloatsTest {
    @Test fun `all finite binary16 values round trip exactly`() {
        for (bits in 0..65535) {
            if (bits ushr 10 and 31 == 31) continue
            assertEquals("Binary16 value $bits", bits.toShort(), encodeHalf(decodeHalf(bits.toShort())))
        }
    }

    @Test fun `rounding uses nearest even and preserves highlight headroom`() {
        assertEquals(1f, decodeHalf(encodeHalf(1f + 1f / 2048f)), 0f)
        assertEquals(1f + 1f / 512f, decodeHalf(encodeHalf(1f + 3f / 2048f)), 0f)
        assertEquals(4f, decodeHalf(encodeHalf(4f)), 0f)
        assertEquals(-.25f, decodeHalf(encodeHalf(-.25f)), 0f)
        assertEquals(65504f, decodeHalf(encodeHalf(Float.POSITIVE_INFINITY)), 0f)
    }
}
