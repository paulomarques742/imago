package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Commands use argv, never a shell. Media must be resolved to local files first. */
class FfmpegService(private val executable: String = locateFfmpeg()) {
    suspend fun frame(input: Path, atMs: Long, destination: Path) {
        require(atMs >= 0)
        run(listOf("-nostdin", "-v", "error", "-ss", (atMs / 1000.0).toString(), "-i", input.toString(),
            "-frames:v", "1", "-y", destination.toString()))
    }

    /**
     * Every frame of [fromMs]..[toMs] of [input], at [fps], into [folder] as `00000.jpg` onwards, no
     * larger than [maxWidth] × [maxHeight]. Returns them in order.
     *
     * One process per clip, and not one per frame: seeking afresh for each of the 1800 frames of a
     * minute was what made a video page take minutes, and a seek past the last frame gave no image
     * and failed the export.
     */
    suspend fun frames(input: Path, fromMs: Long, toMs: Long, fps: Int, maxWidth: Int, maxHeight: Int, folder: Path): List<Path> {
        require(fromMs >= 0 && toMs > fromMs)
        run(listOf(
            "-nostdin", "-v", "error",
            "-ss", (fromMs / 1000.0).toString(), "-t", ((toMs - fromMs) / 1000.0).toString(), "-i", input.toString(),
            "-vf", "fps=$fps,scale=w=$maxWidth:h=$maxHeight:force_original_aspect_ratio=decrease",
            "-q:v", "2", "-start_number", "0", "-y", folder.resolve("%05d.jpg").toString(),
        ))
        return Files.list(folder).use { paths -> paths.filter { it.fileName.toString().endsWith(".jpg") }.sorted().toList() }
    }

    /**
     * True when [input] has an audio stream. A clip without one — a screen recording, a timelapse —
     * used to break the whole mix, because the filter asked for an audio that was not there.
     */
    suspend fun hasAudio(input: Path): Boolean = runCatching {
        run(listOf("-nostdin", "-v", "error", "-i", input.toString(), "-map", "0:a:0", "-t", "0.05", "-f", "null", "-"))
    }.fold(onSuccess = { true }, onFailure = { if (it is CancellationException) throw it; false })

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
            discard(errors)
        }
    }

    /**
     * Windows can hold the log for a moment after FFmpeg exits; failing the export over a temporary
     * file that could not be deleted yet would throw away a video that came out fine.
     */
    private fun discard(log: Path) {
        if (runCatching { Files.deleteIfExists(log) }.isFailure) log.toFile().deleteOnExit()
    }
}

/**
 * Where FFmpeg is: `IMAGO_FFMPEG`, then the `PATH`, then the places Windows installers put it.
 *
 * Only the `PATH` used to be tried, and `winget install ffmpeg` puts it in a folder that only enters
 * the `PATH` of sessions opened after the install — the app, started from the Start menu, did not
 * find an FFmpeg that was there.
 */
internal fun locateFfmpeg(
    environment: Map<String, String> = System.getenv(),
    exists: (File) -> Boolean = File::isFile,
): String {
    environment["IMAGO_FFMPEG"]?.takeIf { it.isNotBlank() }?.let { return it }
    val windows = File.separatorChar == '\\'
    val name = if (windows) "ffmpeg.exe" else "ffmpeg"
    val path = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
    val candidates = path.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it, name) } +
        listOfNotNull(
            environment["LOCALAPPDATA"]?.let { File(it, "Microsoft\\WinGet\\Links\\ffmpeg.exe") },
            environment["ProgramData"]?.let { File(it, "chocolatey\\bin\\ffmpeg.exe") },
            environment["USERPROFILE"]?.let { File(it, "scoop\\shims\\ffmpeg.exe") },
        ).filter { windows } +
        listOf(File("/opt/homebrew/bin/ffmpeg"), File("/usr/local/bin/ffmpeg"), File("/usr/bin/ffmpeg")).filter { !windows }
    return candidates.firstOrNull(exists)?.path ?: "ffmpeg"
}
