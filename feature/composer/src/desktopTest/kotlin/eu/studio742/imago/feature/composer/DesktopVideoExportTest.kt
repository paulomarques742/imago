package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.composition.VideoTiming
import eu.studio742.imago.core.data.CompositionMediaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

private const val NOW = "2026-10-22T10:00:00Z"
private const val BACKGROUND = 0xFF2040A0

/**
 * A page with video exported on the computer, with a real FFmpeg — the one `locateFfmpeg` finds. The
 * test is skipped where there is none.
 *
 * The clips are made by FFmpeg itself: a test pattern with a tone, and the same pattern with no audio
 * track at all, which used to break the mix.
 */
class DesktopVideoExportTest {
    private val ffmpeg = FfmpegService()

    private fun available(): Boolean = runCatching { runBlocking { ffmpeg.run(listOf("-nostdin", "-v", "error", "-version")) } }.isSuccess

    private fun clip(folder: Path, name: String, withAudio: Boolean): File {
        val target = folder.resolve(name)
        val audio = if (withAudio) listOf("-f", "lavfi", "-i", "sine=frequency=440:duration=2") else emptyList()
        runBlocking {
            ffmpeg.run(
                listOf("-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30:duration=2") + audio +
                    listOf("-c:v", "libx264", "-pix_fmt", "yuv420p") + (if (withAudio) listOf("-c:a", "aac", "-shortest") else emptyList()) +
                    listOf("-y", target.toString()),
            )
        }
        return target.toFile()
    }

    private class Files_(private val byAsset: Map<String, File>) : CompositionMediaRepository {
        override fun media(query: String?) = error("not used")
        override fun thumbnailUrl(assetId: String) = assetId
        override fun previewUrl(assetId: String) = assetId
        override fun videoPlaybackUrl(assetId: String) = assetId
        override fun apiKey(assetId: String) = ""
        override suspend fun downloadOriginal(assetId: String, destination: File) {
            byAsset.getValue(assetId).copyTo(destination, overwrite = true)
        }
        override suspend fun uploadComposition(targetLibraryId: String, file: File, fileName: String, mimeType: String, createdAt: String) =
            error("not used")
    }

    private fun video(id: String, assetId: String, timing: VideoTiming, bounds: NormalizedRect, zIndex: Int) = CompositionElement.Video(
        id = id,
        transform = ElementTransform(bounds),
        zIndex = zIndex,
        pageIndex = 0,
        media = MediaReference(assetId = assetId, checksum = "", fileName = "$assetId.mp4", durationMs = 2_000),
        timing = timing,
    )

    /** The frame at [atMs] of [video], read back through FFmpeg. */
    private fun frameAt(video: File, atMs: Long, folder: Path) = folder.resolve("frame-$atMs.png").let { still ->
        runBlocking { ffmpeg.frame(video.toPath(), atMs, still) }
        ImageIO.read(still.toFile())
    }

    private fun isBackground(rgb: Int): Boolean {
        val expected = BACKGROUND.toInt()
        return (0..2).all { shift -> kotlin.math.abs(((rgb shr (shift * 8)) and 0xFF) - ((expected shr (shift * 8)) and 0xFF)) < 24 }
    }

    /** How much of a half of the frame is the page's background, on a grid of points inside it. */
    private fun java.awt.image.BufferedImage.background(left: Boolean): Float {
        val from = if (left) 0 else width / 2
        val points = (1..6).flatMap { i -> (1..6).map { j -> from + i * (width / 2) / 7 to j * height / 7 } }
        return points.count { (x, y) -> isBackground(getRGB(x, y)) }.toFloat() / points.size
    }

    @Test
    fun `a clip that starts late shows the page under it until it starts, and the video has sound`() = runBlocking {
        assumeTrue("no FFmpeg on this machine", available())
        val work = Files.createTempDirectory("imago-video-export")
        val withSound = clip(work, "tone.mp4", withAudio = true)
        val silent = clip(work, "silent.mp4", withAudio = false)
        // An odd page: the video has to come out even on both sides.
        val project = CompositionProject(
            id = "p", name = "Clip", format = PageFormat.custom(331, 241),
            pages = listOf(CompositionPage("page", 0, durationMs = 3_000)),
            background = CompositionBackground.Solid(BACKGROUND),
            elements = listOf(
                // Left half: starts at 1 s, plays one second, then the page shows again.
                video("late", "tone", VideoTiming(trimEndMs = 1_000, startOffsetMs = 1_000), NormalizedRect(0f, 0f, .5f, 1f), zIndex = 0),
                // Right half: no audio track, looping from the start.
                video("loop", "silent", VideoTiming(trimEndMs = 700, loop = true), NormalizedRect(.5f, 0f, .5f, 1f), zIndex = 1),
            ),
            createdAt = NOW, updatedAt = NOW,
        )
        val exports = DesktopCompositionExports(Files_(mapOf("tone" to withSound, "silent" to silent)), ffmpeg, picturesFolder = work.resolve("out"))
        val result = exports.export(project, StaticExportFormat.JPEG, pageIndex = null, targetLibraryId = null, VideoExportQuality.STANDARD) { _, _ -> }
        assertEquals(listOf("video/mp4"), result.mimeTypes)
        val exported = File(result.locations.single())
        assertTrue(exported.length() > 0)

        val before = frameAt(exported, 500, work)
        assertEquals("even sides", 330 to 240, before.width to before.height)
        assertTrue("before 1 s the left half is the background", before.background(left = true) > .95f)
        assertTrue("the looping clip is on from the start", before.background(left = false) < .3f)
        val during = frameAt(exported, 1_500, work)
        assertTrue("at 1.5 s the late clip is on screen", during.background(left = true) < .3f)
        val after = frameAt(exported, 2_600, work)
        assertTrue("after its second the late clip is gone", after.background(left = true) > .95f)
        assertTrue("the loop is still playing", after.background(left = false) < .3f)

        // The tone is in the file: an audio stream FFmpeg can read.
        assertTrue("the video should carry the clip's sound", ffmpeg.hasAudio(exported.toPath()))
    }

    @Test
    fun `ffmpeg is found where Windows installers put it, after IMAGO_FFMPEG and the PATH`() {
        val winget = File("D:\\Profiles\\someone\\AppData\\Local", "Microsoft\\WinGet\\Links\\ffmpeg.exe")
        val onPath = File("C:\\tools\\ffmpeg\\bin", if (File.separatorChar == '\\') "ffmpeg.exe" else "ffmpeg")
        val environment = mapOf("LOCALAPPDATA" to "D:\\Profiles\\someone\\AppData\\Local", "Path" to "C:\\tools\\ffmpeg\\bin")
        assertEquals("C:\\custom\\ffmpeg.exe", locateFfmpeg(environment + ("IMAGO_FFMPEG" to "C:\\custom\\ffmpeg.exe")) { false })
        assertEquals(onPath.path, locateFfmpeg(environment) { it == onPath || it == winget })
        if (File.separatorChar == '\\') assertEquals(winget.path, locateFfmpeg(environment) { it == winget })
        assertEquals("ffmpeg", locateFfmpeg(environment) { false })
    }
}
