package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/** The shortest piece of a video the trim bar lets one keep. */
const val MIN_VIDEO_TRIM_MS = 500L

/**
 * The part of a video recipe that is not a rotation: which stretch of the original is kept.
 *
 * The rotation lives in [Geometry.rotation], where a photo's does, so a recipe that only turns the
 * video needs no [VideoEdit] at all. A null [endMs] is the end of the file, which keeps the recipe
 * valid if the server later reports another duration.
 */
@Serializable
data class VideoEdit(
    val startMs: Long = 0L,
    val endMs: Long? = null,
) {
    /** Whether this keeps the whole video. */
    val keepsEverything: Boolean get() = startMs <= 0L && endMs == null

    /** The kept stretch of a video that lasts [durationMs]; the whole of it when nothing is trimmed. */
    fun rangeIn(durationMs: Long): LongRange {
        val start = startMs.coerceIn(0L, (durationMs - MIN_VIDEO_TRIM_MS).coerceAtLeast(0L))
        val end = (endMs ?: durationMs).coerceIn(minOf(start + MIN_VIDEO_TRIM_MS, durationMs), durationMs)
        return start..end
    }
}

/** Whether the recipe cuts the video; a rotation alone is read from the geometry. */
fun EditRecipe.trimsTheVideo(): Boolean = video?.keepsEverything == false

/** The turn the player and the exporter give the picture, in clockwise degrees: 0, 90, 180 or 270. */
fun EditRecipe.videoRotationDegrees(): Int = ((geometry.rotation % 360) + 360) % 360

/** Whether the recipe changes a video at all, by turning it or by cutting it. */
fun EditRecipe.changesTheVideo(): Boolean = videoRotationDegrees() != 0 || trimsTheVideo()

/**
 * This recipe with the video kept from [startMs] to [endMs] of a video that lasts [durationMs].
 *
 * What is kept is turned into the shape that is stored: an edge that sits on the start or the end of
 * the file is not stored, and a recipe that keeps everything holds no [VideoEdit].
 */
fun EditRecipe.withVideoRange(startMs: Long, endMs: Long, durationMs: Long, updatedAt: String): EditRecipe {
    val range = VideoEdit(startMs, endMs).rangeIn(durationMs)
    val edit = VideoEdit(
        startMs = range.first.takeIf { it > 0L } ?: 0L,
        endMs = range.last.takeIf { it < durationMs },
    )
    return copy(video = edit.takeUnless { it.keepsEverything }, updatedAt = updatedAt)
}

/**
 * Where a cut edge lands: on the nearest of [keyframesMs] within [toleranceMs] — the only places a
 * cut that does not re-encode can fall — or else at the nearest tenth of a second.
 */
fun snapVideoEdge(valueMs: Long, keyframesMs: List<Long>, toleranceMs: Long): Long {
    val nearest = keyframesMs.minByOrNull { abs(it - valueMs) }
    if (nearest != null && abs(nearest - valueMs) <= toleranceMs) return nearest
    return (valueMs / 100.0).roundToLong() * 100L
}
