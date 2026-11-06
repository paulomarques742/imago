package eu.studio742.imago.core.composition

import org.junit.Assert.*
import org.junit.Test

class VideoFrameGeometryTest {
    @Test fun `landscape video pans to each source edge without changing its aspect ratio`() {
        val left = videoTextureSampling(1000f, 500f, 200f, 200f, PlacementCrop(-1f, 0f, 1f))
        val right = videoTextureSampling(1000f, 500f, 200f, 200f, PlacementCrop(1f, 0f, 1f))
        assertEquals(.5f, left.scaleX, .0001f); assertEquals(1f, left.scaleY, .0001f)
        assertEquals(0f, left.offsetX, .0001f); assertEquals(.5f, right.offsetX, .0001f)
        assertEquals(1f, right.offsetX + right.scaleX, .0001f)
    }

    @Test fun `vertical crop accounts for opposite GL and bitmap origins`() {
        val top = videoTextureSampling(500f, 1000f, 200f, 200f, PlacementCrop(0f, -1f, 1f))
        val bottom = videoTextureSampling(500f, 1000f, 200f, 200f, PlacementCrop(0f, 1f, 1f))
        assertEquals(.5f, top.scaleY, .0001f); assertEquals(.5f, top.offsetY, .0001f)
        assertEquals(0f, bottom.offsetY, .0001f); assertEquals(1f, top.offsetY + top.scaleY, .0001f)
    }

    @Test fun `zoom preserves the chosen source edge and clamps pan to the source`() {
        val sampling = videoTextureSampling(1000f, 500f, 200f, 200f, PlacementCrop(100f, -100f, 2f))
        assertEquals(.25f, sampling.scaleX, .0001f); assertEquals(.5f, sampling.scaleY, .0001f)
        assertEquals(1f, sampling.scaleX + sampling.offsetX, .0001f)
        assertEquals(1f, sampling.scaleY + sampling.offsetY, .0001f)
    }

    @Test fun `underlay and video compose to one element opacity at every coverage`() {
        listOf(0f, .1f, .5f, .8f, 1f).forEach { opacity ->
            listOf(0f, .2f, .5f, .9f, 1f).forEach { coverage ->
                listOf(0f, .5f, 1f).forEach { paperAlpha ->
                    val video = coverage * opacity
                    val underlay = paperAlpha * videoDecorationAlphaFactor(coverage, opacity)
                    val composite = video + underlay * (1f - video)
                    val expected = opacity * (coverage + paperAlpha * (1f - coverage))
                    assertEquals(expected, composite, .0001f)
                }
            }
        }
    }

    private fun video(id: String, z: Int) = CompositionElement.Video(id,
        ElementTransform(NormalizedRect(.1f, .2f, .4f, .3f), rotationDegrees = 25f, opacity = .6f, mirrorHorizontal = true), z, 0,
        MediaReference(id, "hash", "$id.mp4"), VideoTiming(trimStartMs = 2000, trimEndMs = 6000, startOffsetMs = 500, loop = true),
        crop = PlacementCrop(.4f, -.3f, 1.7f), frame = FrameStyle(borderWidth = 10f, shadowRadius = 8f,
            cutout = MediaCutout.HEART, decoration = MediaFrameDecoration(MediaFrameKind.POLAROID)))
    private fun project(elements: List<CompositionElement>) = CompositionProject(id = "movie", name = "Movie",
        format = PageFormatPreset.SQUARE_1_1.format, pages = listOf(CompositionPage("page", 0, durationMs = 5000)),
        elements = elements, createdAt = "", updatedAt = "")

    @Test fun `video decoration follows the exact visible segments including leading gaps and loops`() {
        val source = video("clip", 1)
        val plan = PageVideoPlan.of(project(listOf(source)), 0)
        val frame = plan.layers.filterIsInstance<PageVideoPlan.Layer.Decoration>().single()
        val clip = plan.clips.single()
        assertEquals(source, clip.video); assertEquals(source, frame.video)
        assertEquals(clip.segments, frame.segments)
        assertEquals(PageVideoPlan.Segment.Blank(500), frame.segments.first())
        assertEquals(PageVideoPlan.Segment.Source(2000, 2500), frame.segments.last())
        assertTrue(plan.layers.indexOf(frame) < plan.layers.indexOf(clip))
    }

