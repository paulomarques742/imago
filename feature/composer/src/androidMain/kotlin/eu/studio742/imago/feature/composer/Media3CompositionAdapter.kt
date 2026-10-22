package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageVideoPlan
import eu.studio742.imago.core.data.CompositionMediaRepository

/** The preview's longest side: 360 × 640 on a 9:16, as it always was. */
private const val PROXY_LONGEST_SIDE = 640

private const val PROXY_BITRATE = 2_000_000

/** Keeps all of Media3's unstable APIs out of the composer's model and interface. */
@OptIn(UnstableApi::class)
class Media3CompositionAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: CompositionMediaRepository,
    private val staticRenderer: CompositionStaticExporter,
) {
    /**
     * A page with video, as an MP4.
     *
     * Two things about Media3 1.5 shape what is built here. Its compositor draws the first sequence
     * on top of the others, so the layers go to it from the top down — handed over from the bottom
     * up, the still layer with the opaque background covered every video, and the page came out in
     * its background colour. And it does not accept gaps in a sequence with video, so the time a clip
     * is off screen is a transparent image instead of a gap; with gaps, every clip that started after
     * 0 s or ended before the page did failed the export.
     */
    suspend fun exportPage(
        project: CompositionProject,
        pageIndex: Int,
        quality: VideoExportQuality = VideoExportQuality.Default,
        proxy: Boolean = false,
    ): File {
        val plan = PageVideoPlan.of(project, pageIndex)
        if (plan.clips.isEmpty()) throw LocalizedException(uiText(Res.string.composer_page_has_no_video))
        val temporary = mutableListOf<File>()
        val output = File.createTempFile(if (proxy) "composition-proxy-" else "composition-video-", ".mp4", context.cacheDir)
        output.delete()
        try {
            // One download per file: the same video can be on the page twice.
            val sources = mutableMapOf<String, File>()
            plan.clips.forEach { clip ->
                val assetId = clip.video.media.assetId
                if (assetId in sources) return@forEach
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                sources[assetId] = File.createTempFile("composition-video-source-", ".mp4", context.cacheDir).also {
                    temporary += it
                    media.downloadOriginal(assetId, it)
                }
            }
            val (outputWidth, outputHeight) = videoExportSize(project.format, if (proxy) PROXY_LONGEST_SIDE else quality.longestSide)
            val blank by lazy { transparentImage().also { temporary += it } }
            val sequences = mutableListOf<EditedMediaItemSequence>()
            val placements = mutableListOf<CompositionElement.Video?>()
            plan.layers.forEach { layer ->
                when (layer) {
                    is PageVideoPlan.Layer.Still -> {
                        val image = staticRenderer.renderPageToCache(
                            project = project,
                            pageIndex = pageIndex,
                            includeBackground = layer.includesBackground,
                            elementIds = layer.elementIds,
                        ).also { temporary += it }
                        // The layer is drawn at the page's size; the compositor keeps an overlay's own
                        // size, so without this a page bigger than the video spilled over its edges.
                        val fit = Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT)
                        sequences += EditedMediaItemSequence(imageItem(image, plan.durationMs, fit))
                        placements += null
                    }
                    is PageVideoPlan.Layer.Clip -> {
                        sequences += clipSequence(layer, checkNotNull(sources[layer.video.media.assetId]), { blank }, outputWidth, outputHeight)
                        placements += layer.video
                    }
                }
            }
            // Media3 draws input 0 on top.
            sequences.reverse()
            placements.reverse()
            val composition = Composition.Builder(sequences)
                .setVideoCompositorSettings(PageVideoCompositorSettings(outputWidth, outputHeight, placements, pageIndex))
                .setEffects(Effects(listOf(PcmGainProcessor(gain = 1f, limiter = true)), emptyList()))
                // A clip that only starts after a transparent stretch has no audio there; without this,
                // Media3 refuses a sequence whose first item has no audio track and a later one does.
                .experimentalSetForceAudioTrack(true)
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            val bitrate = if (proxy) PROXY_BITRATE else quality.bitrate(outputWidth, outputHeight)
            runTransformer(composition, output, bitrate)
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            temporary.forEach(File::delete)
        }
    }

    private fun imageItem(image: File, durationMs: Long, presentation: Presentation): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(image)).setImageDurationMs(durationMs).build())
            .setFrameRate(IMAGE_FRAME_RATE)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()

    private fun clipSequence(
        clip: PageVideoPlan.Layer.Clip,
        source: File,
        blank: () -> File,
        outputWidth: Int,
        outputHeight: Int,
    ): EditedMediaItemSequence {
        val video = clip.video
        val bounds = video.transform.bounds
        val targetWidth = max(2, (bounds.width * outputWidth).roundToInt())
        val targetHeight = max(2, (bounds.height * outputHeight).roundToInt())
        val presentation = Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val videoEffects = mutableListOf<Effect>(presentation)
        if (video.transform.mirrorHorizontal || video.transform.mirrorVertical) {
            videoEffects += ScaleAndRotateTransformation.Builder().setScale(
                if (video.transform.mirrorHorizontal) -1f else 1f,
                if (video.transform.mirrorVertical) -1f else 1f,
            ).build()
        }
        val audio = if (!clip.audible || video.timing.volume == 1f) emptyList() else listOf<AudioProcessor>(PcmGainProcessor(video.timing.volume))
        val items = clip.segments.map { segment ->
            when (segment) {
                is PageVideoPlan.Segment.Blank -> imageItem(blank(), segment.durationMs, presentation)
                is PageVideoPlan.Segment.Source -> EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(source))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(segment.fromMs)
                                .setEndPositionMs(segment.toMs)
                                .build(),
                        ).build(),
                )
                    .setRemoveAudio(!clip.audible)
                    .setEffects(Effects(audio, videoEffects))
                    .build()
            }
        }
        return EditedMediaItemSequence(items)
    }

    /** A fully transparent image: what a clip shows while it is off screen. */
    private fun transparentImage(): File {
        val file = File.createTempFile("composition-blank-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(BLANK_SIDE, BLANK_SIDE, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private suspend fun runTransformer(composition: Composition, output: File, bitrate: Int) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            lateinit var transformer: Transformer
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            }
            val encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                // A size or bitrate the phone's encoder does not take is brought down to one it does,
                // rather than failing the export.
                .setEnableFallback(true)
                .build()
            transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setPortraitEncodingEnabled(true)
                .setEncoderFactory(encoders)
                .addListener(listener)
                .build()
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post(transformer::cancel) }
            transformer.start(composition, output.absolutePath)
        }
    }

    private companion object {
        const val IMAGE_FRAME_RATE = 30
        const val BLANK_SIDE = 16
    }
}

