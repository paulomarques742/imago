package eu.studio742.imago.core.composition

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompositionMediaFrameTest {
    @Test fun `old frame JSON retains the previous appearance by default`() {
        val frame = Json.decodeFromString<FrameStyle>("""{"cornerRadius":0.2,"borderWidth":12.0,"borderArgb":4294967295,"shadowRadius":8.0}""")
        assertEquals(MediaCutout.RECTANGLE, frame.cutout)
        assertEquals(MediaFrameKind.NONE, frame.decoration.kind)
        assertEquals(12f, frame.borderWidth, .0001f)
    }

    @Test fun `all frame parameters round trip without changing the torn edge seed`() {
        val frame = FrameStyle(cornerRadius = .1f, borderWidth = 14f, cutout = MediaCutout.TORN_PAPER,
            decoration = MediaFrameDecoration(MediaFrameKind.POLAROID, width = 32f, colorArgb = 0xCC887766,
                opacity = .65f, bottomWeight = 2.5f, roughness = .8f, seed = 237))
        assertEquals(frame, Json.decodeFromString<FrameStyle>(Json.encodeToString(frame)))
    }

    @Test fun `all cutout polygons stay finite inside their mask and have enough points to close`() {
        MediaCutout.entries.forEach { kind ->
            val polygon = kind.polygon(700f, 400f, MediaFrameDecoration())
            assertTrue(polygon.size >= 4)
            assertTrue(polygon.all { it.x.isFinite() && it.y.isFinite() && it.x >= -.001f && it.x <= 700.001f && it.y >= -.001f && it.y <= 400.001f })
        }
    }

    @Test fun `the circle remains circular in a portrait rectangle`() {
        val polygon = MediaCutout.CIRCLE.polygon(200f, 500f, MediaFrameDecoration())
        val width = polygon.maxOf { it.x } - polygon.minOf { it.x }
        val height = polygon.maxOf { it.y } - polygon.minOf { it.y }
        assertEquals(200f, width, .001f); assertEquals(width, height, .001f)
    }

    @Test fun `torn geometry is deterministic resolution independent and changes only with its parameters`() {
        val first = tornFramePolygon(0f, 0f, 300f, 200f, 742, .7f)
        val same = tornFramePolygon(0f, 0f, 300f, 200f, 742, .7f)
        val doubled = tornFramePolygon(0f, 0f, 600f, 400f, 742, .7f)
        assertEquals(first, same)
        first.zip(doubled).forEach { (a, b) -> assertEquals(a.x * 2f, b.x, .001f); assertEquals(a.y * 2f, b.y, .001f) }
        assertNotEquals(first, tornFramePolygon(0f, 0f, 300f, 200f, 743, .7f))
    }

    @Test fun `Polaroid bottom margin grows independently and follows page scaling`() {
        val frame = FrameStyle(decoration = MediaFrameDecoration(kind = MediaFrameKind.POLAROID, width = 20f, bottomWeight = 3f))
        assertEquals(FrameInsets(20f, 20f, 20f, 80f), frame.decorationInsets())
        assertEquals(FrameInsets(10f, 10f, 10f, 40f), frame.decorationInsets(.5f))
        assertEquals(FrameInsets(0f, 0f, 0f, 0f), FrameStyle().decorationInsets())
    }

    @Test fun `cover crop pans across the spare source area and never stretches the image`() {
        val left = placementImageRect(1000f, 500f, 200f, 200f, PlacementCrop(-1f, 0f, 1f))
        val right = placementImageRect(1000f, 500f, 200f, 200f, PlacementCrop(1f, 0f, 1f))
        assertEquals(0f, left.left, .001f); assertEquals(-200f, right.left, .001f)
        assertEquals(2f, left.width / left.height, .001f)
        val zoom = placementImageRect(1000f, 500f, 200f, 200f, PlacementCrop(1f, 1f, 2f))
        assertEquals(2f, zoom.width / zoom.height, .001f)
        assertEquals(200f, zoom.left + zoom.width, .001f); assertEquals(200f, zoom.top + zoom.height, .001f)
    }

    @Test fun `decorative borders and shadow expand the culling bounds without moving the saved photo`() {
        val source = NormalizedRect(.1f, .2f, .4f, .3f)
        val frame = FrameStyle(shadowRadius = 10f, decoration = MediaFrameDecoration(MediaFrameKind.POLAROID, width = 40f))
        val element = CompositionElement.MediaPlaceholder("slot", ElementTransform(source), 0, frame = frame)
        val painted = element.paintedBounds(aspect = 1f)
        assertTrue(painted.x < source.x); assertTrue(painted.bottom > source.bottom)
        assertEquals(source, element.transform.bounds)
    }
}
