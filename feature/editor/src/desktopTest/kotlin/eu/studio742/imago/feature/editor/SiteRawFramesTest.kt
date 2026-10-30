package eu.studio742.imago.feature.editor

import androidx.compose.ui.graphics.asSkiaBitmap
import eu.studio742.imago.core.model.CURRENT_RAW_RENDERING
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.developedAsRaw
import eu.studio742.imago.core.render.CURRENT_RAW_HIGHLIGHT
import eu.studio742.imago.core.render.CameraExposureMatch
import eu.studio742.imago.core.render.Histogram
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.decodeRawEditorPreview
import eu.studio742.imago.core.render.toImageBitmap
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume
import org.junit.Test

/**
 * The website's RAW section: one RAW developed by the app's engine in a few states, so the page moves
 * between real renders instead of imitating them. `-Pimago.site.raw=<job.json>`, or a folder of them:
 *
 * ```json
 * { "raw": "DSCF1351.RAF", "side": 1600, "base": { "tone": { "shadows": 20 } },
 *   "frames": { "highlights-0": {}, "highlights-100": { "tone": { "highlights": -100 } },
 *               "shade": { "light": { "kelvin": 7500, "tint": 0 }, "clip": true } } }
 * ```
 *
 * Each frame is the base recipe with its own fields laid over it, opened as the editor opens a RAW for
 * the first time: the preview the editor develops, the exposure matched to the camera's JPEG, the
 * current rendering. `light` is a white balance in kelvin, turned into the sliders through the
 * camera's colour as the editor's presets are. Next to the job come `<frame>.png`, with `"clip": true`
 * also `<frame>-clip.png` (the editor's highlight clipping warning alone, red where a channel reaches
 * [Histogram.HIGH]), and `<job>-frames.json` with each frame's light in kelvin and its clipped share.
 * `"crop": [x, y, w, h]`, in fractions of the photo, keeps only that part of every frame: with a large
 * `side`, a loupe at the photo's own pixels.
 */
class SiteRawFramesTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `the job's RAW becomes the site's frames`() = runBlocking<Unit> {
        val job = System.getProperty("imago.site.raw")?.let(::File)
        Assume.assumeTrue("needs -Pimago.site.raw", job != null)
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        // A folder makes every job in it, to compare a few photos at once.
        val jobs = if (job!!.isDirectory) job.listFiles { file -> file.extension == "json" && !file.name.endsWith("-frames.json") }!!.sorted() else listOf(job)
        try { jobs.forEach { make(it) } } finally { Locale.setDefault(previous) }
    }

    private suspend fun make(job: File) {
        val spec = json.parseToJsonElement(job.readText()).jsonObject
        val raw = job.resolveSibling(spec.getValue("raw").jsonPrimitive.content)
        val side = spec["side"]?.jsonPrimitive?.float?.toInt() ?: 1600
        val preview = decodeRawEditorPreview(raw, maxSide = side, baselineExposure = true, highlight = CURRENT_RAW_HIGHLIGHT)
            ?: error("${raw.name} is not a RAW LibRaw reads")
        val light = preview.light ?: error("${raw.name} has no camera colour, so no kelvin")
        val matched = CameraExposureMatch.of(raw, preview.pixels)
        val base = spec["base"]?.jsonObject ?: JsonObject(emptyMap())
        // A part of the photo, in fractions of it (x, y, width, height): a loupe at the photo's own pixels.
        val crop = spec["crop"]?.jsonArray?.map { it.jsonPrimitive.float }

        val manifest = buildJsonObject {
            put("raw", raw.name)
            put("width", preview.pixels.width)
            put("height", preview.pixels.height)
            put("asShotKelvin", light.asShot.kelvin.roundToInt())
            putJsonObject("frames") {
                for ((name, element) in spec.getValue("frames").jsonObject) {
                    val frame = element.jsonObject
                    var recipe = recipeOf(merge(base, JsonObject(frame - "light" - "clip")), raw.name)
                        .developedAsRaw(CURRENT_RAW_RENDERING, matched)
                    frame["light"]?.jsonObject?.let { wanted ->
                        val (temp, tint) = light.sliders(wanted.getValue("kelvin").jsonPrimitive.float, wanted["tint"]?.jsonPrimitive?.float ?: 0f)
                        recipe = recipe.copy(whiteBalance = recipe.whiteBalance.copy(temp = temp, tint = tint))
                    }
                    val rendered = RecipePixels.render(preview.pixels, recipe).let { whole -> crop?.let { whole.cropped(it) } ?: whole }
                    rendered.save(job.resolveSibling("$name.png"))
                    val clipped = clippingWarning(rendered)
                    if (frame["clip"]?.jsonPrimitive?.booleanOrNull == true) clipped.first.save(job.resolveSibling("$name-clip.png"))
                    val kelvin = light.kelvinTint(recipe.whiteBalance.temp, recipe.whiteBalance.tint).kelvin.roundToInt()
                    putJsonObject(name) {
                        put("kelvin", kelvin)
                        put("temp", recipe.whiteBalance.temp)
                        put("highlightsClipped", clipped.second)
                    }
                    println("$name: $kelvin K, ${"%.2f".format(clipped.second * 100)} % clipped")
                }
            }
        }
        job.resolveSibling("${job.nameWithoutExtension}-frames.json").writeText(json.encodeToString(JsonObject.serializer(), manifest))
    }

    /** The editor's warning on its own: its red where a channel is at white, transparent elsewhere. */
    private fun clippingWarning(image: PixelBuffer): Pair<PixelBuffer, Float> {
        val warning = PixelBuffer(image.width, image.height)
        var clipped = 0
        for (i in image.pixels.indices) {
            val p = image.pixels[i]
            if ((p shr 16 and 255) >= Histogram.HIGH || (p shr 8 and 255) >= Histogram.HIGH || (p and 255) >= Histogram.HIGH) {
                // vec3(0.95, 0.15, 0.15) in the shader.
                warning.pixels[i] = (0xFF shl 24) or (242 shl 16) or (38 shl 8) or 38
                clipped++
            }
        }
        return warning to clipped.toFloat() / image.pixels.size
    }

    private fun PixelBuffer.cropped(box: List<Float>): PixelBuffer {
        val left = (box[0] * width).toInt()
        val top = (box[1] * height).toInt()
        val part = PixelBuffer((box[2] * width).toInt(), (box[3] * height).toInt())
        for (y in 0 until part.height) System.arraycopy(pixels, (top + y) * width + left, part.pixels, y * part.width, part.width)
        return part
    }

    /** [over] laid on [under], object by object. */
    private fun merge(under: JsonObject, over: JsonObject): JsonObject = JsonObject(
        (under.keys + over.keys).associateWith { key ->
            val a = under[key]
            val b = over[key]
            if (a is JsonObject && b is JsonObject) merge(a, b) else (b ?: a)!!
        },
    )

    /** The recipe with the fields that only matter to the library filled in. */
    private fun recipeOf(partial: JsonObject, name: String): EditRecipe {
        val filled = mapOf<String, JsonElement>(
            "assetId" to JsonPrimitive(name),
            "originalChecksum" to JsonPrimitive(name),
            "createdAt" to JsonPrimitive("2026-10-29T12:00:00Z"),
            "updatedAt" to JsonPrimitive("2026-10-29T12:00:00Z"),
        ) + partial
        return json.decodeFromJsonElement(EditRecipe.serializer(), JsonObject(filled))
    }

    private fun PixelBuffer.save(file: File) {
        file.writeBytes(Image.makeFromBitmap(toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
