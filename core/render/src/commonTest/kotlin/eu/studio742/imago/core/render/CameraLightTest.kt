package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLightTest {
    /** XYZ to linear sRGB, D65. */
    private val xyzToRgb = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252,
    )

    /** A development balanced, channel by channel, for a light at [kelvin]: what LibRaw does with the as-shot multipliers. */
    private fun balancedFor(kelvin: Float, tint: Float = 0f): FloatArray {
        val light = WhiteBalanceModel.xyzOf(kelvin, tint)
        val rgb = DoubleArray(3) { row -> (0 until 3).sumOf { xyzToRgb[row * 3 + it] * light[it] } }
        return FloatArray(9) { (xyzToRgb[it] / rgb[it / 3]).toFloat() }
    }

    @Test fun `As Shot is the light the camera balanced for`() {
        for (kelvin in listOf(2850f, 4000f, 5500f, 7500f)) {
            val light = CameraLight.of(balancedFor(kelvin, 8f))!!
            assertEquals("$kelvin", kelvin, light.asShot.kelvin, kelvin * 0.01f)
            assertEquals("$kelvin", 8f, light.asShot.tint, 1f)
        }
    }

    @Test fun `a light in kelvin and the sliders are two names for the same thing`() {
        val light = CameraLight.of(balancedFor(5000f))!!
        assertEquals(0f, light.sliders(light.asShot.kelvin, light.asShot.tint).first, 0.5f)
        for (kelvin in listOf(2850f, 3800f, 5500f, 6500f, 9000f)) for (tint in listOf(-20f, 0f, 15f)) {
            val (temperature, sliderTint) = light.sliders(kelvin, tint)
            val back = light.kelvinTint(temperature, sliderTint)
            assertEquals("$kelvin $tint", kelvin, back.kelvin, kelvin * 0.01f)
            assertEquals("$kelvin $tint", tint, back.tint, 1f)
        }
        // Warmer than As Shot is cooled by the slider, as Lightroom's Temperature does: a lower kelvin, a lower slider.
        assertTrue(light.sliders(3000f, 0f).first < light.sliders(8000f, 0f).first)
    }

    @Test fun `a camera without a colour matrix keeps the relative sliders`() {
        assertNull(CameraLight.of(null))
        assertNull(CameraLight.of(FloatArray(9)))
        assertNull(CameraLight.of(FloatArray(8) { 1f }))
    }

    @Test fun `kelvin and tint round trip through XYZ`() {
        for (kelvin in listOf(2000f, 3200f, 6504f, 12000f, 25000f)) for (tint in listOf(-50f, 0f, 50f)) {
            val xyz = WhiteBalanceModel.xyzOf(kelvin, tint)
            val (k, t) = WhiteBalanceModel.kelvinAndTint(xyz[0], xyz[1], xyz[2])
            assertEquals("$kelvin $tint", kelvin, k, kelvin * 0.005f)
            assertEquals("$kelvin $tint", tint, t, 0.5f)
        }
    }
}
