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
                Retouch(
                    id = "r3",
                    mode = RetouchMode.REMOVE,
                    strokes = listOf(BrushStroke(points = listOf(0.6f, 0.6f), radius = 0.03f)),
                    fill = RemoveFill.of(cell = 4, left = 100, top = 80, columns = 2, rows = 1, offsets = intArrayOf(-40, 3, -40, 3)),
                ),
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

    @Test
    fun aFillKeepsItsOffsetsInRuns() {
        val offsets = intArrayOf(5, -2, 5, -2, 5, -2, -7, 0, -7, 0, 5, -2)
        val fill = RemoveFill.of(cell = 8, left = 0, top = 0, columns = 3, rows = 2, offsets = offsets)
        assertEquals(listOf(3, 5, -2, 2, -7, 0, 1, 5, -2), fill.runs)
        assertTrue(offsets.contentEquals(fill.offsets()))
    }

    @Test
    fun aFillWhoseRunsDoNotCoverTheGridIsRefused() {
        assertEquals(null, RemoveFill(cell = 4, left = 0, top = 0, columns = 2, rows = 2, runs = listOf(3, 1, 1)).offsets())
        assertEquals(null, RemoveFill(cell = 4, left = 0, top = 0, columns = 2, rows = 1, runs = listOf(3, 1, 1)).offsets())
        assertEquals(null, RemoveFill(cell = 0, left = 0, top = 0, columns = 1, rows = 1, runs = listOf(1, 1, 1)).offsets())
    }
}
