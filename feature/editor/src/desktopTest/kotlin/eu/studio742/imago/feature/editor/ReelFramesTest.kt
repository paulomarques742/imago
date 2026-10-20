package eu.studio742.imago.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.RadialMask
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.decodePixels
import eu.studio742.imago.feature.editor.resources.Res
import java.io.File
import java.util.Locale
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The frames of "Show your edit", drawn for real.
 *
 * Besides checking them, it leaves the key frames of each style as PNG in the folder the build passes
 * in `imago.reel.frames`, for a person to look at — layout is not something an assertion can judge.
 * `imago.reel.photo` swaps the small sample photo for a real one.
 */
class ReelFramesTest {
    private lateinit var previousLocale: Locale

    @Before
    fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restore() = Locale.setDefault(previousLocale)

    private val recipe = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-10-20T12:00:00Z",
        updatedAt = "2026-10-20T12:00:00Z",
    ).let { base ->
        base.copy(
            geometry = base.geometry.copy(cropRect = CropRect(0.06f, 0.04f, 0.88f, 0.9f), straighten = 1.5f),
            whiteBalance = base.whiteBalance.copy(temp = 18f, tint = 4f),
            tone = base.tone.copy(exposure = 0.35f, contrast = 22f, highlights = -40f, shadows = 30f, blacks = -12f),
            presence = base.presence.copy(clarity = 12f, vibrance = 20f),
            toneCurve = base.toneCurve.copy(rgb = listOf(CurvePoint(0, 14), CurvePoint(64, 56), CurvePoint(192, 204), CurvePoint(255, 248))),
            hsl = base.hsl.copy(blue = base.hsl.blue.copy(saturation = -25f, luminance = -15f), orange = base.hsl.orange.copy(saturation = 12f)),
            colorGrading = base.colorGrading.copy(shadows = ColorWheel(hue = 210f, saturation = 18f), highlights = ColorWheel(hue = 40f, saturation = 14f)),
            effects = base.effects.copy(vignetteAmount = -22f, grainAmount = 12f),
            masks = listOf(
                LocalMask(
                    id = "sky",
                    name = "Sky",
                    components = listOf(MaskComponent(MaskShape.RADIAL, radial = RadialMask(x = 0.5f, y = 0.3f, radiusX = 0.35f, radiusY = 0.2f))),
                    adjustments = LocalAdjustments(exposure = -0.4f, dehaze = 15f),
                ),
            ),
        )
    }

    private fun source() = runBlocking {
        val bytes = System.getProperty("imago.reel.photo")?.let { File(it).readBytes() }
            ?: Res.readBytes(recipeSamplePath(0))
        decodePixels(bytes)!!
    }

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(3f), LayoutDirection.Ltr)

    private val outputDir: File? = System.getProperty("imago.reel.frames")?.let { File(it).apply { mkdirs() } }

    private fun ImageBitmap.save(name: String) {
        val dir = outputDir ?: return
        val png = Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(dir, "$name.png").writeBytes(png)
    }

    private fun EditReelScript.startOf(segment: Int): Long = segments.take(segment).sumOf { it.durationMs }

    @Test
    fun `every style draws its key frames at the video's size`() = runBlocking {
        val source = source()
        val script = EditReelScript.fromRecipe(recipe)
        val texts = reelTexts(script, "Golden hour, Lisbon")
        val maskStep = script.steps.indexOfFirst { it.title == ReelTitle.Mask("sky") } + 1
        val curveStep = script.steps.indexOfFirst { it.title == ReelTitle.Panel(ReelPanel.CURVE) } + 1
        val toneStep = script.steps.indexOfFirst { it.title == ReelTitle.Panel(ReelPanel.LIGHT) } + 1
        val moments = mapOf(
            "1-before" to 500L,
            "2-tone-moving" to script.startOf(toneStep) + script.stepMoveMs / 2,
            "3-curve-moving" to script.startOf(curveStep) + script.stepMoveMs / 2,
            "4-mask-done" to script.startOf(maskStep) + script.stepMoveMs + 100,
            "5-compare" to script.startOf(script.segments.lastIndex - 1) + REEL_COMPARE_MS / 2,
            "2a-tone-arriving" to script.startOf(toneStep) + 150,
            "6a-closing-bars" to script.startOf(script.segments.lastIndex) + 750,
            "6b-closing-letters" to script.startOf(script.segments.lastIndex) + 1_450,
            "6-closing" to script.durationMs - 1,
        )
        for (style in ReelStyle.entries) {
            val renderer = EditReelRenderer(script, CpuReelPhotoRenderer(source, 1056), ReelFramePainter(script, style, texts, measurer))
            val frames = moments.mapValues { (name, ms) ->
                renderer.frame((ms * renderer.fps / 1000).toInt()).also {
                    assertEquals(REEL_WIDTH, it.width)
                    assertEquals(REEL_HEIGHT, it.height)
                    it.save("${style.name.lowercase()}-$name")
                }
            }
            val pixels = frames.mapValues { (_, image) -> IntArray(image.width * image.height).also { image.readPixels(it) } }
            assertNotEquals("the tone step must change the photo", pixels["1-before"]!!.toList(), pixels["2-tone-moving"]!!.toList())
            assertTrue("the closing is mostly the brand's black", pixels["6-closing"]!!.count { it == 0xFF0D0D0F.toInt() } > pixels["6-closing"]!!.size / 2)
        }
    }

    @Test
    fun `the whole video encodes with a real FFmpeg`() = runBlocking<Unit> {
        val ffmpeg = System.getProperty("imago.reel.ffmpeg")
        Assume.assumeTrue("needs -Pimago.reel.ffmpeg", ffmpeg != null)
        val script = EditReelScript.fromRecipe(recipe)
        val texts = reelTexts(script, "Golden hour, Lisbon")
        val source = source()
        for (style in ReelStyle.entries) {
            val painter = ReelFramePainter(script, style, texts, measurer)
            val renderer = EditReelRenderer(script, CpuReelPhotoRenderer(source, painter.photoMaxSide), painter)
            var progress = 0f
            val video = DesktopReelPlatform(ffmpeg!!).encode(painter.width, painter.height, renderer.fps, renderer.frameCount, renderer::frame) {
                progress = it
            }
            try {
                assertEquals(1f, progress)
                assertTrue("an MP4 of a few megabytes, not an empty file", video.length() > 200_000)
                outputDir?.let { video.copyTo(File(it, "${style.name.lowercase()}.mp4"), overwrite = true) }
            } finally {
                video.delete()
            }
        }
    }

    @Test
    fun `a frame that does not change is drawn once`() {
        val script = EditReelScript.fromRecipe(recipe)
        val texts = runBlocking { reelTexts(script, null) }
        val renderer = EditReelRenderer(
            script,
            CpuReelPhotoRenderer(source(), 400),
            ReelFramePainter(script, ReelStyle.PANEL, texts, measurer, scale = 0.25f),
        )

        assertTrue(renderer.frame(0) === renderer.frame(1))
        assertTrue(renderer.frame(renderer.frameCount - 2) === renderer.frame(renderer.frameCount - 1))
        assertTrue(renderer.frame(40) !== renderer.frame(41))
    }

    @Test
    fun `a recipe is rendered once, however many frames show it`() {
        val source = source()
        val script = EditReelScript.fromRecipe(recipe)
        val texts = runBlocking { reelTexts(script, null) }
        val renderer = EditReelRenderer(script, CpuReelPhotoRenderer(source, 1056), ReelFramePainter(script, ReelStyle.PANEL, texts, measurer))
        var renderNanos = 0L
        val total = measureNanoTime {
            for (index in 0 until renderer.frameCount) {
                val before = renderer.photoRenders
                val nanos = measureNanoTime { renderer.frame(index) }
                if (renderer.photoRenders > before) renderNanos += nanos
            }
        }
        // Each step holds still for the end of its time (less a frame, depending on where the frames
        // fall), the before is one photo and the closing settles for its last quarter: at least that
        // many frames come out without a render.
        val holdFrames = (script.stepMs - script.stepMoveMs) * REEL_FPS / 1000 - 1
        val stills = script.steps.size * holdFrames + REEL_BEFORE_MS * REEL_FPS / 1000 + REEL_CLOSING_MS * REEL_FPS / 1000 / 4
        assertTrue(renderer.photoRenders <= renderer.frameCount - stills + 2)
        println(
            "Reel: ${renderer.frameCount} frames, ${renderer.photoRenders} photo renders at ${source.width}×${source.height}, " +
                "${total / 1_000_000} ms in all, ${renderNanos / 1_000_000 / renderer.photoRenders.coerceAtLeast(1)} ms per rendered frame",
        )
    }
}
