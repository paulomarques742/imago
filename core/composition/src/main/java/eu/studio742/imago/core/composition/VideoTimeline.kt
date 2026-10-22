package eu.studio742.imago.core.composition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** The shortest piece of a clip the timeline lets one keep. */
const val MIN_CLIP_MS = 100L

/** The shortest page there is, with or without video. */
const val MIN_PAGE_DURATION_MS = 1_000L

/**
 * The step the timeline moves in. Dragging is continuous, but a clip that starts at 2.137 s means
 * nothing to anyone, and the encoders count in frames anyway.
 */
const val TIMELINE_STEP_MS = 100L

/** How long the clip plays from its source, once. */
val VideoTiming.clipDurationMs: Long get() = trimEndMs - trimStartMs

/**
 * The edits the timeline makes to a clip, in page time.
 *
 * They live here, and not in the screen, because the rules are the model's: a clip never starts
 * before the page, never keeps less than [MIN_CLIP_MS] and never reads past the end of its source.
 * Each one takes the value the finger points at and returns a timing that `VideoTiming` accepts —
 * the drag never has to know what would make `init` throw.
 */
object VideoTimelineEdits {
    /** Slides the whole clip along the page. What plays does not change, only when. */
    fun move(timing: VideoTiming, startOffsetMs: Long): VideoTiming =
        timing.copy(startOffsetMs = startOffsetMs.coerceIn(0, MAX_PAGE_DURATION_MS))

    /**
     * Moves the clip's left edge to [pageMs].
     *
     * As in an editing timeline, the in-point and the start on the page move together and the right
     * edge stays where it was: pulling the edge right cuts the beginning of the clip, pulling it left
     * brings back what had been cut.
     */
    fun trimStart(timing: VideoTiming, pageMs: Long): VideoTiming {
        // The floor goes last: whatever else gives, neither the source nor the page may go negative.
        val shift = (pageMs - timing.startOffsetMs)
            .coerceAtMost(min(timing.clipDurationMs - MIN_CLIP_MS, MAX_PAGE_DURATION_MS - timing.startOffsetMs))
            .coerceAtLeast(-min(timing.trimStartMs, timing.startOffsetMs))
        return timing.copy(trimStartMs = timing.trimStartMs + shift, startOffsetMs = timing.startOffsetMs + shift)
    }

    /**
     * Moves the clip's right edge to [pageMs].
     *
     * [sourceDurationMs] is the length of the video file; without it, the clip can only shrink or
     * grow back up to the page's ceiling.
     */
    fun trimEnd(timing: VideoTiming, pageMs: Long, sourceDurationMs: Long?): VideoTiming {
        val ceiling = min(sourceDurationMs ?: Long.MAX_VALUE, timing.trimStartMs + MAX_PAGE_DURATION_MS)
            .coerceAtLeast(timing.trimEndMs)
        val end = (timing.trimStartMs + pageMs - timing.startOffsetMs)
            .coerceIn(timing.trimStartMs + MIN_CLIP_MS, max(ceiling, timing.trimStartMs + MIN_CLIP_MS))
        return timing.copy(trimEndMs = end)
    }

    fun pageDuration(durationMs: Long): Long = durationMs.coerceIn(MIN_PAGE_DURATION_MS, MAX_PAGE_DURATION_MS)

    /**
     * The value a drag lands on: the nearest of [targets] within [toleranceMs], or else the nearest
     * [TIMELINE_STEP_MS]. The targets are the page's start and end and the other clips' edges — what
     * one wants to line up with.
     */
    fun snap(valueMs: Long, targets: Collection<Long>, toleranceMs: Long): Long {
        val nearest = targets.minByOrNull { abs(it - valueMs) }
        if (nearest != null && abs(nearest - valueMs) <= toleranceMs) return nearest
        return (valueMs.toDouble() / TIMELINE_STEP_MS).roundToLong() * TIMELINE_STEP_MS
    }
}

/**
 * A page with video as an encoder sees it: still layers and clips, from the bottom up.
 *
 * Android (Media3) and the computer (FFmpeg) build their videos from this same plan, so a clip that
 * starts late, loops or ends before the page does the same on both. Before it existed, each exporter
 * worked out the timing on its own, and the Android one asked Media3 for things it does not do.
 */