@OptIn(UnstableApi::class)
private class PageVideoCompositorSettings(
    private val width: Int,
    private val height: Int,
    private val placements: List<CompositionElement.Video?>,
    private val pageIndex: Int,
) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: MutableList<Size>): Size = Size(width, height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val video = placements.getOrNull(inputId) ?: return OverlaySettings.Builder().build()
        val bounds = video.transform.bounds
        val localCenterX = bounds.x - pageIndex + bounds.width / 2f
        val centerY = bounds.y + bounds.height / 2f
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(localCenterX * 2f - 1f, 1f - centerY * 2f)
            .setOverlayFrameAnchor(0f, 0f)
            .setAlphaScale(video.transform.opacity)
            .setRotationDegrees(video.transform.rotationDegrees)
            .build()
    }
}

/**
 * Volume, and a soft ceiling on the final mix.
 *
 * It takes 16-bit and float PCM. It used to take only 16-bit and throw on anything else, and a
 * decoder that hands out float — some do for high-resolution audio — failed the whole export.
 */
@OptIn(UnstableApi::class)
private class PcmGainProcessor(
    private val gain: Float,
    private val limiter: Boolean = false,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) {
                var sample = inputBuffer.float * gain
                if (limiter && abs(sample) > LIMIT) sample = kotlin.math.sign(sample) * LIMIT
                output.putFloat(sample.coerceIn(-1f, 1f))
            }
        } else {
            val threshold = Short.MAX_VALUE * LIMIT
            while (inputBuffer.remaining() >= 2) {
                var sample = inputBuffer.short.toInt() * gain
                if (limiter && abs(sample) > threshold) sample = kotlin.math.sign(sample) * threshold
                output.putShort(sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        output.flip()
    }

    private companion object {
        const val LIMIT = .95f
    }
}
