package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class ReelYuvTest {
    private fun convert(color: Int): Triple<Int, Int, Int> {
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)
        argbToYuv709(IntArray(4) { color }, 2, 2, y, u, v)
        return Triple(y[0].toInt() and 0xFF, u[0].toInt() and 0xFF, v[0].toInt() and 0xFF)
    }

    @Test
    fun `white and black land on the ends of the limited range`() {
        assertEquals(Triple(235, 128, 128), convert(0xFFFFFFFF.toInt()))
        assertEquals(Triple(16, 128, 128), convert(0xFF000000.toInt()))
    }

    @Test
    fun `pure red has the BT709 values`() {
        assertEquals(Triple(63, 102, 240), convert(0xFFFF0000.toInt()))
    }

    @Test
    fun `chroma is the average of each two by two block`() {
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)
        val red = 0xFFFF0000.toInt()
        val black = 0xFF000000.toInt()
        argbToYuv709(intArrayOf(red, black, black, red), 2, 2, y, u, v)
        // Half red: (127, 0, 0) on average.
        assertEquals(((112 * 127 + 128) shr 8) + 128, v[0].toInt() and 0xFF)
    }
}
