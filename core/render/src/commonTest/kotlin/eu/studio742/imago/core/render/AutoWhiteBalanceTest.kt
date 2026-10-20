package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * Auto white balance on scenes made of patches — greys and colours — lit by a known light: Auto has to
 * find what the White Balance Selector finds on one of the greys.
 */
class AutoWhiteBalanceTest {
    /** Tungsten, about 2850 K, as linear sRGB relative to D65's white. */
    private val tungsten = Triple(1f, 0.70f, 0.38f)
    private val daylight = Triple(1f, 1f, 1f)

    @Test fun findsATungstenCastFromTheGreysAmongColours() {
        val scene = scene(tungsten, greys = 0.3f, seed = 1)
        val expected = selectorOnGrey(tungsten)
        val found = checkNotNull(autoWhiteBalanceOf(scene))
        assertTrue("warm light cools down: $found, the selector says $expected", found.temperature < -20f)
        assertClose(expected, found, tolerance = 4f)
    }

    @Test fun leavesDaylightAlone() {
        val found = checkNotNull(autoWhiteBalanceOf(scene(daylight, greys = 0.3f, seed = 2)))
        assertTrue("$found", abs(found.temperature) < 4f && abs(found.tint) < 4f)
    }

    @Test fun aSceneMostlyOfOneColourIsNotTakenForTheLight() {
        // A lawn with a path through it: most of the photo is green, and the light is daylight.
        val found = checkNotNull(autoWhiteBalanceOf(scene(daylight, greys = 0.12f, seed = 3, dominant = Triple(0.10f, 0.32f, 0.06f))))
        assertTrue("$found", abs(found.temperature) < 8f && abs(found.tint) < 8f)
    }

    @Test fun seaAndSkyAreNotTakenForAColdLight() {
        // Mostly blue water under daylight, with a few greys: the blue is the scene, not the light.
        val found = checkNotNull(autoWhiteBalanceOf(scene(daylight, greys = 0.12f, seed = 4, dominant = Triple(0.05f, 0.16f, 0.42f))))
        assertTrue("$found", abs(found.temperature) < 8f && abs(found.tint) < 8f)
    }

    @Test fun nothingReadableFindsNothing() {
        assertNull(autoWhiteBalanceOf(PixelBuffer(4, 4, IntArray(16) { 0xFF000000.toInt() })))
        assertNull(autoWhiteBalanceOf(PixelBuffer(4, 4, IntArray(16) { 0xFFFFFFFF.toInt() })))
    }

    @Test fun anAutoRecipeTakesWhatThePhotoGivesAndOthersKeepTheirs() {
        val auto = recipe(WhiteBalance(temp = -40f, tint = 5f, auto = true))
        assertEquals(WhiteBalance(temp = 12f, tint = -3f, auto = true), auto.withAutoWhiteBalance(NeutralWhiteBalance(12f, -3f)).whiteBalance)
        assertEquals(WhiteBalance(auto = true), auto.withAutoWhiteBalance(null).whiteBalance)
        val custom = recipe(WhiteBalance(temp = -40f, tint = 5f))
        assertEquals(custom, custom.withAutoWhiteBalance(NeutralWhiteBalance(12f, -3f)))
    }

    private fun recipe(balance: WhiteBalance) =
        EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "", whiteBalance = balance)

    private fun selectorOnGrey(light: Triple<Float, Float, Float>): NeutralWhiteBalance =
        neutralWhiteBalanceOf(IntArray(25) { lit(0.3f, 0.3f, 0.3f, light) })!!

    private fun assertClose(expected: NeutralWhiteBalance, found: NeutralWhiteBalance, tolerance: Float) {
        assertTrue(
            "expected $expected, found $found",
            abs(expected.temperature - found.temperature) <= tolerance && abs(expected.tint - found.tint) <= tolerance,
        )
    }

    /**
     * 20 × 20 patches of 10 pixels: [greys] of them neutral, the rest random colours — or, with
     * [dominant], that colour with a little variation — all lit by [light].
     */
    private fun scene(
        light: Triple<Float, Float, Float>,
        greys: Float,
        seed: Int,
        dominant: Triple<Float, Float, Float>? = null,
    ): PixelBuffer {
        val random = Random(seed)
        val side = 200
        val patch = 10
        val buffer = PixelBuffer(side, side)
        for (py in 0 until side / patch) {
            for (px in 0 until side / patch) {
                val colour = if (random.nextFloat() < greys) {
                    val level = 0.05f + random.nextFloat() * 0.55f
                    lit(level, level, level, light)
                } else if (dominant != null) {
                    val vary = 0.7f + random.nextFloat() * 0.6f
                    lit(dominant.first * vary, dominant.second * vary, dominant.third * vary, light)
                } else {
                    lit(0.02f + random.nextFloat() * 0.5f, 0.02f + random.nextFloat() * 0.5f, 0.02f + random.nextFloat() * 0.5f, light)
                }
                for (y in 0 until patch) for (x in 0 until patch) {
                    buffer.pixels[(py * patch + y) * side + px * patch + x] = colour
                }
            }
        }
        return buffer
    }

    /** A surface of this linear reflectance under [light], as an 8-bit sRGB pixel. */
    private fun lit(red: Float, green: Float, blue: Float, light: Triple<Float, Float, Float>): Int {
        fun encode(linear: Float): Int {
            val value = linear.coerceIn(0f, 1f)
            val srgb = if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f
            return (srgb * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (encode(red * light.first) shl 16) or (encode(green * light.second) shl 8) or encode(blue * light.third)
    }
}
