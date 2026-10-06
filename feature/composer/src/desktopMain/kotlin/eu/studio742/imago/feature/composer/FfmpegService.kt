package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Commands use argv, never a shell. Media must be resolved to local files first. */
class FfmpegService(private val executable: String = System.getenv("IMAGO_FFMPEG") ?: "ffmpeg") {
    suspend fun frame(input: Path, atMs: Long, destination: Path) {
        require(atMs >= 0)
        run(listOf("-nostdin", "-v", "error", "-ss", (atMs / 1000.0).toString(), "-i", input.toString(),
            "-frames:v", "1", "-y", destination.toString()))
    }
    suspend fun run(arguments: List<String>, timeoutMinutes: Long = 30) = withContext(Dispatchers.IO) {
        val errors = Files.createTempFile("imago-ffmpeg-", ".log")
        val process = try { ProcessBuilder(listOf(executable) + arguments).redirectError(errors.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
        catch (error: Exception) { Files.deleteIfExists(errors); throw LocalizedException(uiText(Res.string.composer_ffmpeg_unavailable), error) }
        try {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)
            while (process.isAlive) {
                ensureActive()
                if (System.nanoTime() >= deadline) throw LocalizedException(uiText(Res.string.composer_video_timeout))
                delay(100)
            }
            // Do not surface raw FFmpeg output: it may include local paths and URLs.
            if (process.exitValue() != 0) throw LocalizedException(uiText(Res.string.composer_video_failed, process.exitValue()))
        } finally {
            if (process.isAlive) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
            Files.deleteIfExists(errors)
        }
    }
}
