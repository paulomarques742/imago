package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.KelvinTint
import eu.studio742.imago.core.model.WhiteBalance
import eu.studio742.imago.core.render.CameraLight
import eu.studio742.imago.core.render.WhiteBalanceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceKelvinTest {
    /** A development balanced for a light at [kelvin], as LibRaw's as-shot multipliers do. */
    private fun cameraAt(kelvin: Float): CameraLight {
        val xyzToRgb = doubleArrayOf(3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560, 0.0556434, -0.2040259, 1.0572252)
        val light = WhiteBalanceModel.xyzOf(kelvin, 0f)
        val rgb = DoubleArray(3) { row -> (0 until 3).sumOf { xyzToRgb[row * 3 + it] * light[it] } }
        return CameraLight.of(FloatArray(9) { (xyzToRgb[it] / rgb[it / 3]).toFloat() })!!
    }

    private val recipe = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "")

    @Test fun `a RAW reads its white balance in kelvin, a JPEG and a mask keep the relative scale`() {
        val raw = EditorUiState(recipe = recipe, cameraLight = cameraAt(4800f))
        assertEquals("4800 K", raw.formatValue(Adjustment.TEMPERATURE, 0f))
        assertEquals("+0", raw.formatValue(Adjustment.TINT, 0f))
        assertEquals("+20", EditorUiState(recipe = recipe).formatValue(Adjustment.TEMPERATURE, 20f))
        val masked = raw.copy(selectedMaskId = "m", sheet = EditorSheet.MASK_ADJUSTMENTS)
        assertEquals("+20", masked.formatValue(Adjustment.TEMPERATURE, 20f))
    }

    @Test fun `moving a slider on a saved recipe lets go of the light it carried`() {
        val carrying = recipe.copy(whiteBalance = WhiteBalance(temp = -30f, tint = 8f, light = KelvinTint(3000f, 10f)))
        assertNull(carrying.withAdjustment(Adjustment.TEMPERATURE, -10f).whiteBalance.light)
        assertNull(carrying.withAdjustment(Adjustment.TINT, 2f).whiteBalance.light)
        assertEquals(KelvinTint(3000f, 10f), carrying.withAdjustment(Adjustment.EXPOSURE, 0.5f).whiteBalance.light)
    }

    @Test fun `the lights are offered only where the camera's colour says what they are`() {
        assertEquals(listOf(WhiteBalancePreset.AS_SHOT, WhiteBalancePreset.AUTO, WhiteBalancePreset.CUSTOM), WhiteBalancePreset.offered(null))
        assertTrue(WhiteBalancePreset.TUNGSTEN in WhiteBalancePreset.offered(cameraAt(5000f)))
    }

    @Test fun `a light preset is named while the sliders stay where it put them`() {
        val light = cameraAt(5200f)
        val (temperature, tint) = light.sliders(2850f, 0f)
        assertEquals(WhiteBalancePreset.TUNGSTEN, WhiteBalancePreset.of(WhiteBalance(temperature, tint), light))
        assertEquals(WhiteBalancePreset.CUSTOM, WhiteBalancePreset.of(WhiteBalance(temperature + 3f, tint), light))
        assertEquals(WhiteBalancePreset.CUSTOM, WhiteBalancePreset.of(WhiteBalance(temperature, tint)))
        assertEquals("2850 K", EditorUiState(recipe = recipe.copy(whiteBalance = WhiteBalance(temperature, tint)), cameraLight = light)
            .formatValue(Adjustment.TEMPERATURE, temperature))
    }
}