    @Test fun `decorated clips retain their ordering between still elements`() {
        val text = CompositionElement.Text("middle", ElementTransform(NormalizedRect(.2f, .2f, .3f, .2f)), 2, "Sample")
        val plan = PageVideoPlan.of(project(listOf(video("first", 1), text, video("second", 3))), 0)
        assertEquals(listOf("still", "frame-first", "clip-first", "still", "frame-second", "clip-second"), plan.layers.map { layer ->
            when (layer) {
                is PageVideoPlan.Layer.Still -> "still"
                is PageVideoPlan.Layer.Decoration -> "frame-${layer.video.id}"
                is PageVideoPlan.Layer.Clip -> "clip-${layer.video.id}"
            }
        })
    }

    @Test fun `hidden clips and plain frames create no decoration sequences`() {
        val hidden = video("hidden", 0).copy(visible = false)
        val plain = video("plain", 1).copy(frame = FrameStyle(cutout = MediaCutout.CIRCLE))
        val plan = PageVideoPlan.of(project(listOf(hidden, plain)), 0)
        assertTrue(plan.layers.none { it is PageVideoPlan.Layer.Decoration })
        assertEquals(listOf(plain), plan.clips.map { it.video })
    }

    @Test fun `rotated partial clips render only bounded page pixels and retain their full geometry`() {
        for (aspect in listOf(.5f, 1f, 2f)) for (angle in listOf(0f, 25f, 90f, 315f)) {
            for (x in listOf(-.4f, .2f, .9f)) for (y in listOf(-.3f, .3f, .9f)) {
                val source = video("projection", 0).copy(transform = ElementTransform(NormalizedRect(x, y, .5f, .4f), rotationDegrees = angle))
                val width = (600 * aspect).toInt(); val height = 600
                val placement = source.projectedFrame(0, width, height)
                assertTrue(placement.left >= 0 && placement.top >= 0)
                assertTrue(placement.left + placement.width <= width && placement.top + placement.height <= height)
                if (!placement.visible) continue
                assertEquals((x + .25f) * width, placement.centreX, .0001f)
                assertEquals((y + .2f) * height, placement.centreY, .0001f)
                assertEquals(.5f * width, placement.frameWidth, .0001f)
                assertEquals(.4f * height, placement.frameHeight, .0001f)
                val visible = source.visualBounds(aspect = aspect).intersectionWithPage(0)!!
                assertTrue(placement.left <= visible.x * width + .001f)
                assertTrue(placement.top <= visible.y * height + .001f)
                assertTrue(placement.left + placement.width >= visible.right * width - .001f)
                assertTrue(placement.top + placement.height >= visible.bottom * height - .001f)
            }
        }
    }

    @Test fun `completely off page video hides the texture without removing its audio or timing`() {
        val source = video("outside", 0).copy(transform = ElementTransform(NormalizedRect(4f, 2f, .4f, .3f)))
        assertFalse(source.projectedFrame(0, 600, 600).visible)
        val clip = PageVideoPlan.of(project(listOf(source)), 0).clips.single()
        assertTrue(clip.audible); assertEquals(source.timing, clip.video.timing)
        assertEquals(source.transform.bounds, clip.video.transform.bounds)
    }

    @Test fun `pixel coordinates are relative to the explicit associated page`() {
        val source = video("page", 0).copy(pageIndex = 1, transform = ElementTransform(NormalizedRect(1.1f, .2f, .4f, .3f), rotationDegrees = 15f))
        val placement = source.projectedFrame(1, 600, 1000)
        assertEquals(180f, placement.centreX, .001f)
        assertEquals(350f, placement.centreY, .001f)
        assertEquals(240f, placement.frameWidth, .001f)
        assertEquals(1, source.pageIndex)
    }
}
