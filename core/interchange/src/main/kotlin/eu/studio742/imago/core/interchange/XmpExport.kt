package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.RAW_PROFILE_COLOR
import eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME
import eu.studio742.imago.core.model.RAW_PROFILE_NEUTRAL
import eu.studio742.imago.core.model.RAW_PROFILE_VIVID
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.ToneCurve
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

const val XMP_FILE_EXTENSION = "xmp"

/** What a recipe has and a preset file cannot hold, said before the file is written. */
enum class XmpLeftOut { MASKS, REMOVE, GEOMETRY, DEFRINGE_RANGE }

/** [leftOut] is empty when the file says everything the recipe does. */
class XmpExport(val fileName: String, val bytes: ByteArray, val leftOut: Set<XmpLeftOut>)

private const val NEUTRAL_RANGE = 50f

/** What real presets write for these two: the versions the conversion was worked out against. */
private const val WRITER_VERSION = "13.1"
private const val WRITER_PROCESS = "11.0"

private val PROFILE_NAMES = mapOf(
    RAW_PROFILE_COLOR to "Default Color",
    RAW_PROFILE_NEUTRAL to "Adobe Neutral",
    RAW_PROFILE_VIVID to "Adobe Vivid",
    RAW_PROFILE_MONOCHROME to "Default Monochrome",
)

private val IDENTITY_CURVE = ToneCurve().rgb

/** The look of this recipe as a preset. Sliders are written whole, as editors do, and the exposure to two decimals. */
fun encodeXmpPreset(saved: SavedRecipe, fallbackName: String): XmpExport {
    val parts = saved.appliedParts - PHOTO_BOUND_PARTS
    val attributes = PresetAttributes(saved.recipe, parts).build()
    val curves = if (RecipePart.TONE_CURVE in parts) curveElements(saved.recipe.toneCurve) else ""
    val text = """
        |<x:xmpmeta xmlns:x="adobe:ns:meta/">
        | <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        |  <rdf:Description rdf:about=""
        |    xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
        |${attributes.joinToString("\n") { (name, value) -> """   crs:$name="$value"""" }}>
        |${alternative("Name", saved.name)}
        |${alternative("ShortName", "")}
        |${alternative("SortName", "")}
        |${alternative("Group", saved.collection)}
        |${alternative("Description", "")}
        |$curves   </rdf:Description>
        | </rdf:RDF>
        |</x:xmpmeta>
        |
    """.trimMargin().replace("   </rdf:Description>", "  </rdf:Description>")
    return XmpExport(
        fileName = imagoFileName(saved.name, fallbackName, XMP_FILE_EXTENSION),
        bytes = text.encodeToByteArray(),
        leftOut = saved.leftOutOfXmp(parts),
    )
}

private fun SavedRecipe.leftOutOfXmp(parts: Set<RecipePart>): Set<XmpLeftOut> = buildSet {
    val bound = photoBoundParts()
    if (RecipePart.MASKS in bound) add(XmpLeftOut.MASKS)
    if (RecipePart.REMOVE in bound) add(XmpLeftOut.REMOVE)
    if (bound.any { it in RecipePart.GEOMETRY }) add(XmpLeftOut.GEOMETRY)
    val lens = recipe.lens
    if (RecipePart.DEFRINGE in parts &&
        ((lens.purpleAmount > 0f && lens.purpleRange != NEUTRAL_RANGE) || (lens.greenAmount > 0f && lens.greenRange != NEUTRAL_RANGE))
    ) {
        add(XmpLeftOut.DEFRINGE_RANGE)
    }
}

private class PresetAttributes(private val recipe: EditRecipe, private val parts: Set<RecipePart>) {
    private val out = mutableListOf<Pair<String, String>>()

    private fun put(name: String, value: String) {
        out += name to value
    }

    private inline fun whenPart(part: RecipePart, write: () -> Unit) {
        if (part in parts) write()
    }

    fun build(): List<Pair<String, String>> {
        header()
        whiteBalance()
        tone()
        presence()
        profile()
        curve()
        colorMixer()
        colorGrading()
        detail(recipe.detail)
        effects()
        calibration()
        lens()
        return out
    }

    private fun header() {
        put("PresetType", "Normal")
        put("Cluster", "")
        // From the content: the same recipe exported twice is the same preset, and does not pile up as a copy.
        put("UUID", uuidOf(recipe.toString() + parts.sorted()))
        put("SupportsAmount", "False")
        put("SupportsColor", "True")
        put("SupportsMonochrome", "True")
        put("SupportsHighDynamicRange", "True")
        put("SupportsNormalDynamicRange", "True")
        put("SupportsSceneReferred", "True")
        put("SupportsOutputReferred", "True")
        put("CameraModelRestriction", "")
        put("Copyright", "")
        put("ContactInfo", "")
        put("Version", WRITER_VERSION)
        put("ProcessVersion", WRITER_PROCESS)
        put("HasSettings", "True")
    }

