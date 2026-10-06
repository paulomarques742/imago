package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.intersectionWithPage
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.immich.IMMICH_API_KEY_HEADER
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.decodePixels
import eu.studio742.imago.core.render.toSkiaBitmap
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * Exporting compositions on the computer.
 *
 * The pages are drawn with the same model as on the phone, in Skia; a page with video goes through
 * FFmpeg, frame by frame, with the audio of the videos that are heard. The result goes to a folder
 * named after the project, inside Pictures — the home equivalent of Android's gallery.
 */
class DesktopCompositionExports(
    private val media: CompositionMediaRepository,
    private val ffmpeg: FfmpegService = FfmpegService(),
    private val picturesFolder: Path = Path.of(System.getProperty("user.home"), "Pictures", "IMAGO"),
) : CompositionExports {
    private val renderer = CompositionRenderer()
    private val http = OkHttpClient()

    override suspend fun export(
        project: CompositionProject,
        format: StaticExportFormat,
        pageIndex: Int?,
        targetLibraryId: String?,
        onProgress: (Int, Int) -> Unit,
    ): CompositionExportOutcome = withContext(Dispatchers.IO) {
        val pages = pageIndex?.let { listOf(it.coerceIn(project.pages.indices)) } ?: project.pages.indices.toList()
        val untitled = appString(Res.string.composer_composition)
        val folder = uniqueFolder(project.name.ifBlank { untitled })
        Files.createDirectories(folder)
        val locations = mutableListOf<String>()
        val mimeTypes = mutableListOf<String>()
        var uploadFailures = 0
        pages.forEachIndexed { position, page ->
            coroutineContext.ensureActive()
            val hasVideo = project.elements.any { it is CompositionElement.Video && it.visible && it.pageIndex == page }
            val name = "${project.name.ifBlank { untitled }}-${page + 1}"
            val file = if (hasVideo) {
                renderVideoPage(project, page, folder.resolve("$name.mp4")).toFile()
            } else {
                val bytes = renderer.render(project, page, prepare(project, page, Int.MAX_VALUE, 0, strict = true))
                val target = folder.resolve("$name.${format.extension}")
                Files.write(target, if (format == StaticExportFormat.PNG) bytes else toJpeg(bytes))
                target.toFile()
            }
            locations += file.absolutePath
            mimeTypes += if (hasVideo) "video/mp4" else format.mimeType
            if (targetLibraryId != null) {
                runCatching { media.uploadComposition(targetLibraryId, file, file.name, if (hasVideo) "video/mp4" else format.mimeType, project.createdAt) }
                    .onFailure { uploadFailures++ }
            }
            onProgress(position + 1, pages.size)
        }
        CompositionExportOutcome(locations, mimeTypes, uploadFailures)
    }

    override fun cancel(projectId: String) = Unit

    override suspend fun previewPage(project: CompositionProject, pageIndex: Int): File = withContext(Dispatchers.IO) {
        val target = Files.createTempFile("imago-proxy-", ".mp4")
        Files.deleteIfExists(target)
        renderVideoPage(project, pageIndex, target, side = PROXY_SIDE).toFile()
    }

    /** A page with video: 30 frames per second drawn, and the audio mixed on top. */
    private suspend fun renderVideoPage(project: CompositionProject, page: Int, target: Path, side: Int = Int.MAX_VALUE): Path {
        val staging = Files.createTempDirectory("imago-frames-")
        try {
            val duration = project.pages[page].durationMs
            val frames = ((duration * FPS + 999) / 1000).toInt().coerceAtLeast(1)
            for (frame in 0 until frames) {
                coroutineContext.ensureActive()
                val time = frame * 1000L / FPS
                val bytes = renderer.render(project, page, prepare(project, page, side, time, strict = true), side, time)
                Files.write(staging.resolve("%05d.png".format(frame)), bytes)
            }
            val videos = project.elements.filterIsInstance<CompositionElement.Video>().filter { it.visible && it.pageIndex == page }
            val audible = videos.filter { !it.timing.muted && it.timing.volume > 0 && (videos.none { other -> other.timing.solo } || it.timing.solo) }
            val arguments = mutableListOf("-nostdin", "-v", "error", "-framerate", "$FPS", "-i", staging.resolve("%05d.png").toString())
            val filters = mutableListOf<String>()
            audible.forEachIndexed { index, video ->
                val source = originalOf(video.media)
                if (video.timing.loop) arguments += listOf("-stream_loop", "-1")
                arguments += listOf(
                    "-ss", (video.timing.trimStartMs / 1000.0).toString(),
                    "-t", ((video.timing.trimEndMs - video.timing.trimStartMs) / 1000.0).toString(),
                    "-i", source.toString(),
                )
                filters += "[${index + 1}:a]asetpts=PTS-STARTPTS,volume=${video.timing.volume},adelay=${video.timing.startOffsetMs}:all=1[a$index]"
            }
            if (audible.isNotEmpty()) {
                filters += audible.indices.joinToString("") { "[a$it]" } + "amix=inputs=${audible.size}:normalize=0[mix]"
                arguments += listOf("-filter_complex", filters.joinToString(";"), "-map", "0:v", "-map", "[mix]", "-c:a", "aac")
            }
            arguments += listOf(
                "-t", (project.pages[page].durationMs / 1000.0).toString(),
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", target.toString(),
            )
            ffmpeg.run(arguments)
            return target
        } finally {
            runCatching { Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        }
    }

    /**
     * The images the page needs, with the recipes already applied.
     *
     * The preview makes do with the reduced version the server serves; the export always goes to the
     * original.
     */
    private suspend fun prepare(project: CompositionProject, page: Int, side: Int, timeMs: Long, strict: Boolean): Map<String, ByteArray> {
        val prepared = mutableMapOf<String, ByteArray>()
        // The texts' fonts, ready before drawing like the media: the renderer does not suspend.
        project.elements.filterIsInstance<CompositionElement.Text>()
            .distinctBy { it.fontFamily to it.fontWeight }
            .forEach { SkiaCompositionFonts.typeface(it.fontFamily, it.fontWeight) }

        suspend fun add(key: String, reference: MediaReference, recipe: EditRecipe? = null, frame: Long? = null) {
            try {
                val source = if (frame != null) {
                    val video = originalOf(reference)
                    val still = Files.createTempFile("imago-frame-", ".png")
                    try { ffmpeg.frame(video, frame, still); Files.readAllBytes(still) } finally { Files.deleteIfExists(still) }
                } else if (strict || side > PREVIEW_SIDE) {
                    Files.readAllBytes(originalOf(reference))
                } else {
                    download(media.previewUrl(reference.assetId), media.apiKey(reference.assetId))
                }
                val pixels = decodePixels(source) ?: throw LocalizedException(uiText(Res.string.composer_read_failed, reference.fileName))
                val reduced = if (side == Int.MAX_VALUE) pixels else RecipePixels.reduce(pixels, side)
                val rendered = if (recipe == null) reduced else RecipePixels.render(reduced, recipe)
                prepared[key] = rendered.toSkiaBitmap().let { bitmap ->
                    Image.makeFromBitmap(bitmap).use { image ->
                        (image.encodeToData(EncodedImageFormat.PNG)
                            ?: throw LocalizedException(uiText(Res.string.composer_prepare_failed, reference.fileName))).bytes
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                // In a preview, unavailable content leaves its place empty; in an export, a page with a
                // hole is not saved.
                if (strict) throw error
            }
        }

        for (element in project.elements.filter { it.visible && (it.transform.bounds.intersectionWithPage(page) != null || page in it.repeatOnPages) }) {
            when (element) {
                is CompositionElement.Photo -> add(element.id, element.media, element.recipe.recipe)
                is CompositionElement.Video -> {
                    val elapsed = timeMs - element.timing.startOffsetMs
                    val duration = element.timing.trimEndMs - element.timing.trimStartMs
                    if (element.pageIndex == page && elapsed >= 0 && (element.timing.loop || elapsed < duration)) {
                        add(element.id, element.media, frame = element.timing.trimStartMs + if (element.timing.loop) elapsed % duration else elapsed)
                    }
                }
                else -> Unit
            }
        }
        val background = project.pages[page].backgroundOverride ?: project.background
        if (background is CompositionBackground.Photo) add(background.media.assetId, background.media)
        return prepared
    }

    /** The original on disk, downloaded once per export. */
    private suspend fun originalOf(reference: MediaReference): Path = originals.getOrPut(reference.assetId) {
        val file = Files.createTempFile("imago-media-", ".asset")
        media.downloadOriginal(reference.assetId, file.toFile())
        file.toFile().deleteOnExit()
        file
    }

    private val originals = mutableMapOf<String, Path>()

    private fun download(url: String, apiKey: String): ByteArray {
        val request = Request.Builder().url(url).apply { if (apiKey.isNotBlank()) header(IMMICH_API_KEY_HEADER, apiKey) }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw LocalizedException(uiText(Res.string.composer_server_refused_image, response.code))
            response.body.bytes()
        }
    }

    private fun toJpeg(png: ByteArray): ByteArray = Image.makeFromEncoded(png).use { image ->
        checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, 95)) { "Could not encode the JPEG." }.bytes
    }

    /** Never writes over a previous export. */
    private fun uniqueFolder(name: String): Path {
        val base = name.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        var candidate = picturesFolder.resolve(base)
        var index = 2
        while (Files.exists(candidate)) candidate = picturesFolder.resolve("$base ($index)").also { index++ }
        return candidate
    }

    private companion object {
        const val FPS = 30
        const val PREVIEW_SIDE = 1440
        const val PROXY_SIDE = 720
    }
}
