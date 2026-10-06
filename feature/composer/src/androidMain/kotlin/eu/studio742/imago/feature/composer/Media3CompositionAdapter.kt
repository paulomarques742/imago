package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import android.content.Context
import android.graphics.RectF
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
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
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
import kotlin.math.min
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.VideoTiming
import eu.studio742.imago.core.data.CompositionMediaRepository

/**
 * The longest side an exported MP4 can have.
 *
 * Pages are no longer all 1080 px: an "A4 portrait" is 2970 px tall, and a 3:1 panorama is 3000 px
 * wide — sizes most phones' hardware encoder refuses. The video comes out reduced, with the page's
 * aspect ratio intact; the image export still comes out at the requested size, because there no
 * encoder imposes limits.
 */
private const val MAX_VIDEO_LONGEST_SIDE = 1920

/** The same, for the preview: 360 × 640 on a 9:16, as it always was. */
private const val PROXY_LONGEST_SIDE = 640

/**
 * The size this can be encoded at: never larger than [longestSide] and always even.
 *
 * H.264 counts in macroblocks and an odd height — which a reduced 1500 × 1000 easily gives — makes the
 * encoder refuse the job or round on its own, stretching the image.
 */
private fun encodableSize(width: Int, height: Int, longestSide: Int): Pair<Int, Int> {
    val scale = min(1f, longestSide.toFloat() / max(width, height))
    fun even(value: Float) = max(2, (value.roundToInt() / 2) * 2)
    return even(width * scale) to even(height * scale)
}

/** Keeps all of Media3's unstable APIs out of the composer's model and interface. */
@OptIn(UnstableApi::class)
class Media3CompositionAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: CompositionMediaRepository,
    private val staticRenderer: CompositionStaticExporter,
) {
    suspend fun exportPage(project: CompositionProject, pageIndex: Int, proxy: Boolean = false): File {
        val page = project.pages[pageIndex]
        val ordered = project.elements.filter { it.visible }
            .filter { it !is CompositionElement.Video || it.pageIndex == pageIndex }
            .sortedWith(compareBy<CompositionElement> { it.zIndex }.thenBy { it.id })
        val videos = ordered.filterIsInstance<CompositionElement.Video>()
        if (videos.isEmpty()) throw LocalizedException(uiText(Res.string.composer_page_has_no_video))
        val downloads = mutableListOf<File>()
        val layerImages = mutableListOf<File>()
        val output = File.createTempFile(if (proxy) "composition-proxy-" else "composition-video-", ".mp4", context.cacheDir)
        output.delete()
        try {
            val sources = videos.associateWith { video ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                File.createTempFile("composition-video-source-", ".mp4", context.cacheDir).also {
                    downloads += it
                    media.downloadOriginal(video.media.assetId, it)
                }
            }
            val (outputWidth, outputHeight) = encodableSize(
                project.format.pixelWidth,
                project.format.pixelHeight,
                longestSide = if (proxy) PROXY_LONGEST_SIDE else MAX_VIDEO_LONGEST_SIDE,
            )
            val sequences = mutableListOf<EditedMediaItemSequence>()
            val soloActive = videos.any { it.timing.solo }
            val placements = mutableListOf<CompositionElement.Video?>()
            var staticIds = mutableSetOf<String>()
            var backgroundPending = true
            suspend fun flushStaticLayer() {
                if (!backgroundPending && staticIds.isEmpty()) return
                val image = staticRenderer.renderPageToCache(
                    project = project,
                    pageIndex = pageIndex,
                    includeBackground = backgroundPending,
                    elementIds = staticIds,
                )
                layerImages += image
                val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(image)).setImageDurationMs(page.durationMs).build()
                sequences += EditedMediaItemSequence.Builder(
                    EditedMediaItem.Builder(mediaItem).setFrameRate(30).setRemoveAudio(true).build(),
                ).build()
                placements += null
                staticIds = mutableSetOf()
                backgroundPending = false
            }
            ordered.forEach { element ->
                if (element is CompositionElement.Video) {
                    flushStaticLayer()
                    sequences += buildVideoSequence(
                        video = element,
                        source = checkNotNull(sources[element]),
                        pageDurationMs = page.durationMs,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        removeAudio = element.timing.muted || soloActive && !element.timing.solo,
                    )
                    placements += element
                } else {
                    staticIds += element.id
                }
            }
            flushStaticLayer()
            val composition = Composition.Builder(sequences)
                .setVideoCompositorSettings(PageVideoCompositorSettings(outputWidth, outputHeight, placements, pageIndex))
                .setEffects(Effects(listOf(Pcm16GainProcessor(gain = 1f, limiter = true)), emptyList()))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            runTransformer(composition, output)
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            layerImages.forEach(File::delete)
            downloads.forEach(File::delete)
        }
    }

    private fun buildVideoSequence(
        video: CompositionElement.Video,
        source: File,
        pageDurationMs: Long,
        outputWidth: Int,
        outputHeight: Int,
        removeAudio: Boolean,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()
        val timing = video.timing
        if (timing.startOffsetMs > 0) builder.addGap(timing.startOffsetMs * 1_000)
        var remaining = pageDurationMs - timing.startOffsetMs
        val sourceClipDuration = timing.trimEndMs - timing.trimStartMs
        do {
            val duration = minOf(sourceClipDuration, remaining)
            if (duration <= 0) break
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(timing.trimStartMs)
                        .setEndPositionMs(timing.trimStartMs + duration)
                        .build(),
                ).build()
            val bounds = video.transform.bounds
            val targetWidth = max(2, (bounds.width * outputWidth).roundToInt())
            val targetHeight = max(2, (bounds.height * outputHeight).roundToInt())
            val effects = mutableListOf<Effect>()
            effects += Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            if (video.transform.mirrorHorizontal || video.transform.mirrorVertical) {
                effects += ScaleAndRotateTransformation.Builder().setScale(
                    if (video.transform.mirrorHorizontal) -1f else 1f,
                    if (video.transform.mirrorVertical) -1f else 1f,
                ).build()
            }
            val audio = if (removeAudio || timing.volume == 1f) emptyList() else listOf<AudioProcessor>(Pcm16GainProcessor(timing.volume))
            builder.addItem(
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(removeAudio)
                    .setEffects(Effects(audio, effects))
                    .build(),
            )
            remaining -= duration
        } while (timing.loop && remaining > 0)
        if (remaining > 0) builder.addGap(remaining * 1_000)
        return builder.build()
    }

    private suspend fun runTransformer(composition: Composition, output: File) = withContext(Dispatchers.Main) {
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
            transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setPortraitEncodingEnabled(true)
                .addListener(listener)
                .build()
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post(transformer::cancel) }
            transformer.start(composition, output.absolutePath)
        }
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
        if (inputId == 0) return OverlaySettings.Builder().build()
        val video = placements[inputId] ?: return OverlaySettings.Builder().build()
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

@OptIn(UnstableApi::class)
private class Pcm16GainProcessor(
    private val gain: Float,
    private val limiter: Boolean = false,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        while (inputBuffer.remaining() >= 2) {
            var sample = inputBuffer.short.toInt() * gain
            if (limiter) {
                val threshold = Short.MAX_VALUE * .95f
                if (abs(sample) > threshold) sample = kotlin.math.sign(sample) * threshold
            }
            output.putShort(sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        output.flip()
    }
}
