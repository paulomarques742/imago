package eu.studio742.imago.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What these tests guard is the backward-compatibility promise: a recipe's JSON lives whole in a
 * Room column, and a failed migration gives no error — it gives a different photo.
 */
class LocalMaskTest {
    /** The same configuration as `RoomRecipeRepository`. Testing with another one would test nothing. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun recipe(masks: List<LocalMask> = emptyList()) = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-08-25T12:00:00Z",
        updatedAt = "2026-08-25T12:00:00Z",
        masks = masks,
    )

    @Test
    fun aRecipeWithMasksRoundTrips() {
        val original = recipe(
            listOf(
                LocalMask(
                    id = "m1",
                    name = "Sky",
                    components = listOf(
                        MaskComponent(shape = MaskShape.LINEAR, linear = LinearMask(y = 0.3f, angle = 12f)),
                    ),
                    adjustments = LocalAdjustments(exposure = -1.5f, temp = -20f),
                ),
                LocalMask(
                    id = "m2",
                    inverted = true,
                    components = listOf(
                        MaskComponent(shape = MaskShape.RADIAL, radial = RadialMask(radiusX = 0.4f)),
                        MaskComponent(shape = MaskShape.RADIAL, op = MaskOp.SUBTRACT, radial = RadialMask(radiusX = 0.1f)),
                    ),
                    adjustments = LocalAdjustments(clarity = 30f),
                ),
            ),
        )

        assertEquals(original, json.decodeFromString<EditRecipe>(json.encodeToString(original)))
    }

    @Test
    fun aRecipeWithoutMasksIsStillNeutral() {
        val decoded = json.decodeFromString<EditRecipe>(json.encodeToString(recipe()))

        assertEquals(emptyList<LocalMask>(), decoded.masks)
        assertTrue(LocalAdjustments().isNeutral)
    }

    /**
     * The case that matters: a recipe saved before masks existed. The missing field has to fall to
     * the default value, and not blow up reading everything already in the database.
     */
    @Test
    fun aRecipeStoredBeforeMasksExistedStillDecodes() {
        val legacy = """
            {
              "schemaVersion": 1,
              "assetId": "asset-1",
              "originalChecksum": "checksum",
              "processVersion": 7,
              "createdAt": "2026-01-01T00:00:00Z",
              "updatedAt": "2026-01-01T00:00:00Z",
              "tone": { "exposure": 0.5 }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<EditRecipe>(legacy)

        assertEquals(emptyList<LocalMask>(), decoded.masks)
        assertEquals(7, decoded.processVersion)
        assertEquals(0.5f, decoded.tone.exposure)
    }

    /** A component only carries its shape's payload; the others stay null and take no meaning. */
    @Test
    fun aComponentCarriesOnlyItsOwnShape() {
        val component = MaskComponent(shape = MaskShape.RADIAL, radial = RadialMask())
        val decoded = json.decodeFromString<MaskComponent>(json.encodeToString(component))

        assertEquals(null, decoded.linear)
        assertEquals(RadialMask(), decoded.radial)
        assertEquals(MaskOp.ADD, decoded.op)
    }

    @Test
    fun theMaskLimitIsTheChannelCountOfAnRgbaTexture() {
        assertEquals(4, MAX_LOCAL_MASKS)
    }
}
