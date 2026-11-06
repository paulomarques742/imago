package eu.studio742.imago.core.composition

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompositionOutlineModelTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.2f, .3f, .4f, .3f), opacity = .65f),
        0, ShapeKind.RECTANGLE, fillArgb = 0x002A6B8C, strokeArgb = 0x8044AA77, strokeWidth = 120f, cornerRadius = .25f)

    @Test fun `hollow rounded shapes keep fill outline opacity and corners in saved projects and templates`() {
        val project = CompositionProject(id = "outline", name = "Outline", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = listOf(shape), createdAt = "", updatedAt = "")
        val restored = json.decodeFromString<CompositionProject>(json.encodeToString(CompositionProject.serializer(), project))
        assertEquals(shape, restored.elements.single())
        val template = CompositionTemplate("template", "Outline", project, false, "", "")
        assertEquals(template, json.decodeFromString<CompositionTemplate>(json.encodeToString(CompositionTemplate.serializer(), template)))
    }

    @Test fun `a shape saved before corner controls opens square and retains its transparent fill`() {
        val legacy = """{"type":"shape","id":"old","transform":{"bounds":{"x":0.2,"y":0.3,"width":0.4,"height":0.3}},"zIndex":0,"kind":"RECTANGLE","fillArgb":2771852,"strokeArgb":4282690167,"strokeWidth":40} """
        val restored = json.decodeFromString<CompositionElement>(legacy) as CompositionElement.Shape
        assertEquals(0f, restored.cornerRadius, 0f)
        assertEquals(0L, restored.fillArgb ushr 24)
        assertEquals(40f, restored.strokeWidth, 0f)
    }

    @Test fun `outside paint is retained for a thick outline crossing the page while manipulation bounds stay unchanged`() {
        val outside = shape.copy(transform = shape.transform.copy(bounds = NormalizedRect(-.15f, .3f, .1f, .2f)))
        assertNull(outside.visualBounds(aspect = 1f).intersectionWithPage(0))
        assertNotNull(outside.paintedBounds(aspect = 1f).intersectionWithPage(0))
        assertEquals(-.15f, outside.transform.bounds.x, 0f)
        assertEquals(.1f, outside.transform.bounds.width, 0f)
    }
}
