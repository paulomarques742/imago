package eu.studio742.imago.core.composition

import eu.studio742.imago.core.composition.PageVideoPlan.Layer
import eu.studio742.imago.core.composition.PageVideoPlan.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = "2026-10-22T10:00:00Z"

class VideoTimelineTest {
    private fun video(
        id: String,
        timing: VideoTiming,
        zIndex: Int = 1,
        visible: Boolean = true,
    ) = CompositionElement.Video(
        id = id,
        transform = ElementTransform(NormalizedRect(.1f, .1f, .5f, .5f)),
        zIndex = zIndex,
        pageIndex = 0,
        media = MediaReference(assetId = "asset-$id", checksum = "", fileName = "$id.mp4", durationMs = 10_000),
        timing = timing,
        visible = visible,
    )

    private fun text(id: String, zIndex: Int) = CompositionElement.Text(
        id = id,
        transform = ElementTransform(NormalizedRect(.1f, .7f, .5f, .1f)),
        zIndex = zIndex,
        text = id,
    )

    private fun project(pageMs: Long, vararg elements: CompositionElement) = CompositionProject(
        id = "project",
        name = "Test",
        format = PageFormatPreset.STORY_9_16.format,
        pages = listOf(CompositionPage(id = "page", index = 0, durationMs = pageMs)),
        elements = elements.toList(),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun Layer.Clip.total() = segments.sumOf { it.durationMs }

    @Test
    fun `a clip that starts late and ends early is padded with blanks to the page's length`() {
        val plan = PageVideoPlan.of(project(5_000, video("v", VideoTiming(trimStartMs = 1_000, trimEndMs = 3_000, startOffsetMs = 1_500))), 0)
        val clip = plan.clips.single()
        assertEquals(listOf(Segment.Blank(1_500), Segment.Source(1_000, 3_000), Segment.Blank(1_500)), clip.segments)
        assertEquals(5_000, clip.total())
        assertEquals(1_500L, clip.firstShownMs)
        assertEquals(2_000, clip.shownMs)
    }

    @Test
    fun `a clip longer than the page is cut at the page's end`() {
        val clip = PageVideoPlan.of(project(3_000, video("v", VideoTiming(trimEndMs = 8_000))), 0).clips.single()
        assertEquals(listOf(Segment.Source(0, 3_000)), clip.segments)
    }

    @Test
    fun `a looping clip repeats until the page ends, and a tail too short to see becomes blank`() {
        val looping = VideoTiming(trimStartMs = 0, trimEndMs = 2_000, loop = true)
        assertEquals(
            listOf(Segment.Source(0, 2_000), Segment.Source(0, 2_000), Segment.Source(0, 1_000)),
            PageVideoPlan.of(project(5_000, video("v", looping)), 0).clips.single().segments,
        )
        assertEquals(
            listOf(Segment.Source(0, 2_000), Segment.Source(0, 2_000), Segment.Blank(50)),
            PageVideoPlan.of(project(4_050, video("v", looping)), 0).clips.single().segments,
        )
    }

    @Test
    fun `a clip that starts after the page ends is never shown`() {
        val clip = PageVideoPlan.of(project(2_000, video("v", VideoTiming(trimEndMs = 1_000, startOffsetMs = 3_000))), 0).clips.single()
        assertEquals(listOf(Segment.Blank(2_000)), clip.segments)
        assertNull(clip.firstShownMs)
    }

    @Test
    fun `layers go from the bottom up, with the background in the first still`() {
        val plan = PageVideoPlan.of(
            project(
                5_000,
                text("below", zIndex = 0),
                video("v1", VideoTiming(trimEndMs = 5_000), zIndex = 1),
                text("between", zIndex = 2),
                video("v2", VideoTiming(trimEndMs = 5_000), zIndex = 3),
                text("above", zIndex = 4),
            ),
            0,
        )
        assertEquals(
            listOf("still(bg, below)", "clip v1", "still(between)", "clip v2", "still(above)"),
            plan.layers.map { layer ->
                when (layer) {
                    is Layer.Still -> "still(${listOfNotNull("bg".takeIf { layer.includesBackground }).plus(layer.elementIds).joinToString()})"
                    is Layer.Clip -> "clip ${layer.video.id}"
                }
            },
        )
    }

    @Test
    fun `a page that opens with a video still gets a background layer under it`() {
        val plan = PageVideoPlan.of(project(5_000, video("v", VideoTiming(trimEndMs = 5_000))), 0)
        assertEquals(Layer.Still(includesBackground = true, elementIds = emptySet()), plan.layers.first())
        assertTrue(plan.layers.last() is Layer.Clip)
    }

    @Test
    fun `mute, volume and solo decide what is heard, and hidden videos are left out`() {
        val plan = PageVideoPlan.of(
            project(
                5_000,
                video("loud", VideoTiming(trimEndMs = 5_000), zIndex = 1),
                video("solo", VideoTiming(trimEndMs = 5_000, solo = true), zIndex = 2),
                video("hidden", VideoTiming(trimEndMs = 5_000, solo = true), zIndex = 3, visible = false),
            ),
            0,
        )
        assertEquals(mapOf("loud" to false, "solo" to true), plan.clips.associate { it.video.id to it.audible })
        val quiet = PageVideoPlan.of(
            project(
                5_000,
                video("muted", VideoTiming(trimEndMs = 5_000, muted = true), zIndex = 1),
                video("silent", VideoTiming(trimEndMs = 5_000, volume = 0f), zIndex = 2),
                video("heard", VideoTiming(trimEndMs = 5_000), zIndex = 3),
            ),
            0,
        )
        assertEquals(mapOf("muted" to false, "silent" to false, "heard" to true), quiet.clips.associate { it.video.id to it.audible })
    }

    @Test
    fun `trimming the left edge moves the in-point and the start together`() {
        val timing = VideoTiming(trimStartMs = 1_000, trimEndMs = 4_000, startOffsetMs = 2_000)
        val cut = VideoTimelineEdits.trimStart(timing, pageMs = 2_500)
        assertEquals(VideoTiming(trimStartMs = 1_500, trimEndMs = 4_000, startOffsetMs = 2_500), cut)
        // Pulling it back brings the cut part back, but never before the source or the page begins.
        assertEquals(VideoTiming(trimStartMs = 0, trimEndMs = 4_000, startOffsetMs = 1_000), VideoTimelineEdits.trimStart(timing, pageMs = 0))
        // And never past the right edge.
        assertEquals(3_900, VideoTimelineEdits.trimStart(timing, pageMs = 9_000).trimStartMs)
    }

    @Test
    fun `trimming the right edge stops at the end of the source and never empties the clip`() {
        val timing = VideoTiming(trimStartMs = 1_000, trimEndMs = 4_000, startOffsetMs = 2_000)
        assertEquals(3_000, VideoTimelineEdits.trimEnd(timing, pageMs = 4_000, sourceDurationMs = 10_000).trimEndMs)
        assertEquals(10_000, VideoTimelineEdits.trimEnd(timing, pageMs = 30_000, sourceDurationMs = 10_000).trimEndMs)
        assertEquals(1_100, VideoTimelineEdits.trimEnd(timing, pageMs = 0, sourceDurationMs = 10_000).trimEndMs)
    }

    @Test
    fun `moving keeps the clip on the page`() {
        val timing = VideoTiming(trimEndMs = 2_000, startOffsetMs = 1_000)
        assertEquals(0, VideoTimelineEdits.move(timing, -500).startOffsetMs)
        assertEquals(MAX_PAGE_DURATION_MS, VideoTimelineEdits.move(timing, 90_000).startOffsetMs)
        assertEquals(timing.clipDurationMs, VideoTimelineEdits.move(timing, 3_000).clipDurationMs)
    }

    @Test
    fun `a drag lands on a nearby edge, or else on the step`() {
        assertEquals(5_000, VideoTimelineEdits.snap(4_930, listOf(0, 5_000), toleranceMs = 100))
        assertEquals(4_800, VideoTimelineEdits.snap(4_780, listOf(0, 5_000), toleranceMs = 100))
        assertFalse(VideoTimelineEdits.snap(1_234, emptyList(), toleranceMs = 0) % TIMELINE_STEP_MS != 0L)
        assertEquals(MIN_PAGE_DURATION_MS, VideoTimelineEdits.pageDuration(10))
    }
}
