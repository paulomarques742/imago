package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The White Balance Selector: whatever colour the picked area has, the white balance it gives makes
 * that area grey through the very pipeline the export runs.
 */
class WhiteBalancePickTest {
    private fun exported(argb: Int, pick: NeutralWhiteBalance): Int {
        val parameters = RenderParameters(temperature = pick.temperature, tint = pick.tint)
        return BitmapPhotoProcessor.processPixel(argb, parameters, parameters.effectiveTone())
    }

    private fun assertGrey(argb: Int, what: String) {
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        assertTrue("$what came out ($r, $g, $b)", abs(r - g) <= 1 && abs(g - b) <= 1 && abs(r - b) <= 1)
    }

    @Test fun aColourCastComesOutGrey() {
        for (cast in listOf(0xFFA0968C.toInt(), 0xFF8C96A0.toInt(), 0xFF909A90.toInt(), 0xFF9A909A.toInt(), 0xFF55504A.toInt(), 0xFFB49A80.toInt(), 0xFF7890B8.toInt())) {
            val pick = neutralWhiteBalanceOf(IntArray(25) { cast })
            assertNotNull(pick)
            assertGrey(exported(cast, pick!!), "%08X".format(cast))
        }
    }

    @Test fun warmTakesTemperatureDownAndGreenTakesTintUp() {
        val warm = neutralWhiteBalanceOf(intArrayOf(0xFFC0A080.toInt()))!!
        assertTrue(warm.temperature < 0f)
        val green = neutralWhiteBalanceOf(intArrayOf(0xFF80A080.toInt()))!!
        assertTrue("magenta neutralises green", green.tint > 0f)
    }

    @Test fun greyNeedsNothing() {
        val pick = neutralWhiteBalanceOf(IntArray(9) { 0xFF808080.toInt() })!!
        assertEquals(0f, pick.temperature, 0.01f)
        assertEquals(0f, pick.tint, 0.01f)
    }

    @Test fun theAreaIsAveragedInLinearLight() {
        val noisy = IntArray(25) { if (it % 2 == 0) 0xFFB09880.toInt() else 0xFFA89078.toInt() }
        assertNotNull(neutralWhiteBalanceOf(noisy))
    }

    @Test fun aBurntOutOrBlackAreaSaysNothing() {
        assertNull(neutralWhiteBalanceOf(IntArray(25) { 0xFFFFF0D0.toInt() }))
        assertNull(neutralWhiteBalanceOf(IntArray(25) { 0xFF060504.toInt() }))
        assertNull(neutralWhiteBalanceOf(IntArray(0)))
    }

    @Test fun aCastPastTheSlidersStopsAtTheirEnds() {
        val pick = neutralWhiteBalanceOf(intArrayOf(0xFFE0300A.toInt()))!!
        assertTrue("$pick", pick.temperature in -100f..100f && pick.tint in -100f..100f)
        assertTrue("$pick", abs(pick.temperature) == 100f || abs(pick.tint) == 100f)
    }
}
