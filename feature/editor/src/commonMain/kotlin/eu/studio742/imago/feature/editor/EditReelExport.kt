package eu.studio742.imago.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextMeasurer
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import coil3.Bitmap
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.reel_failed
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a finished video went, and what to tell the person about it. */
data class SavedReel(val location: String, val message: UiText)

/** What making a video depends on per platform: the encoder, where the file is kept, and sharing. */
interface ReelPlatform {
    /**
     * The largest frame this device encodes, against 1080 × 1920: 1 almost everywhere, less on an
     * encoder that refuses a portrait Full HD frame.
     */
    fun frameScale(): Float

    /**
     * What draws the photo of each frame from [photo] — the bitmap the editor has open — for a
     * frame that shows it at most [maxSide] on its longest side. Called off the main thread; the
     * caller closes it. A RAW comes with [highPrecisionSource], the developed image the editor shows.
     */
    fun photoRenderer(
        photo: Bitmap,
        maxSide: Int,
        highPrecisionSource: eu.studio742.imago.core.render.FloatPixelBuffer? = null,
    ): ReelPhotoRenderer

    /**
     * Encodes [frameCount] frames of [width] × [height] at [fps] into a temporary MP4 the caller
     * deletes. [frame] draws each one, in order, on the calling thread; cancelling stops between frames.
     */
    suspend fun encode(
        width: Int,
        height: Int,
        fps: Int,
        frameCount: Int,
        frame: (Int) -> ImageBitmap,
        onProgress: (Float) -> Unit,
    ): File

    /** Keeps the video with the person's own; null if they gave up in a dialog. */
    suspend fun save(video: File, fileName: String): SavedReel?

    /** The system's share menu for a saved video; null where the platform has none. */
    val share: ((SavedReel) -> Unit)?

    /**
     * An export has begun, on the main thread: the platform keeps the process alive and shows the
     * progress outside the app, where it has a way to. Reads [ReelExports.state] to follow it.
     */
    fun exportStarted() = Unit
}

@Composable
expect fun rememberReelPlatform(): ReelPlatform

/** The video's file name: the photo's, with a suffix of its own so no export of a photo is mistaken for it. */
internal fun reelFileName(original: String): String =
    original.substringBeforeLast('.', original).ifBlank { "IMAGO" }.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_ShowYourEdit.mp4"

data class ReelExportState(
    /** From 0 to 1 while the video is being made; null otherwise. */
    val progress: Float? = null,
    val saved: SavedReel? = null,
    val error: UiText? = null,
)

/**
 * Makes the video away from the screen, and from the editor: rotating the tablet, closing the sheet,
 * leaving the photo or leaving the app halfway does not throw away a minute of rendering. It belongs to
 * the process, not to a screen; one video at a time.
 */
object ReelExports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(ReelExportState())
    val state: StateFlow<ReelExportState> = mutableState.asStateFlow()
    private var job: Job? = null

    /**
     * @param photo the bitmap the editor has open, before any recipe
     * @param textMeasurer one this export alone uses: the frames are measured off the main thread
     */
    fun export(
        platform: ReelPlatform,
        script: EditReelScript,
        style: ReelStyle,
        title: String?,
        photo: Bitmap,
        fileName: String,
        textMeasurer: TextMeasurer,
        highPrecisionSource: eu.studio742.imago.core.render.FloatPixelBuffer? = null,
    ) {
        if (job?.isActive == true) return
        mutableState.value = ReelExportState(progress = 0f)
        platform.exportStarted()
        job = scope.launch {
            var video: File? = null
            try {
                val texts = reelTexts(script, title)
                video = withContext(Dispatchers.Default) {
                    val painter = ReelFramePainter(script, style, texts, textMeasurer, scale = platform.frameScale())
                    platform.photoRenderer(photo, painter.photoMaxSide, highPrecisionSource).use { photos ->
                        val renderer = EditReelRenderer(script, photos, painter)
                        platform.encode(painter.width, painter.height, renderer.fps, renderer.frameCount, renderer::frame) { done ->
                            mutableState.update { it.copy(progress = done) }
                        }
                    }
                }
                val saved = platform.save(video, fileName)
                mutableState.value = ReelExportState(saved = saved)
            } catch (cancelled: CancellationException) {
                mutableState.value = ReelExportState()
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = ReelExportState(error = failure.toUiText(Res.string.reel_failed))
            } finally {
                video?.delete()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** The result was read: the sheet goes back to its choices. */
    fun acknowledge() {
        if (job?.isActive != true) mutableState.value = ReelExportState()
    }
}
