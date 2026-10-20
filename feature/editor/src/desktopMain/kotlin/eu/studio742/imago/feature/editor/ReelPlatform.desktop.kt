package eu.studio742.imago.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import coil3.Bitmap
import eu.studio742.imago.core.render.toPixelBuffer
import eu.studio742.imago.core.designsystem.NativeDialogs
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.reel_failed
import eu.studio742.imago.feature.editor.resources.reel_ffmpeg_unavailable
import eu.studio742.imago.feature.editor.resources.reel_saved_file
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
actual fun rememberReelPlatform(): ReelPlatform = remember { DesktopReelPlatform() }

/** The video on the computer: FFmpeg, as the composer uses it, and a file the person chooses. */
internal class DesktopReelPlatform(
    private val executable: String = System.getenv("IMAGO_FFMPEG") ?: "ffmpeg",
) : ReelPlatform {
    override fun frameScale(): Float = 1f

    /** The computer draws with the CPU render, as its editor does. */
    override fun photoRenderer(photo: Bitmap, maxSide: Int): ReelPhotoRenderer =
        CpuReelPhotoRenderer(photo.toPixelBuffer(), maxSide)

    override suspend fun encode(
        width: Int,
        height: Int,
        fps: Int,
        frameCount: Int,
        frame: (Int) -> ImageBitmap,
        onProgress: (Float) -> Unit,
    ): File {
        val output = File.createTempFile("imago-reel-", ".mp4")
        val errors = File.createTempFile("imago-ffmpeg-", ".log")
        val process = try {
            ProcessBuilder(listOf(executable) + reelFfmpegArguments(width, height, fps, output.path))
                .redirectError(errors)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (failure: IOException) {
            output.delete()
            errors.delete()
            throw LocalizedException(uiText(Res.string.reel_ffmpeg_unavailable), failure)
        }
        try {
            val argb = IntArray(width * height)
            // Little-endian ARGB words are B, G, R, A in memory: FFmpeg's bgra, with no conversion here.
            val bytes = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
            BufferedOutputStream(process.outputStream, 1 shl 20).use { stdin ->
                for (index in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    frame(index).readPixels(argb)
                    bytes.clear()
                    bytes.asIntBuffer().put(argb)
                    stdin.write(bytes.array())
                    onProgress((index + 1f) / frameCount)
                }
            }
            val finished = withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.MINUTES) }
            // FFmpeg's own output is not shown: it may carry local paths.
            if (!finished || process.exitValue() != 0) throw LocalizedException(uiText(Res.string.reel_failed))
            return output
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            if (process.isAlive) process.destroyForcibly()
            errors.delete()
        }
    }

    override suspend fun save(video: File, fileName: String): SavedReel? {
        val destination = NativeDialogs.saveFile(fileName, listOf(NativeDialogs.Filter("MP4", listOf("mp4")))) ?: return null
        withContext(Dispatchers.IO) { Files.copy(video.toPath(), destination, StandardCopyOption.REPLACE_EXISTING) }
        return SavedReel(destination.toString(), uiText(Res.string.reel_saved_file))
    }

    override val share: ((SavedReel) -> Unit)? = null
}

/**
 * Raw frames on standard input, H.264 out. yuv420p because it is what every phone and every social
 * network plays; faststart so the video starts before it has fully downloaded.
 *
 * The BT.709 conversion is asked of the scaler and the tags only say so: tagging alone would leave
 * FFmpeg converting with its default BT.601 matrix, and players would read the colours shifted.
 */
internal fun reelFfmpegArguments(width: Int, height: Int, fps: Int, output: String): List<String> = listOf(
    "-hide_banner",
    "-v", "error",
    "-f", "rawvideo",
    "-pix_fmt", "bgra",
    "-s", "${width}x$height",
    "-r", fps.toString(),
    "-i", "-",
    "-vf", "scale=out_color_matrix=bt709:out_range=tv",
    "-c:v", "libx264",
    "-preset", "medium",
    "-crf", "18",
    "-pix_fmt", "yuv420p",
    "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
    "-movflags", "+faststart",
    "-y", output,
)
