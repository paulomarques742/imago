package eu.studio742.imago.core.composition

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe

class CompositionRoundTripTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `project round trips all static element families`() {
        val now = Instant.EPOCH.toString()
        val project = CompositionProject(
            id = "project",
            name = "Teste",
            format = PageFormatPreset.STORY_9_16.format,
            pages = listOf(CompositionPage("one", 0), CompositionPage("two", 1)),
            elements = listOf(
                CompositionElement.Text("text", ElementTransform(NormalizedRect(.1f, .1f, .8f, .2f)), 0, "Hello"),
                CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.8f, .2f, .4f, .3f)), 1, ShapeKind.ARROW),
                CompositionElement.Drawing(
                    "stroke", ElementTransform(NormalizedRect(0f, 0f, 2f, 1f)), 2, StrokeKind.PEN,
                    listOf(StrokePoint(0f, 0f), StrokePoint(1f, 1f)), 0xFFFFFFFF, 4f,
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )
        assertEquals(project, json.decodeFromString<CompositionProject>(json.encodeToString(project)))
    }

    @Test
    fun `presets keep the name that the saved projects already use`() {
        assertEquals("\"STORY_9_16\"", json.encodeToString(PageFormatPreset.STORY_9_16.format))
        assertEquals(
            PageFormatPreset.SQUARE_1_1.format,
            json.decodeFromString<PageFormat>("\"SQUARE_1_1\""),
        )
    }

    @Test
    fun `a custom size survives the round trip and an unknown name falls back`() {
        val custom = PageFormat.custom(1234, 4321)
        assertEquals(custom, json.decodeFromString<PageFormat>(json.encodeToString(custom)))
        assertEquals(PageFormat.Default, json.decodeFromString<PageFormat>("\"FORMATO_QUE_JA_NAO_EXISTE\""))
    }

    @Test
    fun `a custom size is held inside the limits`() {
        assertEquals(MAX_PAGE_SIDE, PageFormat.custom(9_000, 1_000).pixelWidth)
        assertEquals(MIN_PAGE_SIDE, PageFormat.custom(1_000, 10).pixelHeight)
        assertThrows(IllegalArgumentException::class.java) { PageFormat(10, 1_000, "curto demais") }
    }

    @Test
    fun `same asset keeps independent recipe snapshots`() {
        val now = Instant.EPOCH.toString()
        val media = MediaReference("asset", "checksum", "photo.jpg")
        val first = PhotoRecipeSnapshot(EditRecipe(assetId = "asset", originalChecksum = "checksum", createdAt = now, updatedAt = now))
        val second = PhotoRecipeSnapshot(first.recipe.copy(tone = first.recipe.tone.copy(exposure = 1f)))
        assertNotEquals(first, second)
        assertEquals(0f, first.recipe.tone.exposure)
        assertEquals(1f, second.recipe.tone.exposure)
        assertEquals(media.assetId, media.assetId)
    }

    @Test
    fun `video cannot cross a page separator`() {
        val now = Instant.EPOCH.toString()
        assertThrows(IllegalArgumentException::class.java) {
            CompositionProject(
                id = "invalid",
                name = "Invalid",
                format = PageFormatPreset.SQUARE_1_1.format,
                pages = listOf(CompositionPage("one", 0), CompositionPage("two", 1)),
                elements = listOf(
                    CompositionElement.Video(
                        id = "video",
                        transform = ElementTransform(NormalizedRect(.8f, 0f, .4f, 1f)),
                        zIndex = 0,
                        pageIndex = 0,
                        media = MediaReference("asset", "checksum", "clip.mp4", durationMs = 2_000),
                        timing = VideoTiming(trimEndMs = 2_000),
                    ),
                ),
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
