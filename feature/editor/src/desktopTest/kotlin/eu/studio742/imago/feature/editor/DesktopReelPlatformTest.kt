package eu.studio742.imago.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.reel_ffmpeg_unavailable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DesktopReelPlatformTest {
    @Test
    fun `frames go in raw and come out as H264 a phone plays`() {
        val arguments = reelFfmpegArguments(1080, 1920, 30, "out.mp4")

        fun after(flag: String) = arguments[arguments.indexOf(flag) + 1]
        assertEquals("rawvideo", after("-f"))
        assertEquals("1080x1920", after("-s"))
        assertEquals("30", after("-r"))
        assertEquals("-", after("-i"))
        assertEquals("libx264", after("-c:v"))
        assertEquals("yuv420p", arguments[arguments.lastIndexOf("-pix_fmt") + 1])
        assertTrue(after("-vf").contains("out_color_matrix=bt709"))
        assertEquals("out.mp4", arguments.last())
    }

    @Test
    fun `a missing FFmpeg says how to point at one`() = runBlocking<Unit> {
        val platform = DesktopReelPlatform(executable = "imago-no-such-ffmpeg-executable")
        try {
            platform.encode(2, 2, 30, 1, { ImageBitmap(2, 2) }, {})
            fail("encoding without FFmpeg must fail")
        } catch (failure: LocalizedException) {
            assertEquals(UiText.Resource(Res.string.reel_ffmpeg_unavailable), failure.text)
        }
    }

    @Test
    fun `the video is named after the photo and never like an exported photo`() {
        assertEquals("DSCF7385_ShowYourEdit.mp4", reelFileName("DSCF7385.JPG"))
        assertEquals("Ferias_em_agosto_ShowYourEdit.mp4", reelFileName("Ferias em agosto.heic"))
        assertEquals("IMAGO_ShowYourEdit.mp4", reelFileName(""))
    }
}