    private fun whiteBalance() = whenPart(RecipePart.WHITE_BALANCE) {
        val balance = recipe.whiteBalance
        val light = balance.light
        when {
            balance.auto -> put("WhiteBalance", "Auto")
            light != null -> {
                put("WhiteBalance", "Custom")
                put("Temperature", light.kelvin.roundToInt().toString())
                put("Tint", signed(light.tint))
            }
            balance.isAsShot -> put("WhiteBalance", "As Shot")
            else -> {
                put("WhiteBalance", "Custom")
                put("IncrementalTemperature", signed(balance.temp))
                put("IncrementalTint", signed(balance.tint))
            }
        }
    }

    private fun tone() {
        val tone = recipe.tone
        whenPart(RecipePart.EXPOSURE) { put("Exposure2012", signedDecimal(tone.exposure, 2)) }
        whenPart(RecipePart.CONTRAST) { put("Contrast2012", signed(tone.contrast)) }
        whenPart(RecipePart.HIGHLIGHTS) { put("Highlights2012", signed(tone.highlights)) }
        whenPart(RecipePart.SHADOWS) { put("Shadows2012", signed(tone.shadows)) }
        whenPart(RecipePart.WHITES) { put("Whites2012", signed(tone.whites)) }
        whenPart(RecipePart.BLACKS) { put("Blacks2012", signed(tone.blacks)) }
    }

    private fun presence() {
        val presence = recipe.presence
        whenPart(RecipePart.TEXTURE) { put("Texture", signed(presence.texture)) }
        whenPart(RecipePart.CLARITY) { put("Clarity2012", signed(presence.clarity)) }
        whenPart(RecipePart.DEHAZE) { put("Dehaze", signed(presence.dehaze)) }
        whenPart(RecipePart.VIBRANCE) { put("Vibrance", signed(presence.vibrance)) }
        whenPart(RecipePart.SATURATION) { put("Saturation", signed(presence.saturation)) }
    }

    private fun profile() = whenPart(RecipePart.PROFILE) {
        put("ConvertToGrayscale", if (recipe.profile == RAW_PROFILE_MONOCHROME) "True" else "False")
        put("CameraProfile", PROFILE_NAMES.getValue(recipe.profile))
    }

    private fun curve() = whenPart(RecipePart.TONE_CURVE) {
        put("ToneCurveName2012", if (recipe.toneCurve.rgb == IDENTITY_CURVE) "Linear" else "Custom")
        // Written at zero: a preset without them would leave the photo's own parametric curve under this one.
        listOf("Shadows", "Darks", "Lights", "Highlights").forEach { put("Parametric$it", "0") }
        put("ParametricShadowSplit", "25")
        put("ParametricMidtoneSplit", "50")
        put("ParametricHighlightSplit", "75")
    }

    private fun colorMixer() = whenPart(RecipePart.COLOR_MIXER) {
        val hsl = recipe.hsl
        val bands = listOf(
            "Red" to hsl.red, "Orange" to hsl.orange, "Yellow" to hsl.yellow, "Green" to hsl.green,
            "Aqua" to hsl.aqua, "Blue" to hsl.blue, "Purple" to hsl.purple, "Magenta" to hsl.magenta,
        )
        fun write(prefix: String, value: (HslBand) -> Float) = bands.forEach { (colour, band) -> put("$prefix$colour", signed(value(band))) }
        write("HueAdjustment") { it.hue }
        write("SaturationAdjustment") { it.saturation }
        write("LuminanceAdjustment") { it.luminance }
    }

    private fun colorGrading() = whenPart(RecipePart.COLOR_GRADING) {
        val grading = recipe.colorGrading
        put("SplitToningShadowHue", whole(grading.shadows.hue))
        put("SplitToningShadowSaturation", whole(grading.shadows.saturation))
        put("SplitToningHighlightHue", whole(grading.highlights.hue))
        put("SplitToningHighlightSaturation", whole(grading.highlights.saturation))
        put("SplitToningBalance", signed(grading.balance))
        put("ColorGradeMidtoneHue", whole(grading.midtones.hue))
        put("ColorGradeMidtoneSat", whole(grading.midtones.saturation))
        put("ColorGradeShadowLum", signed(grading.shadows.luminance))
        put("ColorGradeMidtoneLum", signed(grading.midtones.luminance))
        put("ColorGradeHighlightLum", signed(grading.highlights.luminance))
        put("ColorGradeBlending", whole(grading.blending))
        put("ColorGradeGlobalHue", whole(grading.global.hue))
        put("ColorGradeGlobalSat", whole(grading.global.saturation))
        put("ColorGradeGlobalLum", signed(grading.global.luminance))
    }

