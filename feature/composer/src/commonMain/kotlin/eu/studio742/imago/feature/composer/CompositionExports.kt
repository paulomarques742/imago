package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormat
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The format of an exported page without video. Pages with video always come out as MP4. */
enum class StaticExportFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
}

/**
 * How a page with video is encoded: the longest side it may have and how many bits each pixel gets.
 *
 * [MAXIMUM] is the page's own size, up to 4K; the other two are the usual 720p and 1080p, and never
 * larger than the page. On Android the encoder may still step down if the phone cannot encode the
 * size asked for.
 */
enum class VideoExportQuality(val longestSide: Int, private val bitsPerPixel: Float, val crf: Int) {
    STANDARD(1280, .12f, 23),
    HIGH(1920, .16f, 19),
    MAXIMUM(3840, .26f, 16),
    ;

    /** The H.264 bitrate for [width] × [height] at [fps], within what phones' encoders accept. */
    fun bitrate(width: Int, height: Int, fps: Int = VIDEO_EXPORT_FPS): Int =
        (width.toLong() * height * fps * bitsPerPixel).toLong().coerceIn(1_500_000L, 60_000_000L).toInt()

    companion object {
        val Default = HIGH
    }
}

/** The frame rate the computer renders at, and the one the bitrates are sized for. */
const val VIDEO_EXPORT_FPS = 30

/**
 * The size a page is encoded at: never larger than [longestSide] nor than the page, and always even.
 *
 * H.264 counts in macroblocks and an odd side — which a reduced 1500 × 1000 easily gives — makes the
 * encoder refuse the job or round on its own, stretching the image.
 */
fun videoExportSize(format: PageFormat, longestSide: Int): Pair<Int, Int> {
    val scale = min(1f, longestSide.toFloat() / max(format.pixelWidth, format.pixelHeight))
    fun even(value: Float) = max(2, (value.roundToInt() / 2) * 2)
    return even(format.pixelWidth * scale) to even(format.pixelHeight * scale)
}

fun videoExportSize(format: PageFormat, quality: VideoExportQuality): Pair<Int, Int> =
    videoExportSize(format, quality.longestSide)

/** True when [pageIndex] comes out as an MP4 and not as an image. */
fun CompositionProject.pageHasVideo(pageIndex: Int): Boolean =
    elements.any { it is CompositionElement.Video && it.visible && it.pageIndex == pageIndex }

/**
 * What was saved.
 *
 * @param locations where each page went — the gallery URI on Android, the path on the computer.
 */
data class CompositionExportOutcome(
    val locations: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val immichUploadFailures: Int = 0,
)

/**
 * A composition's export, on each platform.
 *
 * On Android it runs in a background job, with a notification, to survive leaving the app; on desktop
 * it runs in the app itself, which nobody interrupts. Both draw the pages with the same model, and
 * both return the same result.
 */
interface CompositionExports {
    suspend fun export(
        project: CompositionProject,
        format: StaticExportFormat,
        pageIndex: Int?,
        targetLibraryId: String?,
        videoQuality: VideoExportQuality = VideoExportQuality.Default,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): CompositionExportOutcome

    fun cancel(projectId: String)

    /** A preview of a page with video, in a temporary file. */
    suspend fun previewPage(project: CompositionProject, pageIndex: Int): File
}
