package eu.studio742.imago.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RawDevelopmentTest {
    @Test fun `RAW development engine survives recipe synchronization JSON`() {
        val recipe = EditRecipe(assetId = "raw", originalChecksum = "original", createdAt = "", updatedAt = "",
            tone = Tone(exposure = -2f), rawDevelopment = RawDevelopment())
        val restored = Json.decodeFromString<EditRecipe>(Json.encodeToString(recipe))
        assertEquals(recipe, restored)
        assertEquals(CURRENT_RAW_ENGINE, restored.rawDevelopment?.engine)
        assertTrue(restored.processVersion >= RAW_PROCESS_VERSION)
    }

    @Test fun `a legacy photo has no RAW development stage`() {
        val recipe = Json.decodeFromString<EditRecipe>("""{"assetId":"jpeg","originalChecksum":"","createdAt":"","updatedAt":"","processVersion":17}""")
        assertNull(recipe.rawDevelopment)
        assertEquals(17, recipe.processVersion)
    }
}
