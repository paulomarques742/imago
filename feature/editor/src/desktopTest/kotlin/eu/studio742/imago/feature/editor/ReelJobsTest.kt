package eu.studio742.imago.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.studio742.imago.core.model.AUTOMATIC_UPRIGHT_MODES
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.MASK_AMOUNT_NEUTRAL
import eu.studio742.imago.core.render.BrushRasters
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.decodePixels
import eu.studio742.imago.core.render.detectLineSegments
import eu.studio742.imago.core.render.frameGeometry
import eu.studio742.imago.core.render.solveAutoUpright
import eu.studio742.imago.core.render.toImageBitmap
import eu.studio742.imago.core.render.toRenderParameters
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * "Show your edit" videos made in batch, with the same script, painter and encoder as the app.
 *
 * `-Pimago.reel.jobs=<folder>` points at a folder of `*.json` jobs, each one a photo, a recipe, a
 * style and an optional title:
 *
 * ```json
 * { "photo": "lighthouse.jpg", "style": "PANEL", "title": "Every frame here is the real edit.",
 *   "recipe": { "tone": { "exposure": 0.3 } } }
 * ```
 *
 * The photo path is relative to the job. The recipe is an [EditRecipe] whose bookkeeping fields may be
 * left out. Next to each job come `<job>.mp4` and its key frames as PNG, to look at before posting.
 * Needs `-Pimago.reel.ffmpeg` too. `-Pimago.reel.only=<job>` makes a single one. A job with
 * `"maskLayers": true` also gets each of its masks alone, as `<job>-mask-<n>.png`.
 */
class ReelJobsTest {
    private lateinit var previousLocale: Locale

    @Before
    fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restore() = Locale.setDefault(previousLocale)

    private companion object {
        const val APPROVAL_SIDE = 1600

        /** The side the editor reduces a photo to before looking for its lines. */
        const val UPRIGHT_DETECTION_SIDE = 1000

        /** A dark flat grey and a strong exposure: room for many levels between no mask and full. */
        const val PROBE_GREY = 0xFF303030.toInt()
        const val PROBE_EV = 4f
        const val PROBE_STEPS = 400
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(3f), LayoutDirection.Ltr)

    @Test
    fun `every job in the folder becomes a video`() = runBlocking<Unit> {
        val folder = System.getProperty("imago.reel.jobs")?.let(::File)
        val ffmpeg = System.getProperty("imago.reel.ffmpeg")
        Assume.assumeTrue("needs -Pimago.reel.jobs and -Pimago.reel.ffmpeg", folder != null && ffmpeg != null)
        val only = System.getProperty("imago.reel.only")
        val jobs = folder!!.listFiles { file -> file.extension == "json" }!!
            .filter { only == null || it.nameWithoutExtension == only }
            .sortedBy { it.name }
        assertTrue("no jobs in $folder", jobs.isNotEmpty())
        for (job in jobs) make(job, ffmpeg!!)
    }

    private suspend fun make(job: File, ffmpeg: String) {
        val spec = json.parseToJsonElement(job.readText()).jsonObject
        val photo = job.resolveSibling(spec.getValue("photo").jsonPrimitive.content)
        val style = ReelStyle.valueOf(spec["style"]?.jsonPrimitive?.content ?: "PANEL")
        val title = spec["title"]?.jsonPrimitive?.content
        val source = decodePixels(photo.readBytes()) ?: error("${photo.name} is not an image")
        val recipe = recipeOf(spec["recipe"]?.jsonObject ?: JsonObject(emptyMap()), photo.name).withAutomaticUpright(source)

        val script = EditReelScript.fromRecipe(recipe)
        val texts = reelTexts(script, title)
        val painter = ReelFramePainter(script, style, texts, measurer)
        val renderer = EditReelRenderer(script, CpuReelPhotoRenderer(source, painter.photoMaxSide), painter)

        val name = job.nameWithoutExtension
        val compareStart = script.segments.dropLast(2).sumOf { it.durationMs }
        val stills = mapOf(
            "1-before" to 500L,
            "2-edited" to compareStart - 1,
            "3-compare" to compareStart + REEL_COMPARE_MS / 2,
        )
        for ((still, ms) in stills) renderer.frame((ms * renderer.fps / 1000).toInt()).save(job.resolveSibling("$name-$still.png"))
        // The photo alone, larger and without the overlay: what the edit is approved on. The before is
        // the photo as it came in, not the video's compare frame, which already has the final framing.
        CpuReelPhotoRenderer(source, APPROVAL_SIDE).use { photos ->
            photos.render(recipeOf(JsonObject(emptyMap()), photo.name)).save(job.resolveSibling("$name-photo-before.png"))
            photos.render(recipe).save(job.resolveSibling("$name-photo-after.png"))
        }
        if (spec["maskLayers"]?.jsonPrimitive?.booleanOrNull == true) saveMaskLayers(source, recipe, job)

        val video = DesktopReelPlatform(ffmpeg).encode(painter.width, painter.height, renderer.fps, renderer.frameCount, renderer::frame) {}
        try {
            video.copyTo(job.resolveSibling("$name.mp4"), overwrite = true)
        } finally {
            video.delete()
        }
        println("$name: ${script.steps.size} steps, ${script.durationMs} ms")
    }