data class PageVideoPlan(val durationMs: Long, val layers: List<Layer>) {
    sealed interface Layer {
        /** Everything that does not move, between two clips. The bottom one carries the background. */
        data class Still(val includesBackground: Boolean, val elementIds: Set<String>) : Layer

        /**
         * One video, as a run of [segments] that add up to the page's duration exactly.
         *
         * [audible] already accounts for mute, volume and solo.
         */
        data class Clip(val video: CompositionElement.Video, val segments: List<Segment>, val audible: Boolean) : Layer {
            /** When the clip first shows, in page time; null when it never does. */
            val firstShownMs: Long?
                get() {
                    var cursor = 0L
                    segments.forEach { segment ->
                        if (segment is Segment.Source) return cursor
                        cursor += segment.durationMs
                    }
                    return null
                }

            /** How long the clip is on screen in total, counting every loop. */
            val shownMs: Long get() = segments.filterIsInstance<Segment.Source>().sumOf { it.durationMs }
        }
    }

    sealed interface Segment {
        val durationMs: Long

        /** The clip is not on screen. */
        data class Blank(override val durationMs: Long) : Segment

        /** The clip plays [fromMs] to [toMs] of its source. */
        data class Source(val fromMs: Long, val toMs: Long) : Segment {
            override val durationMs: Long get() = toMs - fromMs
        }
    }

    val clips: List<Layer.Clip> get() = layers.filterIsInstance<Layer.Clip>()

    companion object {
        /**
         * The plan of [pageIndex].
         *
         * A piece of clip shorter than [MIN_CLIP_MS] — the tail of a loop cut by the end of the page —
         * becomes blank: encoders may not get a single frame out of it, and nobody sees it.
         */
        fun of(project: CompositionProject, pageIndex: Int): PageVideoPlan {
            val duration = project.pages[pageIndex].durationMs
            val ordered = project.elements.filter { it.visible }
                .filter { it !is CompositionElement.Video || it.pageIndex == pageIndex }
                .sortedWith(compareBy<CompositionElement> { it.zIndex }.thenBy { it.id })
            val videos = ordered.filterIsInstance<CompositionElement.Video>()
            val soloActive = videos.any { it.timing.solo }
            val layers = mutableListOf<Layer>()
            var still = mutableSetOf<String>()
            var backgroundPending = true
            fun flush() {
                if (!backgroundPending && still.isEmpty()) return
                layers += Layer.Still(backgroundPending, still)
                still = mutableSetOf()
                backgroundPending = false
            }
            ordered.forEach { element ->
                if (element is CompositionElement.Video) {
                    flush()
                    val timing = element.timing
                    layers += Layer.Clip(
                        video = element,
                        segments = segments(timing, duration),
                        audible = !timing.muted && timing.volume > 0f && (!soloActive || timing.solo),
                    )
                } else {
                    still += element.id
                }
            }
            flush()
            return PageVideoPlan(duration, layers)
        }

        private fun segments(timing: VideoTiming, pageMs: Long): List<Segment> {
            val raw = mutableListOf<Segment>()
            val start = min(timing.startOffsetMs, pageMs)
            if (start > 0) raw += Segment.Blank(start)
            var cursor = start
            while (cursor < pageMs) {
                val length = min(timing.clipDurationMs, pageMs - cursor)
                raw += if (length < MIN_CLIP_MS) Segment.Blank(length) else Segment.Source(timing.trimStartMs, timing.trimStartMs + length)
                cursor += length
                if (!timing.loop) break
            }
            if (cursor < pageMs) raw += Segment.Blank(pageMs - cursor)
            // Two blanks in a row are one: each blank is an item for the encoder.
            return raw.fold(mutableListOf()) { merged, segment ->
                val last = merged.lastOrNull()
                if (segment is Segment.Blank && last is Segment.Blank) {
                    merged[merged.lastIndex] = Segment.Blank(last.durationMs + segment.durationMs)
                } else {
                    merged += segment
                }
                merged
            }
        }
    }
}
