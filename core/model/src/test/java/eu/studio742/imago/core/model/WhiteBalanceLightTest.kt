package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhiteBalanceLightTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val light = KelvinTint(3000f, 10f)
    private val balance = WhiteBalance(temp = -30f, tint = 8f, light = light)

    /** The class as an app released before the field reads it. */
    @Serializable
    private data class OlderWhiteBalance(val temp: Float = 0f, val tint: Float = 0f, val auto: Boolean = false)

    private fun recipe(whiteBalance: WhiteBalance = WhiteBalance()) =
        EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "", whiteBalance = whiteBalance)

    @Test fun `the light survives being stored`() {
        assertEquals(balance, json.decodeFromString<WhiteBalance>(json.encodeToString(balance)))
    }

    @Test fun `a recipe stored before the field has no light`() {
        assertNull(json.decodeFromString<WhiteBalance>("""{"temp":-30.0,"tint":8.0,"auto":false}""").light)
    }

    @Test fun `an app without the field applies the difference`() {
        val older = json.decodeFromString<OlderWhiteBalance>(json.encodeToString(balance))
        assertEquals(OlderWhiteBalance(temp = -30f, tint = 8f), older)
    }

    @Test fun `the light goes with the white balance and only with it`() {
        val source = recipe(balance)
        assertEquals(light, recipe().withPartsFrom(source, setOf(RecipePart.WHITE_BALANCE)).whiteBalance.light)
        assertNull(recipe().withPartsFrom(source, setOf(RecipePart.EXPOSURE)).whiteBalance.light)
    }
}
