package eu.studio742.imago.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInRecipesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun everyPresetSurvivesTheSchemaRoundTrip() {
        BUILT_IN_RECIPES.forEach { preset ->
            val encoded = json.encodeToString(EditRecipe.serializer(), preset.recipe)
            val decoded = json.decodeFromString(EditRecipe.serializer(), encoded)
            assertEquals("Preset ${preset.id} did not survive the round trip", preset.recipe, decoded)
        }
    }

    @Test
    fun presetsDeclareSchemaV1AndTheCurrentPipeline() {
        BUILT_IN_RECIPES.forEach { preset ->
            assertEquals(1, preset.recipe.schemaVersion)
            assertEquals(CURRENT_PROCESS_VERSION, preset.recipe.processVersion)
        }
    }

    /** A preset that touches nothing is indistinguishable from the original and would have no reason to exist. */
    @Test
    fun everyPresetChangesSomething() {
        val neutral = EditRecipe(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "")
        BUILT_IN_RECIPES.forEach { preset ->
            assertNotEquals("Preset ${preset.id} is neutral", neutral, preset.recipe)
        }
    }

    @Test
    fun identifiersAndNamesAreUnique() {
        assertEquals(BUILT_IN_RECIPES.size, BUILT_IN_RECIPES.distinctBy { it.id }.size)
        assertEquals(BUILT_IN_RECIPES.size, BUILT_IN_RECIPES.distinctBy { it.name }.size)
    }

    /**
     * No value may fall outside the range the matching slider accepts — a preset with exposure at
     * +7 EV would be impossible to reproduce by hand and to correct later.
     */
    @Test
    fun presetValuesStayInsideTheirRanges() {
        BUILT_IN_RECIPES.forEach { preset ->
            val recipe = preset.recipe
            assertTrue("${preset.id}: exposure", recipe.tone.exposure in -5f..5f)
            listOf(
                "contrast" to recipe.tone.contrast,
                "highlights" to recipe.tone.highlights,
                "shadows" to recipe.tone.shadows,
                "whites" to recipe.tone.whites,
                "blacks" to recipe.tone.blacks,
                "temperature" to recipe.whiteBalance.temp,
                "tint" to recipe.whiteBalance.tint,
                "texture" to recipe.presence.texture,
                "clarity" to recipe.presence.clarity,
                "dehaze" to recipe.presence.dehaze,
                "vibrance" to recipe.presence.vibrance,
                "saturation" to recipe.presence.saturation,
                "vignette" to recipe.effects.vignetteAmount,
            ).forEach { (name, value) ->
                assertTrue("${preset.id}: $name outside -100..100 ($value)", value in -100f..100f)
            }
            assertTrue("${preset.id}: grain", recipe.effects.grainAmount in 0f..100f)
        }
    }

    /** The curve has to be monotonic in x, or the interpolation cannot sample it. */
    @Test
    fun presetCurvesAreOrderedAndInsideTheByteRange() {
        BUILT_IN_RECIPES.forEach { preset ->
            val points = preset.recipe.toneCurve.rgb
            assertTrue("${preset.id}: curve with fewer than two points", points.size >= 2)
            points.forEach {
                assertTrue("${preset.id}: point outside 0..255", it.x in 0..255 && it.y in 0..255)
            }
            assertEquals(
                "${preset.id}: curve is not sorted in x",
                points.map { it.x }.sorted(),
                points.map { it.x },
            )
        }
    }
}