    /** The job's recipe, with the fields that only matter to the library filled in. */
    private fun recipeOf(partial: JsonObject, photoName: String): EditRecipe {
        val filled = JsonObject(
            mapOf(
                "assetId" to JsonPrimitive(photoName),
                "originalChecksum" to JsonPrimitive(photoName),
                "createdAt" to JsonPrimitive("2026-10-20T12:00:00Z"),
                "updatedAt" to JsonPrimitive("2026-10-20T12:00:00Z"),
            ) + partial,
        )
        return json.decodeFromJsonElement(EditRecipe.serializer(), filled)
    }

    /**
     * A job that asks for an automatic Upright mode gets the angles the app would find: the lines
     * detected on a copy reduced as the editor reduces it, and solved by the same function. The video
     * then shows what one tap does, not angles typed in by hand.
     */
    private fun EditRecipe.withAutomaticUpright(source: PixelBuffer): EditRecipe {
        val mode = geometry.perspective.upright
        if (mode !in AUTOMATIC_UPRIGHT_MODES) return this
        val lines = detectLineSegments(RecipePixels.reduce(source, UPRIGHT_DETECTION_SIDE))
        val angles = solveAutoUpright(toRenderParameters().frameGeometry(source.width, source.height), lines, mode)
            ?: error("Upright $mode finds no lines in $assetId")
        println("$assetId: Upright $mode, roll ${angles.roll}°, pitch ${angles.pitch}°, yaw ${angles.yaw}°")
        return copy(
            geometry = geometry.copy(
                perspective = geometry.perspective.copy(uprightRoll = angles.roll, uprightPitch = angles.pitch, uprightYaw = angles.yaw),
            ),
        )
    }

    /**
     * Each mask on its own, white where it applies: read back through the renderer rather than drawn
     * again, so it is the mask the edit used. The mask goes alone over a flat grey with one strong
     * exposure for adjustments, and each grey that comes out is matched to the weight that gives it,
     * from the same exposure applied globally in steps. Brushes are painted over the real photo, so
     * Auto Mask still follows its edges.
     */
    private fun saveMaskLayers(source: PixelBuffer, recipe: EditRecipe, job: File) {
        val photo = RecipePixels.reduce(source, APPROVAL_SIDE)
        val blank = recipeOf(JsonObject(emptyMap()), recipe.assetId)
        fun grey(width: Int, height: Int) = PixelBuffer(width, height).apply { pixels.fill(PROBE_GREY) }
        val ramp = IntArray(PROBE_STEPS + 1) { step ->
            val probe = blank.copy(tone = blank.tone.copy(exposure = PROBE_EV * step / PROBE_STEPS))
            RecipePixels.render(grey(4, 4), probe).pixels[0] and 255
        }
        recipe.masks.forEachIndexed { index, mask ->
            val alone = blank.copy(
                masks = listOf(mask.copy(enabled = true, adjustments = LocalAdjustments(exposure = PROBE_EV), amount = MASK_AMOUNT_NEUTRAL)),
            ).toRenderParameters()
            val layer = RecipePixels.render(grey(photo.width, photo.height), alone, 1f, BrushRasters.of(alone, photo))
            for (i in layer.pixels.indices) {
                val level = (weightOf(layer.pixels[i] and 255, ramp) * 255f).roundToInt()
                layer.pixels[i] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            }
            layer.toImageBitmap().save(job.resolveSibling("${job.nameWithoutExtension}-mask-${index + 1}.png"))
            println("${job.nameWithoutExtension}: mask ${index + 1}, ${mask.name}")
        }
    }

    /** Where [level] falls on [ramp], the greys the exposure gives in steps, as a weight from 0 to 1. */
    private fun weightOf(level: Int, ramp: IntArray): Float {
        val above = ramp.indexOfFirst { it >= level }
        if (above < 0) return 1f
        if (above == 0) return 0f
        val low = ramp[above - 1]
        val high = ramp[above]
        val within = if (high == low) 0f else (level - low).toFloat() / (high - low)
        return ((above - 1 + within) / PROBE_STEPS).coerceIn(0f, 1f)
    }

    private fun ImageBitmap.save(file: File) {
        file.writeBytes(Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
