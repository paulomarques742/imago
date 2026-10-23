package eu.studio742.imago.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetouchTest {
    /** The same configuration as `RoomRecipeRepository`. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun recipe(retouches: List<Retouch> = emptyList()) = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-10-23T12:00:00Z",
        updatedAt = "2026-10-23T12:00:00Z",
        retouches = retouches,
    )

    @Test
    fun aRecipeWithCorrectionsRoundTrips() {
        val original = recipe(
            listOf(
                Retouch(
                    id = "r1",
                    mode = RetouchMode.HEAL,
                    strokes = listOf(BrushStroke(points = listOf(0.4f, 0.5f, 0.42f, 0.51f), radius = 0.02f, feather = 30f)),
                    sourceX = -0.1f,
                    sourceY = 0.05f,
                    opacity = 80f,
                ),
                Retouch(id = "r2", mode = RetouchMode.CLONE, strokes = listOf(BrushStroke(points = listOf(0.1f, 0.1f), radius = 0.01f))),
            ),
        )
        assertEquals(original, json.decodeFromString(EditRecipe.serializer(), json.encodeToString(EditRecipe.serializer(), original)))
    }

    @Test
    fun aRecipeFromBeforeCorrectionsHasNone() {
        val stored = json.encodeToString(EditRecipe.serializer(), recipe()).replace(",\"retouches\":[]", "")
        assertTrue("the field should be gone from the old JSON", "retouches" !in stored)
        assertEquals(emptyList<Retouch>(), json.decodeFromString(EditRecipe.serializer(), stored).retouches)
    }
}
