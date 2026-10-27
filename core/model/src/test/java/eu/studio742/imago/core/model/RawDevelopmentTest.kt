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

    @Test fun `a recipe from process 19 keeps the rendering it was edited on`() {
        val written = Json.decodeFromString<EditRecipe>(
            """{"assetId":"raw","originalChecksum":"","createdAt":"","updatedAt":"","processVersion":19,
               "rawDevelopment":{"engine":"libraw-0.22.2-imago-1"}}""")
        assertEquals(RAW_RENDERING_LINEAR, written.rawRendering())
        assertFalse(written.appliesBaselineExposure())
        assertEquals(RAW_PROFILE_COLOR, written.profile)
        val opened = written.developedAsRaw()
        assertEquals(CURRENT_PROCESS_VERSION, opened.processVersion)
        assertEquals(RAW_RENDERING_LINEAR, opened.rawRendering())
    }

    @Test fun `a recipe decides by its process until it is opened as a RAW`() {
        val older = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "", processVersion = 19)
        assertEquals(RAW_RENDERING_LINEAR, older.rawRendering())
        assertEquals(RAW_RENDERING_LINEAR, older.developedAsRaw().rawDevelopment!!.rendering)
        val fresh = older.copy(processVersion = CURRENT_PROCESS_VERSION)
        assertEquals(RAW_RENDERING_PROFILED, fresh.rawRendering())
        assertTrue(fresh.appliesBaselineExposure())
        assertEquals(RawDevelopment(rendering = RAW_RENDERING_PROFILED), fresh.developedAsRaw().rawDevelopment)
        // The decision made before decoding wins over the process the editor stamps afterwards.
        assertEquals(RAW_RENDERING_LINEAR, fresh.developedAsRaw(RAW_RENDERING_LINEAR).rawRendering())
    }

    @Test fun `the rendering and the profile survive synchronization JSON`() {
        val recipe = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "",
            rawDevelopment = RawDevelopment(rendering = RAW_RENDERING_PROFILED), profile = RAW_PROFILE_MONOCHROME)
        val restored = Json.decodeFromString<EditRecipe>(Json.encodeToString(recipe))
        assertEquals(recipe, restored)
        assertTrue(restored.appliesBaselineExposure())
    }

    @Test fun `a rendering or a profile this app does not know asks for a newer one`() {
        val recipe = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "")
        assertFalse(recipe.needsNewerRawRendering())
        assertTrue(recipe.copy(profile = "vivid").needsNewerRawRendering())
        assertTrue(recipe.copy(rawDevelopment = RawDevelopment(rendering = CURRENT_RAW_RENDERING + 1)).needsNewerRawRendering())
    }

    @Test fun `the profile travels as a part of the look, but not with the app's filters or an older saved recipe`() {
        val base = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "",
            rawDevelopment = RawDevelopment(rendering = RAW_RENDERING_PROFILED))
        val monochrome = base.copy(profile = RAW_PROFILE_MONOCHROME, rawDevelopment = null)
        val copied = base.withPartsFrom(monochrome, RecipePart.LOOK)
        assertEquals(RAW_PROFILE_MONOCHROME, copied.profile)
        assertEquals("the development stays the photo's", base.rawDevelopment, copied.rawDevelopment)
        assertEquals(RAW_PROFILE_COLOR, base.withPartsFrom(monochrome, RecipePart.BUILT_IN).profile)
        assertEquals(RAW_PROFILE_COLOR, base.withPartsFrom(monochrome, RecipePart.LEGACY).profile)
        assertEquals(RAW_PROFILE_MONOCHROME, base.copy(profile = RAW_PROFILE_MONOCHROME).withPartsFrom(base, RecipePart.BUILT_IN).profile)
    }

    @Test fun `the camera's exposure is recorded once, and only for the rendering that uses it`() {
        val fresh = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "")
        val developed = fresh.developedAsRaw(exposure = -0.9f)
        assertEquals(-0.9f, developed.rawExposure(), 0f)
        assertEquals("a recorded development is kept", -0.9f, developed.developedAsRaw(exposure = 0.4f).rawExposure(), 0f)
        assertEquals(0f, fresh.developedAsRaw(RAW_RENDERING_LINEAR, exposure = -0.9f).rawExposure(), 0f)
        val restored = Json.decodeFromString<EditRecipe>(Json.encodeToString(developed))
        assertEquals(-0.9f, restored.rawExposure(), 0f)
    }
}
