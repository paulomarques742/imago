package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Temperature and Tint as a light on the Planckian locus, adapted to D65. */
class WhiteBalanceModelTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = floatArrayOf(
        m[0] * r + m[1] * g + m[2] * b,
        m[3] * r + m[4] * g + m[5] * b,
        m[6] * r + m[7] * g + m[8] * b,
    )

    private fun inverse(m: FloatArray): FloatArray {
        val a = m[4] * m[8] - m[5] * m[7]
        val b = m[5] * m[6] - m[3] * m[8]
        val c = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * a + m[1] * b + m[2] * c
        return floatArrayOf(
            a, m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            b, m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            c, m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        ).map { it / det }.toFloatArray()
    }

    @Test fun atZeroTheLightIsD65AndNothingChanges() {
        val m = WhiteBalanceModel.matrix(0f, 0f)
        for (i in 0 until 9) assertEquals("entry $i", if (i % 4 == 0) 1f else 0f, m[i], 1e-5f)
    }

    @Test fun warmerToTheRightAndMagentaUpwards() {
        val warm = apply(WhiteBalanceModel.matrix(0.5f, 0f), 0.5f, 0.5f, 0.5f)
        assertTrue("+Temperature warms grey: ${warm.toList()}", warm[0] > warm[2])
        val cool = apply(WhiteBalanceModel.matrix(-0.5f, 0f), 0.5f, 0.5f, 0.5f)
        assertTrue("−Temperature cools grey: ${cool.toList()}", cool[2] > cool[0])
        val magenta = apply(WhiteBalanceModel.matrix(0f, 0.5f), 0.5f, 0.5f, 0.5f)
        assertTrue("+Tint is magenta: ${magenta.toList()}", magenta[1] < magenta[0] && magenta[1] < magenta[2])
        val green = apply(WhiteBalanceModel.matrix(0f, -0.5f), 0.5f, 0.5f, 0.5f)
        assertTrue("−Tint is green: ${green.toList()}", green[1] > green[0] && green[1] > green[2])
    }

    @Test fun changingTheLightKeepsTheLuminanceOfGrey() {
        for ((t, s) in listOf(-1f to 0f, 1f to 0f, 0f to 1f, -0.6f to -0.4f)) {
            val grey = apply(WhiteBalanceModel.matrix(t, s), 0.5f, 0.5f, 0.5f)
            assertEquals("$t, $s", 0.5f, 0.2126729f * grey[0] + 0.7151522f * grey[1] + 0.0721750f * grey[2], 1e-4f)
        }
    }

    @Test fun theLightOfTheSlidersIsWhatTheInverseFinds() {
        for ((t, s) in listOf(-0.8f to 0.2f, -0.3f to -0.5f, 0f to 0f, 0.4f to 0.3f, 0.9f to -0.9f)) {
            val light = apply(inverse(WhiteBalanceModel.matrix(t, s)), 0.5f, 0.5f, 0.5f)
            val (foundT, foundS) = WhiteBalanceModel.slidersFor(light[0], light[1], light[2])
            assertEquals("temperature of $t, $s", t, foundT, 0.002f)
            assertEquals("tint of $t, $s", s, foundS, 0.002f)
        }
    }

    /** What the earlier gains could not do: a JPEG under tungsten light, its grey card picked. */
    @Test fun aTungstenCastIsWithinReach() {
        val tungsten = 0xFFB49A80.toInt()
        val pick = neutralWhiteBalanceOf(IntArray(25) { tungsten })!!
        assertTrue("within the slider: $pick", pick.temperature > -100f)
        val parameters = RenderParameters(temperature = pick.temperature, tint = pick.tint)
        val out = BitmapPhotoProcessor.processPixel(tungsten, parameters, parameters.effectiveTone())
        val r = out ushr 16 and 0xFF
        val g = out ushr 8 and 0xFF
        val b = out and 0xFF
        assertTrue("came out ($r, $g, $b)", abs(r - g) <= 1 && abs(g - b) <= 1)
    }

    /** An older recipe, converted, gives grey the colour the three gains gave it. */
    @Test fun theConversionOfAnOlderWhiteBalanceKeepsTheColourOfGrey() {
        for (legacy in listOf(WhiteBalance(26f, 6f), WhiteBalance(-40f, 0f), WhiteBalance(10f, -30f), WhiteBalance(100f, 100f))) {
            val old = RenderParameters(temperature = legacy.temp, tint = legacy.tint, lightWhiteBalance = false)
            val converted = lightWhiteBalanceOf(legacy)
            val new = RenderParameters(temperature = converted.temp, tint = converted.tint)
            val before = BitmapPhotoProcessor.processPixel(0xFF808080.toInt(), old, old.effectiveTone())
            val after = BitmapPhotoProcessor.processPixel(0xFF808080.toInt(), new, new.effectiveTone())
            fun ratios(argb: Int): Pair<Float, Float> {
                val r = (argb ushr 16 and 0xFF).toFloat()
                val g = (argb ushr 8 and 0xFF).toFloat()
                val b = (argb and 0xFF).toFloat()
                return r / g to b / g
            }
            val (oldRed, oldBlue) = ratios(before)
            val (newRed, newBlue) = ratios(after)
            assertEquals("red over green for $legacy → $converted", oldRed, newRed, 0.02f)
            assertEquals("blue over green for $legacy", oldBlue, newBlue, 0.02f)
            assertTrue("$legacy became $converted", abs(converted.temp) <= 100f && abs(converted.tint) <= 100f)
        }
    }

    /** The shader builds the matrix per pixel under masks with the same ends as Kotlin. */
    @Test fun theShaderUsesTheModelsConstants() {
        val tone = PhotoShaders.TONE
        assertEquals(1.0e6 / 6504.0, WhiteBalanceModel.NEUTRAL_MIRED, 1e-6)
        assertTrue(tone.contains("153.75153752 + (500.0 - 153.75153752) * -t"))
        assertTrue(tone.contains("153.75153752 - (153.75153752 - 40.0) * t"))
        assertTrue(tone.contains("normal * s * 0.05"))
        assertEquals(500.0, WhiteBalanceModel.COLD_END_MIRED, 0.0)
        assertEquals(40.0, WhiteBalanceModel.WARM_END_MIRED, 0.0)
        assertEquals(0.05, WhiteBalanceModel.TINT_REACH_UV, 0.0)
        assertTrue(PhotoShaders.LOCAL_BASE.contains("whiteBalanceAt(temperature, tint) * linear"))
    }
}
