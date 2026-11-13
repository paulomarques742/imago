package eu.studio742.imago.feature.detail

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.videoRotationDegrees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

private const val SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024

/**
 * [VideoEditExport] with the platform's extractor and muxer: samples are copied, never decoded, so
 * the export takes the time of a file copy and the picture is exactly the original's.
 */
class LosslessVideoExport : VideoEditExport {
    override suspend fun export(source: File, recipe: EditRecipe, output: File, createdAt: String) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val fileRotation: Int
        val location: Pair<Double, Double>?
        try {
            retriever.setDataSource(source.absolutePath)
            fileRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            location = parseIso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
        } finally {
            retriever.release()
        }
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val tracks = mutableMapOf<Int, Int>()
            var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                // Other tracks — camera metadata, timecodes — have no place in a muxer that writes video and sound.
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(index)
                tracks[index] = muxer.addTrack(format)
                if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = index
            }
            require(videoTrack >= 0) { "The file has no video track" }
            muxer.setOrientationHint(combinedOrientation(fileRotation, recipe.videoRotationDegrees()))
            location?.let { (latitude, longitude) -> muxer.setLocation(latitude.toFloat(), longitude.toFloat()) }

            val trim = recipe.video
            val endUs = trim?.endMs?.let { it * 1000L }
            if (trim != null && trim.startMs > 0L) extractor.seekTo(trim.startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // The keyframe the cut starts on is read from the video track alone: with the sound selected,
            // the first sample after the seek may be an audio one, earlier than the picture.
            val startKeyUs = if (trim == null || trim.startMs <= 0L) 0L else keyframeAtOrBefore(source, videoTrack, trim.startMs * 1000L)

            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            while (true) {
                coroutineContext.ensureActive()
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (endUs != null && time > endUs) break
                if (keepsSample(time, startKeyUs, endUs)) {
                    buffer.clear()
                    info.set(0, extractor.readSampleData(buffer, 0), time - startKeyUs, extractor.sampleFlags)
                    muxer.writeSampleData(tracks.getValue(track), buffer, info)
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
        keepRecordingDates(source, output, createdAt)
    }
}

private fun keyframeAtOrBefore(source: File, videoTrack: Int, timeUs: Long): Long {
    val probe = MediaExtractor()
    try {
        probe.setDataSource(source.absolutePath)
        probe.selectTrack(videoTrack)
        probe.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        return probe.sampleTime.coerceAtLeast(0L)
    } finally {
        probe.release()
    }
}
