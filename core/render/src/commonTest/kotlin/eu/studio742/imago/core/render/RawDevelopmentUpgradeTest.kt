package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.CURRENT_RAW_ENGINE
import eu.studio742.imago.core.model.CURRENT_RAW_RENDERING
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RAW_ENGINE_1
import eu.studio742.imago.core.model.RAW_PROFILE_COLOR
import eu.studio742.imago.core.model.RAW_PROFILE_VIVID
import eu.studio742.imago.core.model.RAW_RENDERING_LINEAR
import eu.studio742.imago.core.model.RAW_RENDERING_PROFILED
import eu.studio742.imago.core.model.RawDevelopment
import eu.studio742.imago.core.model.Tone
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDevelopmentUpgradeTest {
    private fun recipe(development: RawDevelopment?, exposure: Float = 0f, profile: String = RAW_PROFILE_COLOR) = EditRecipe(
        assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "",
        processVersion = CURRENT_PROCESS_VERSION,
        tone = Tone(exposure = exposure),
        profile = profile,
        rawDevelopment = development,
    )

    private val linear = RawDevelopment(RAW_ENGINE_1, RAW_RENDERING_LINEAR, 0f)
    private val profiled = RawDevelopment(RAW_ENGINE_1, RAW_RENDERING_PROFILED, 0.3f)
    private val current = RawDevelopment(CURRENT_RAW_ENGINE, CURRENT_RAW_RENDERING, 0.3f)

    @Test fun `only an older development is offered an upgrade`() {
        assertTrue(RawDevelopmentUpgrade.isAvailable(recipe(linear)))
        assertTrue(RawDevelopmentUpgrade.isAvailable(recipe(profiled)))
        assertFalse(RawDevelopmentUpgrade.isAvailable(recipe(current)))
        assertFalse("a recipe not opened as a RAW has none", RawDevelopmentUpgrade.isAvailable(recipe(null)))
        assertFalse(
            "a rendering from a newer app is not touched",
            RawDevelopmentUpgrade.isAvailable(recipe(RawDevelopment(CURRENT_RAW_ENGINE, CURRENT_RAW_RENDERING + 1, 0f))),
        )
        assertNull(RawDevelopmentUpgrade.upgrade(recipe(current), 0f, 0f))
    }

    @Test fun `rendering 1 keeps its mid grey where it was`() {
        for (old in listOf(-1.5f, -0.4f, 0f, 0.7f, 1.2f)) {
            for (baseline in listOf(0f, 0.5f)) {
                for (measured in listOf(-0.3f, 0f, 0.4f)) {
                    val upgraded = RawDevelopmentUpgrade.upgrade(recipe(linear, exposure = old), measured, baseline)!!
                    val before = encodeSrgb(0.18f * 2f.pow(old))
                    val after = RawProfile.curve(0.18f * 2f.pow(baseline + measured + upgraded.tone.exposure))
                    assertEquals("exposure $old, baseline $baseline, measured $measured", before, after, 0.005f)
                }
            }
        }
    }

    @Test fun `rendering 1 becomes the current development with the Color profile`() {
        val upgraded = RawDevelopmentUpgrade.upgrade(recipe(linear, profile = RAW_PROFILE_VIVID), measured = 0.25f, baseline = 0f)!!
        assertEquals(RawDevelopment(CURRENT_RAW_ENGINE, CURRENT_RAW_RENDERING, 0.25f), upgraded.rawDevelopment)
        assertEquals(RAW_PROFILE_COLOR, upgraded.profile)
        assertFalse(RawDevelopmentUpgrade.isAvailable(upgraded))
    }

    @Test fun `rendering 2 only changes the engine and keeps its exposure and profile`() {
        val upgraded = RawDevelopmentUpgrade.upgrade(recipe(profiled, exposure = 0.6f, profile = RAW_PROFILE_VIVID), measured = 9f, baseline = 9f)!!
        assertEquals(RawDevelopment(CURRENT_RAW_ENGINE, CURRENT_RAW_RENDERING, 0.3f), upgraded.rawDevelopment)
        assertEquals(0.6f, upgraded.tone.exposure, 0f)
        assertEquals(RAW_PROFILE_VIVID, upgraded.profile)
        assertNotNull(upgraded)
    }

    @Test fun `the compensation stays on the slider`() {
        assertEquals(5f, RawDevelopmentUpgrade.exposureFor(oldExposure = 5f, baseline = -20f, measured = 0f), 0f)
        assertEquals(-5f, RawDevelopmentUpgrade.exposureFor(oldExposure = -5f, baseline = 20f, measured = 0f), 0f)
    }
}
