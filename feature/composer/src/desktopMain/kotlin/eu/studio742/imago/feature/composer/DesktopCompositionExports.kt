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
import eu.studio742.imago.core.composition.PageVideoPlan
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
        videoQuality: VideoExportQuality,
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
            val hasVideo = project.pageHasVideo(page)
            val name = "${project.name.ifBlank { untitled }}-${page + 1}"
            val file = if (hasVideo) {
                renderVideoPage(project, page, folder.resolve("$name.mp4"), videoExportSize(project.format, videoQuality), videoQuality.crf).toFile()
            } else {
                val bytes = renderer.render(project, page, prepare(project, page, Int.MAX_VALUE, strict = true))
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
        renderVideoPage(project, pageIndex, target, videoExportSize(project.format, PROXY_SIDE), PROXY_CRF, preset = "veryfast").toFile()
    }

    /**
     * A page with video: [VIDEO_EXPORT_FPS] frames per second drawn at [size], and the audio of the
     * clips that are heard mixed on top. When each clip shows and what it plays comes from the page's
     * plan, the same one the phone encodes from.
     */
    private suspend fun renderVideoPage(
        project: CompositionProject,
        page: Int,
        target: Path,
        size: Pair<Int, Int>,
        crf: Int,
        preset: String = "medium",
    ): Path {
        val plan = PageVideoPlan.of(project, page)
        val staging = Files.createTempDirectory("imago-frames-")
        try {
            val (width, height) = size
            // The still media once for the whole page, already at the video's size: decoding the
            // originals again for every frame was most of the time a page took.
            val stills = prepare(project, page, maxOf(width, height), strict = true)
            val clips = plan.clips.mapIndexedNotNull { index, clip ->
                val sources = clip.segments.filterIsInstance<PageVideoPlan.Segment.Source>()
                if (sources.isEmpty()) return@mapIndexedNotNull null
                val from = sources.minOf { it.fromMs }
                val folder = Files.createDirectory(staging.resolve("clip-$index"))
                val frames = ffmpeg.frames(originalOf(clip.video.media), from, sources.maxOf { it.toMs }, VIDEO_EXPORT_FPS, width, height, folder)
                if (frames.isEmpty()) throw LocalizedException(uiText(Res.string.composer_read_failed, clip.video.media.fileName))
                ClipFrames(clip, from, frames)
            }
            val output = Files.createDirectory(staging.resolve("page"))
            val count = ((plan.durationMs * VIDEO_EXPORT_FPS + 999) / 1000).toInt().coerceAtLeast(1)
            for (frame in 0 until count) {
                coroutineContext.ensureActive()
                val time = frame * 1000L / VIDEO_EXPORT_FPS
                val media = stills.toMutableMap()
                clips.forEach { clip -> clip.at(time)?.let { media[clip.clip.video.id] = it } }
                Files.write(output.resolve("%05d.png".format(frame)), renderer.render(project, page, media, size = size))
            }
            val arguments = mutableListOf("-nostdin", "-v", "error", "-framerate", "$VIDEO_EXPORT_FPS", "-i", output.resolve("%05d.png").toString())
            val chains = mutableListOf<String>()
            plan.clips.filter { it.audible && it.firstShownMs != null }.forEach { clip ->
                val source = originalOf(clip.video.media)
                if (!ffmpeg.hasAudio(source)) return@forEach
                arguments += listOf("-i", source.toString())
                chains += audioChain(clip, input = chains.size + 1, label = "a${chains.size}")
            }
            if (chains.isNotEmpty()) {
                val mix = if (chains.size == 1) "[a0]anull[mix]" else chains.indices.joinToString("") { "[a$it]" } + "amix=inputs=${chains.size}:normalize=0[mix]"
                arguments += listOf("-filter_complex", (chains + mix).joinToString(";"), "-map", "0:v", "-map", "[mix]", "-c:a", "aac", "-b:a", "192k")
            }
            arguments += listOf(
                "-t", (plan.durationMs / 1000.0).toString(),
                "-c:v", "libx264", "-preset", preset, "-crf", "$crf", "-pix_fmt", "yuv420p", "-r", "$VIDEO_EXPORT_FPS",
                "-movflags", "+faststart", "-y", target.toString(),
            )
            ffmpeg.run(arguments)
            return target
        } finally {
            runCatching { Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        }
    }

    /** A clip's frames on disk, and which one is on screen at each moment of the page. */
    private class ClipFrames(val clip: PageVideoPlan.Layer.Clip, private val fromMs: Long, private val frames: List<Path>) {
        private var lastIndex = -1
        private var lastBytes: ByteArray? = null

        fun at(pageMs: Long): ByteArray? {
            var cursor = 0L
            for (segment in clip.segments) {
                if (pageMs < cursor + segment.durationMs) {
                    if (segment !is PageVideoPlan.Segment.Source) return null
                    val sourceMs = segment.fromMs + (pageMs - cursor)
                    val index = ((sourceMs - fromMs) * VIDEO_EXPORT_FPS / 1000).toInt().coerceIn(0, frames.lastIndex)
                    if (index != lastIndex) {
                        lastIndex = index
                        lastBytes = Files.readAllBytes(frames[index])
                    }
                    return lastBytes
                }
                cursor += segment.durationMs
            }
            return null
        }
    }

    /**
     * One clip's sound, in page time: the trimmed part, looped as many times as the picture is, at its
     * volume, starting when the clip first shows.
     */
    private fun audioChain(clip: PageVideoPlan.Layer.Clip, input: Int, label: String): String {
        val first = clip.segments.filterIsInstance<PageVideoPlan.Segment.Source>().first()
        val loops = clip.segments.count { it is PageVideoPlan.Segment.Source } > 1
        return buildString {
            append("[$input:a]atrim=start=${first.fromMs / 1000.0}:end=${first.toMs / 1000.0},asetpts=PTS-STARTPTS,aresample=$AUDIO_RATE")
            if (loops) append(",aloop=loop=-1:size=${first.durationMs * AUDIO_RATE / 1000}")
            append(",atrim=end=${clip.shownMs / 1000.0},asetpts=PTS-STARTPTS")
            append(",volume=${clip.video.timing.volume}")
            append(",adelay=${clip.firstShownMs}:all=1[$label]")
        }
    }

    /**
     * The still images the page needs — photos and the background — with the recipes already applied.
     * The clips' frames come from [renderVideoPage].
     *
     * The preview makes do with the reduced version the server serves; the export always goes to the
     * original.
     */
    private suspend fun prepare(project: CompositionProject, page: Int, side: Int, strict: Boolean): Map<String, ByteArray> {
        val prepared = mutableMapOf<String, ByteArray>()
        // The texts' fonts, ready before drawing like the media: the renderer does not suspend.
        project.elements.filterIsInstance<CompositionElement.Text>()
            .distinctBy { it.fontFamily to it.fontWeight }
            .forEach { SkiaCompositionFonts.typeface(it.fontFamily, it.fontWeight) }

        suspend fun add(key: String, reference: MediaReference, recipe: EditRecipe? = null) {
            try {
                val source = if (strict || side > PREVIEW_SIDE) {
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
            if (element is CompositionElement.Photo) add(element.id, element.media, element.recipe.recipe)
        }
        val background = project.pages[page].backgroundOverride ?: project.background
        if (background is CompositionBackground.Photo) add(background.media.assetId, background.media, background.recipe?.recipe)
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
        const val PREVIEW_SIDE = 1440
        const val PROXY_SIDE = 720
        const val PROXY_CRF = 28
        const val AUDIO_RATE = 48_000
    }
}
