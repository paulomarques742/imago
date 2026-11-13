package eu.studio742.imago.feature.detail

import eu.studio742.imago.core.model.EditRecipe
import java.io.File

/**
 * Writes the edited video as a file of its own, without encoding it again: the picture is turned by
 * the file's orientation and the cut copies the samples that are kept, so quality, location and date
 * stay as the original had them. Null where the platform cannot do it.
 */
interface VideoEditExport {
    /**
     * Writes [source] with [recipe]'s turn and cut to [output]. The cut cannot start between two
     * keyframes, so it starts on the one before the start asked for: nothing the recipe keeps is lost,
     * and what comes before shows for a fraction of a second.
     *
     * The new file keeps the original's recording date; [createdAt] stands in for it when the original
     * has none in its headers.
     */
    suspend fun export(source: File, recipe: EditRecipe, output: File, createdAt: String)
}

/** Where an edited video goes. */
sealed interface VideoExportTarget {
    /** The user's videos on this device. */
    data object Device : VideoExportTarget

    /** A new video in the Immich library [libraryId]. */
    data class Immich(val libraryId: String) : VideoExportTarget
}

/** Sends a finished file to an Immich library; null where the platform has no way to. */
interface VideoUploader {
    /** Fails now if the key of [libraryId] may not upload, before the video is made. */
    suspend fun checkCanUpload(libraryId: String)

    suspend fun upload(libraryId: String, file: File, fileName: String, mimeType: String, createdAt: String)
}

/** The dates [output] takes: [source]'s own, or [createdAt]'s when the original's headers carry none. */
internal fun keepRecordingDates(source: File, output: File, createdAt: String) {
    val times = Mp4Dates.read(source)?.takeIf { it.creation > 0L }
        ?: epochMillisOf(createdAt)?.let { mp4SecondsOf(it) }?.let { Mp4Times(it, it) }
        ?: return
    Mp4Dates.write(output, times)
}

private fun epochMillisOf(value: String): Long? =
    runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()

/** The quarter turns a file shows after another [turnDegrees] is added to the [fileDegrees] it already carries. */
internal fun combinedOrientation(fileDegrees: Int, turnDegrees: Int): Int =
    (((fileDegrees + turnDegrees) % 360) + 360) % 360

/** A location as MP4 files write it, `+38.7223-009.1393/`, or null when it is not one. */
internal fun parseIso6709(text: String?): Pair<Double, Double>? {
    val match = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)(?:[+-]\d+(?:\.\d+)?)?/?$""").matchEntire(text?.trim().orEmpty()) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return latitude to longitude
}

/**
 * Whether a sample of the original goes into the cut: from the keyframe the cut starts on up to the
 * end asked for, in microseconds; no [endUs] is the end of the file.
 */
internal fun keepsSample(timeUs: Long, startKeyUs: Long, endUs: Long?): Boolean =
    timeUs >= startKeyUs && (endUs == null || timeUs <= endUs)