    private fun detail(detail: Detail) {
        whenPart(RecipePart.SHARPENING) {
            put("Sharpness", whole(detail.sharpenAmount))
            put("SharpenRadius", signedDecimal(detail.sharpenRadius, 1))
            put("SharpenDetail", whole(detail.sharpenDetail))
            put("SharpenEdgeMasking", whole(detail.sharpenMasking))
        }
        whenPart(RecipePart.LUMINANCE_NOISE_REDUCTION) {
            put("LuminanceSmoothing", whole(detail.noiseReductionLuminance))
            put("LuminanceNoiseReductionDetail", whole(detail.noiseReductionLuminanceDetail))
            put("LuminanceNoiseReductionContrast", whole(detail.noiseReductionContrast))
        }
        whenPart(RecipePart.COLOR_NOISE_REDUCTION) {
            put("ColorNoiseReduction", whole(detail.noiseReductionColor))
            put("ColorNoiseReductionDetail", whole(detail.noiseReductionColorDetail))
            put("ColorNoiseReductionSmoothness", whole(detail.noiseReductionColorSmoothness))
        }
    }

    private fun effects() {
        val effects = recipe.effects
        whenPart(RecipePart.VIGNETTE) {
            put("PostCropVignetteAmount", signed(effects.vignetteAmount))
            put("PostCropVignetteMidpoint", whole(effects.vignetteMidpoint))
            put("PostCropVignetteFeather", whole(effects.vignetteFeather))
            put("PostCropVignetteRoundness", signed(effects.vignetteRoundness))
            put("PostCropVignetteStyle", "1")
            put("PostCropVignetteHighlightContrast", "0")
        }
        whenPart(RecipePart.GRAIN) {
            put("GrainAmount", whole(effects.grainAmount))
            put("GrainSize", whole(effects.grainSize))
            put("GrainFrequency", whole(effects.grainRoughness))
        }
    }

    private fun calibration() = whenPart(RecipePart.CALIBRATION) {
        val calibration = recipe.calibration
        put("ShadowTint", signed(calibration.shadowsTint))
        put("RedHue", signed(calibration.redHue))
        put("RedSaturation", signed(calibration.redSaturation))
        put("GreenHue", signed(calibration.greenHue))
        put("GreenSaturation", signed(calibration.greenSaturation))
        put("BlueHue", signed(calibration.blueHue))
        put("BlueSaturation", signed(calibration.blueSaturation))
    }

    private fun lens() {
        val lens = recipe.lens
        whenPart(RecipePart.LENS_VIGNETTE) {
            put("VignetteAmount", signed(lens.vignetteAmount))
            put("VignetteMidpoint", whole(lens.vignetteMidpoint))
        }
        whenPart(RecipePart.DEFRINGE) {
            put("DefringePurpleAmount", whole(lens.purpleAmount))
            put("DefringePurpleHueLo", "30")
            put("DefringePurpleHueHi", "70")
            put("DefringeGreenAmount", whole(lens.greenAmount))
            put("DefringeGreenHueLo", "40")
            put("DefringeGreenHueHi", "60")
        }
    }
}

private fun whole(value: Float) = value.roundToInt().toString()

private fun signed(value: Float): String = value.roundToInt().let { if (it > 0) "+$it" else it.toString() }

private fun signedDecimal(value: Float, decimals: Int): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", value)
    return if (value > 0f) "+$text" else text
}

private fun curveElements(curve: ToneCurve): String = listOf(
    "ToneCurvePV2012" to curve.rgb,
    "ToneCurvePV2012Red" to (curve.red ?: IDENTITY_CURVE),
    "ToneCurvePV2012Green" to (curve.green ?: IDENTITY_CURVE),
    "ToneCurvePV2012Blue" to (curve.blue ?: IDENTITY_CURVE),
).joinToString("") { (name, points) -> curveElement(name, points) }

private fun curveElement(name: String, points: List<CurvePoint>): String {
    val items = points.joinToString("\n") { "     <rdf:li>${it.x}, ${it.y}</rdf:li>" }
    return "   <crs:$name>\n    <rdf:Seq>\n$items\n    </rdf:Seq>\n   </crs:$name>\n"
}

private fun alternative(name: String, value: String): String {
    val item = if (value.isEmpty()) {
        """<rdf:li xml:lang="x-default"/>"""
    } else {
        """<rdf:li xml:lang="x-default">${escaped(value)}</rdf:li>"""
    }
    return "   <crs:$name>\n    <rdf:Alt>\n     $item\n    </rdf:Alt>\n   </crs:$name>"
}

/** A recipe's name is text from whoever wrote it, and it must not be able to end the element it sits in. */
private fun escaped(value: String): String = buildString {
    value.filterNot(Char::isISOControl).forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(char)
        }
    }
}

private fun uuidOf(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.encodeToByteArray()).joinToString("") { "%02X".format(it) }
