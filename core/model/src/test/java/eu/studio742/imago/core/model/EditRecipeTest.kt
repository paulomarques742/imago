package eu.studio742.imago.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EditRecipeTest {
    @Test
    fun neutralRecipeRoundTripsWithoutChangingDefaults() {
        val recipe = EditRecipe(
            assetId = "asset-1",
            originalChecksum = "checksum",
            createdAt = "2026-08-06T12:00:00Z",
            updatedAt = "2026-08-06T12:00:00Z",
        )

        val decoded = Json.decodeFromString<EditRecipe>(Json.encodeToString(recipe))

        assertEquals(recipe, decoded)
        assertEquals(0f, decoded.tone.exposure)
        assertEquals(0f, decoded.whiteBalance.temp)
        assertEquals(0f, decoded.presence.saturation)
        assertEquals(CURRENT_PROCESS_VERSION, decoded.processVersion)
    }

    @Test
    fun theFourColourWheelsSurviveTheRoundTrip() {
        val recipe = EditRecipe(
            assetId = "asset-1",
            originalChecksum = "checksum",
            createdAt = "2026-08-06T12:00:00Z",
            updatedAt = "2026-08-06T12:00:00Z",
            colorGrading = ColorGrading(
                shadows = ColorWheel(hue = 212f, saturation = 45f, luminance = -12f),
                midtones = ColorWheel(hue = 90f, saturation = 10f),
                highlights = ColorWheel(hue = 38f, saturation = 60f, luminance = 8f),
                global = ColorWheel(hue = 300f, saturation = 15f),
                blending = 72f,
                balance = -24f,
            ),
        )

        assertEquals(recipe, Json.decodeFromString<EditRecipe>(Json.encodeToString(recipe)))
    }

    /**
     * A recipe saved before the fourth wheel existed. The missing field falls to the neutral
     * default, which is the only thing that keeps it opening the same.
     */
    @Test
    fun aRecipeWithoutTheGlobalWheelDecodesNeutral() {
        val legacy = """
            {
              "assetId": "asset-1",
              "originalChecksum": "checksum",
              "processVersion": 8,
              "createdAt": "2026-08-06T12:00:00Z",
              "updatedAt": "2026-08-06T12:00:00Z",
              "colorGrading": {
                "shadows": { "hue": 210.0, "saturation": 30.0, "luminance": 0.0 },
                "blending": 50.0,
                "balance": 0.0
              }
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<EditRecipe>(legacy)

        assertEquals(ColorWheel(), decoded.colorGrading.global)
        assertEquals(210f, decoded.colorGrading.shadows.hue)
        assertEquals(8, decoded.processVersion)
    }
}
