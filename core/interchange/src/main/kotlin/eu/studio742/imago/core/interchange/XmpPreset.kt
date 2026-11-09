package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.Calibration
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.Effects
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Hsl
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.KelvinTint
import eu.studio742.imago.core.model.LensCorrection
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.RAW_PROFILE_COLOR
import eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME
import eu.studio742.imago.core.model.RAW_PROFILE_NEUTRAL
import eu.studio742.imago.core.model.RAW_PROFILE_VIVID
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.WhiteBalance
import eu.studio742.imago.core.model.withPartsFrom
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/** A setting of a preset that has no place in the recipe, by name in the app's own words. */
enum class ImportedSetting {
    PARAMETRIC_CURVE, TONE_CURVE, CREATIVE_PROFILE, CAMERA_PROFILE, MASKS, GEOMETRY, GRAY_MIXER, LENS_PROFILE,
    CHROMATIC_ABERRATION, DEFRINGE_HUE, VIGNETTE_STYLE, AUTO_TONE, ISO_ADAPTIVE, POINT_COLOR, LENS_BLUR,
    OLD_TONE_CONTROLS, WHITE_BALANCE_PRESET, OTHER,
}

enum class ImportGapReason { NOT_IN_APP, NOT_IMPORTED, OLD_PROCESS, APPROXIMATED }

/** What did not arrive as the file had it. Closed values only: a report never repeats what a stranger's file says. */
sealed interface ImportGap {
    data class Left(val setting: ImportedSetting, val reason: ImportGapReason) : ImportGap

    /** Brought to the slider's range; [original] is what the file asked for. */
    data class Limited(val part: RecipePart, val original: Float) : ImportGap
}

private const val CRS_NS = "http://ns.adobe.com/camera-raw-settings/1.0/"
private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
private const val NAME_LIMIT = 120
private const val COLLECTION_LIMIT = 60

private val SIGNED_100 = -100f..100f
private val UNSIGNED_100 = 0f..100f

/** The values of one XMP packet: a field can be an attribute or an element, depending on who wrote it. */
internal class XmpFields(
    val values: Map<String, String>,
    /** Ordered and unordered arrays, by field. A mask's items have no text of their own, so only the count means anything. */
    val lists: Map<String, List<String>>,
)

internal fun xmpText(bytes: ByteArray): String? {
    val text = runCatching {
        when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("﻿")
        }
    }.getOrNull() ?: return null
    return text.takeIf { CRS_NS in it }
}

/**
 * Null when this is not XML that can be trusted to parse. The check is on the very text handed to the parser, so
 * a declared encoding cannot hide a DTD from it; the parser's own features differ between the JVM and Android.
 */
internal fun readXmpFields(text: String): XmpFields? {
    if ("<!DOCTYPE" in text || "<!ENTITY" in text) return null
    val document = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    }.getOrNull() ?: return null

    val values = linkedMapOf<String, String>()
    val lists = linkedMapOf<String, List<String>>()
    val descriptions = document.getElementsByTagNameNS(RDF_NS, "Description")
    for (index in 0 until descriptions.length) {
        val description = descriptions.item(index) as Element
        // Only the packet's own descriptions: a mask's items are descriptions too, one level inside a field.
        if ((description.parentNode as? Element)?.let { it.namespaceURI == RDF_NS && it.localName == "RDF" } != true) continue
        description.crsAttributes().forEach { (name, value) -> values[name] = value }
        description.childElements().filter { it.namespaceURI == CRS_NS }.forEach { field -> readField(field, values, lists) }
    }
    return XmpFields(values, lists)
}

private fun readField(field: Element, values: MutableMap<String, String>, lists: MutableMap<String, List<String>>) {
    val name = field.localName
    val container = field.childElements().firstOrNull { it.namespaceURI == RDF_NS }
    when {
        // The profile and the lens blur are structures: the attributes that matter are kept under the field's name.
        name == "Look" || name == "LensBlur" -> {
            val structure = (listOf(field) + field.childElements().filter { it.namespaceURI == RDF_NS && it.localName == "Description" })
            structure.forEach { part ->
                part.crsAttributes().forEach { (key, value) -> values["$name.$key"] = value }
                part.childElements().firstOrNull { it.namespaceURI == CRS_NS && it.localName == "Name" }?.let { child ->
                    values["$name.Name"] = child.alternativeText()
                }
            }
        }
        container?.localName == "Alt" -> values[name] = field.alternativeText()
        container?.localName == "Seq" || container?.localName == "Bag" ->
            lists[name] = container.childElements().filter { it.localName == "li" }.map { it.textContent.trim() }
        field.childElements().isEmpty() -> values[name] = field.textContent.trim()
        // Some other structure: there is something in it, and nothing here can read what.
        else -> lists[name] = listOf("")
    }
}

private fun Element.crsAttributes(): List<Pair<String, String>> = (0 until attributes.length).mapNotNull { index ->
    val attribute = attributes.item(index)
    if (attribute.namespaceURI == CRS_NS) attribute.localName to attribute.nodeValue else null
}

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** The x-default of a language alternative, or the first there is. */
private fun Element.alternativeText(): String {
    val items = childElements().firstOrNull { it.localName == "Alt" }?.childElements().orEmpty()
    val chosen = items.firstOrNull { it.getAttributeNS(XML_NS, "lang") == "x-default" } ?: items.firstOrNull()
    return chosen?.textContent?.trim().orEmpty()
}

/** Adobe's own presets may name themselves through a localisation key, as `$$$/Key=English`. */
private fun presetText(value: String?, limit: Int): String =
    cleanText(value?.let { if (it.startsWith("$$$/")) it.substringAfter('=', "") else it }, limit)

/**
 * The fields that say nothing about the picture, or that are read as part of another. Everything else that is off
 * its neutral value and was not read is named in the report, so that a setting cannot go missing unnoticed.
 */
private val INERT_FIELDS = setOf(
    "AlreadyApplied", "AsShotTemperature", "AsShotTint", "Baseline", "CameraModelRestriction", "CameraProfileDigest", "Cluster", "CompatibleVersion",
    "ContactInfo", "Copyright", "Description", "FavoriteProfiles", "GrainSeed", "Group", "HasSettings", "LensProfileSetup", "LookTable", "Name",
    "OverrideLookVignette", "PresetType", "ProcessVersion", "ProfileGainTableMap", "ProfileToneCurve", "RawFileName",
    "RawDefaults", "RequiresRGBTables", "RGBTable",
    "RGBTableAmount", "RGBTables", "ShortName", "ShowInPresets", "ShowInQuickActions", "SortName", "SupportsAmount",
    "SupportsAmount2", "SupportsColor", "SupportsHighDynamicRange", "SupportsMonochrome", "SupportsNormalDynamicRange",
    "SupportsOutputReferred", "SupportsSceneReferred", "ToneCurveName", "ToneCurveName2012", "UUID", "Version",
)

private val MASK_FIELDS = listOf(
    "MaskGroupBasedCorrections", "GradientBasedCorrections", "CircularGradientBasedCorrections", "PaintBasedCorrections",
)

private val OLD_TONE_FIELDS = listOf("Exposure", "Contrast", "Brightness", "Shadows", "FillLight", "HighlightRecovery", "Clarity")

private val GEOMETRY_FIELDS = listOf(
    "CropAngle", "PerspectiveVertical", "PerspectiveHorizontal", "PerspectiveRotate", "PerspectiveAspect", "PerspectiveX",
    "PerspectiveY", "PerspectiveUpright", "LensManualDistortionAmount",
)

private val GRAY_MIXER_FIELDS = listOf(
    "GrayMixerRed", "GrayMixerOrange", "GrayMixerYellow", "GrayMixerGreen",
    "GrayMixerAqua", "GrayMixerBlue", "GrayMixerPurple", "GrayMixerMagenta",
)

private val BASE_PROFILES = mapOf(
    "Default Color" to RAW_PROFILE_COLOR,
    "Adobe Color" to RAW_PROFILE_COLOR,
    "Adobe Standard" to RAW_PROFILE_COLOR,
    "Adobe Neutral" to RAW_PROFILE_NEUTRAL,
    "Adobe Vivid" to RAW_PROFILE_VIVID,
    "Default Monochrome" to RAW_PROFILE_MONOCHROME,
    "Adobe Monochrome" to RAW_PROFILE_MONOCHROME,
)

/** These leave the choice to the photo, which is what a recipe without the part does. */
private val PROFILES_OF_THE_PHOTO = setOf("", "Embedded", "Default Profile")

/**
 * How far a parametric slider at 100 moves the middle of its region, in curve levels. An estimate: see [parametricCurve].
 * The same reach everywhere would flatten the ends, where a region sits a few levels away from black or white, so
 * near them it shrinks with that distance.
 */
private const val PARAMETRIC_REACH = 64f
private const val PARAMETRIC_REACH_OF_DISTANCE = 0.6f

private class XmpConversion(private val file: XmpFields) {
    val parts = linkedSetOf<RecipePart>()
    val gaps = linkedSetOf<ImportGap>()
    private val read = mutableSetOf<String>()

    private fun raw(field: String): String? {
        read += field
        return file.values[field]
    }

    private fun number(field: String): Float? = raw(field)?.toFloatOrNull()

    private fun flag(field: String): Boolean = raw(field).equals("True", ignoreCase = true) || raw(field) == "1"

    private fun left(setting: ImportedSetting, reason: ImportGapReason = ImportGapReason.NOT_IN_APP) {
        gaps += ImportGap.Left(setting, reason)
    }

    private fun value(field: String, part: RecipePart, range: ClosedFloatingPointRange<Float>, default: Float = 0f): Float {
        val found = number(field) ?: return default
        parts += part
        val inRange = found.coerceIn(range)
        if (inRange != found) gaps += ImportGap.Limited(part, found)
        return inRange
    }

    private fun isOff(field: String, neutral: Float = 0f) = number(field)?.let { it == neutral } ?: true

    fun recipe(): EditRecipe {
        val blank = EditRecipe(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "")
        val built = blank.copy(
            whiteBalance = whiteBalance(),
            tone = tone(),
            presence = presence(),
            toneCurve = toneCurve(),
            hsl = hsl(),
            colorGrading = colorGrading(),
            detail = detail(),
            effects = effects(),
            calibration = calibration(),
            lens = lens(),
            profile = profile(),
        )
        reportWhatHasNoPlace()
        return blank.withPartsFrom(built, parts)
    }

    private fun whiteBalance(): WhiteBalance {
        val choice = raw("WhiteBalance")
        val temperature = number("Temperature")
        val incrementalTemperature = number("IncrementalTemperature")
        val incrementalTint = number("IncrementalTint")
        val fileTint = number("Tint")
        return when {
            choice == "As Shot" -> { parts += RecipePart.WHITE_BALANCE; WhiteBalance() }
            choice == "Auto" -> { parts += RecipePart.WHITE_BALANCE; WhiteBalance(auto = true) }
            incrementalTemperature != null || incrementalTint != null -> WhiteBalance(
                temp = value("IncrementalTemperature", RecipePart.WHITE_BALANCE, SIGNED_100),
                tint = value("IncrementalTint", RecipePart.WHITE_BALANCE, SIGNED_100),
            )
            temperature != null -> {
                parts += RecipePart.WHITE_BALANCE
                // Only the light: a preset written for a RAW has no offset from anything.
                WhiteBalance(light = KelvinTint(temperature, fileTint ?: 0f))
            }
            choice != null && choice != "Custom" -> { left(ImportedSetting.WHITE_BALANCE_PRESET, ImportGapReason.NOT_IMPORTED); WhiteBalance() }
            else -> WhiteBalance()
        }
    }

    private fun tone() = Tone(
        exposure = value("Exposure2012", RecipePart.EXPOSURE, -5f..5f),
        contrast = value("Contrast2012", RecipePart.CONTRAST, SIGNED_100),
        highlights = value("Highlights2012", RecipePart.HIGHLIGHTS, SIGNED_100),
        shadows = value("Shadows2012", RecipePart.SHADOWS, SIGNED_100),
        whites = value("Whites2012", RecipePart.WHITES, SIGNED_100),
        blacks = value("Blacks2012", RecipePart.BLACKS, SIGNED_100),
    )

    private fun presence() = Presence(
        texture = value("Texture", RecipePart.TEXTURE, SIGNED_100),
        clarity = value("Clarity2012", RecipePart.CLARITY, SIGNED_100),
        dehaze = value("Dehaze", RecipePart.DEHAZE, SIGNED_100),
        vibrance = value("Vibrance", RecipePart.VIBRANCE, SIGNED_100),
        saturation = if (flag("ConvertToGrayscale")) {
            // The monochrome profile only reaches a RAW; this is what makes the preset black and white on a JPEG too.
            parts += RecipePart.SATURATION
            read += "Saturation"
            -100f
        } else {
            value("Saturation", RecipePart.SATURATION, SIGNED_100)
        },
    )

    private fun curve(field: String): List<CurvePoint>? {
        val items = file.lists[field] ?: return null
        read += field
        val points = items.map { item ->
            val coordinates = item.split(',').map { it.trim().toFloatOrNull() }
            if (coordinates.size != 2 || coordinates.any { it == null }) return unreadableCurve()
            CurvePoint(coordinates[0]!!.roundToInt(), coordinates[1]!!.roundToInt())
        }.distinctBy { it.x }.sortedBy { it.x }
        if (points.isEmpty() || points.any { it.x !in 0..255 || it.y !in 0..255 }) return unreadableCurve()
        return points
    }

    private fun unreadableCurve(): List<CurvePoint>? {
        left(ImportedSetting.TONE_CURVE, ImportGapReason.NOT_IMPORTED)
        return null
    }

    private fun channel(modern: String, old: String): List<CurvePoint>? =
        (curve(modern) ?: curve(old))?.also { parts += RecipePart.TONE_CURVE }?.takeUnless { it.isIdentity() }

    private fun toneCurve(): ToneCurve {
        val composite = (curve("ToneCurvePV2012") ?: curve("ToneCurve"))?.also { parts += RecipePart.TONE_CURVE }
        val namedOnly = composite == null && listOf("ToneCurveName2012", "ToneCurveName").any { field ->
            raw(field).let { it != null && it != "Linear" && it != "Custom" }
        }
        if (namedOnly) left(ImportedSetting.TONE_CURVE, ImportGapReason.NOT_IMPORTED)
        // A file may hold a curve under both names; the older one is then only a copy for older readers.
        read += listOf("ToneCurve", "ToneCurveRed", "ToneCurveGreen", "ToneCurveBlue")
        val rgb = parametricCurve(composite ?: ToneCurve().rgb)
        return ToneCurve(
            rgb = rgb,
            red = channel("ToneCurvePV2012Red", "ToneCurveRed"),
            green = channel("ToneCurvePV2012Green", "ToneCurveGreen"),
            blue = channel("ToneCurvePV2012Blue", "ToneCurveBlue"),
        )
    }

    /**
     * The four region sliders drawn as points, the way the person asked for them to be carried over: Adobe does not
     * publish the formula, so the shape is a stand-in and the report says so. Each slider lifts the middle of its
     * region, and the spline that reads the curve makes the rest smooth. The point curve then runs on top.
     */
    private fun parametricCurve(base: List<CurvePoint>): List<CurvePoint> {
        val amounts = listOf("ParametricShadows", "ParametricDarks", "ParametricLights", "ParametricHighlights")
            .map { number(it) ?: 0f }
        var splits = listOf(number("ParametricShadowSplit") ?: 25f, number("ParametricMidtoneSplit") ?: 50f, number("ParametricHighlightSplit") ?: 75f)
        if (amounts.all { it == 0f }) return base
        if (splits.zipWithNext().any { (a, b) -> a >= b } || splits.first() <= 0f || splits.last() >= 100f) splits = listOf(25f, 50f, 75f)
        parts += RecipePart.TONE_CURVE
        left(ImportedSetting.PARAMETRIC_CURVE, ImportGapReason.APPROXIMATED)

        val edges = listOf(0f) + splits + 100f
        val centres = edges.zipWithNext { a, b -> (a + b) / 2f * 2.55f }
        val controls = listOf(CurvePoint(0, 0)) + centres.mapIndexed { index, centre ->
            val reach = minOf(PARAMETRIC_REACH, minOf(centre, 255f - centre) * PARAMETRIC_REACH_OF_DISTANCE)
            val height = centre + amounts[index].coerceIn(SIGNED_100) / 100f * reach
            CurvePoint(centre.roundToInt(), height.roundToInt().coerceIn(0, 255))
        } + CurvePoint(255, 255)

        var floor = 0
        return (controls.map { it.x } + base.map { it.x }).distinct().sorted().map { x ->
            val y = base.heightAt(controls.heightAt(x.toFloat())).roundToInt().coerceIn(floor, 255)
            floor = y
            CurvePoint(x, y)
        }
    }

    private fun hsl(): Hsl {
        fun band(colour: String) = HslBand(
            hue = value("HueAdjustment$colour", RecipePart.COLOR_MIXER, SIGNED_100),
            saturation = value("SaturationAdjustment$colour", RecipePart.COLOR_MIXER, SIGNED_100),
            luminance = value("LuminanceAdjustment$colour", RecipePart.COLOR_MIXER, SIGNED_100),
        )
        return Hsl(band("Red"), band("Orange"), band("Yellow"), band("Green"), band("Aqua"), band("Blue"), band("Purple"), band("Magenta"))
    }

    private fun colorGrading(): ColorGrading {
        val part = RecipePart.COLOR_GRADING
        fun wheel(hue: String, saturation: String, luminance: String) = ColorWheel(
            hue = value(hue, part, 0f..360f),
            saturation = value(saturation, part, UNSIGNED_100),
            luminance = value(luminance, part, SIGNED_100),
        )
        val grading = ColorGrading(
            // The shadows and highlights kept their old split toning names when the two other wheels were added.
            shadows = wheel("SplitToningShadowHue", "SplitToningShadowSaturation", "ColorGradeShadowLum"),
            midtones = wheel("ColorGradeMidtoneHue", "ColorGradeMidtoneSat", "ColorGradeMidtoneLum"),
            highlights = wheel("SplitToningHighlightHue", "SplitToningHighlightSaturation", "ColorGradeHighlightLum"),
            global = wheel("ColorGradeGlobalHue", "ColorGradeGlobalSat", "ColorGradeGlobalLum"),
            balance = value("SplitToningBalance", part, SIGNED_100),
        )
        // A file from before the wheels has no blending, and split toning is what a blending of 100 draws.
        return grading.copy(blending = value("ColorGradeBlending", part, UNSIGNED_100, default = 100f))
    }

    private fun detail(): Detail {
        val defaults = Detail()
        val luminance = RecipePart.LUMINANCE_NOISE_REDUCTION
        val colour = RecipePart.COLOR_NOISE_REDUCTION
        return Detail(
            sharpenAmount = value("Sharpness", RecipePart.SHARPENING, 0f..150f),
            sharpenRadius = value("SharpenRadius", RecipePart.SHARPENING, 0.5f..3f, defaults.sharpenRadius),
            sharpenDetail = value("SharpenDetail", RecipePart.SHARPENING, UNSIGNED_100, defaults.sharpenDetail),
            sharpenMasking = value("SharpenEdgeMasking", RecipePart.SHARPENING, UNSIGNED_100),
            noiseReductionLuminance = value("LuminanceSmoothing", luminance, UNSIGNED_100),
            noiseReductionLuminanceDetail = value("LuminanceNoiseReductionDetail", luminance, UNSIGNED_100, defaults.noiseReductionLuminanceDetail),
            noiseReductionContrast = value("LuminanceNoiseReductionContrast", luminance, UNSIGNED_100),
            noiseReductionColor = value("ColorNoiseReduction", colour, UNSIGNED_100, defaults.noiseReductionColor),
            noiseReductionColorDetail = value("ColorNoiseReductionDetail", colour, UNSIGNED_100, defaults.noiseReductionColorDetail),
            noiseReductionColorSmoothness = value("ColorNoiseReductionSmoothness", colour, UNSIGNED_100, defaults.noiseReductionColorSmoothness),
        )
    }

    private fun effects(): Effects {
        val defaults = Effects()
        val amount = value("PostCropVignetteAmount", RecipePart.VIGNETTE, SIGNED_100)
        val style = number("PostCropVignetteStyle")
        val highlights = number("PostCropVignetteHighlightContrast") ?: 0f
        if (amount != 0f && ((style != null && style != 1f) || highlights != 0f)) left(ImportedSetting.VIGNETTE_STYLE)
        return Effects(
            vignetteAmount = amount,
            vignetteMidpoint = value("PostCropVignetteMidpoint", RecipePart.VIGNETTE, UNSIGNED_100, defaults.vignetteMidpoint),
            vignetteRoundness = value("PostCropVignetteRoundness", RecipePart.VIGNETTE, SIGNED_100),
            vignetteFeather = value("PostCropVignetteFeather", RecipePart.VIGNETTE, UNSIGNED_100, defaults.vignetteFeather),
            grainAmount = value("GrainAmount", RecipePart.GRAIN, UNSIGNED_100),
            grainSize = value("GrainSize", RecipePart.GRAIN, UNSIGNED_100, defaults.grainSize),
            grainRoughness = value("GrainFrequency", RecipePart.GRAIN, UNSIGNED_100, defaults.grainRoughness),
        )
    }

    private fun calibration() = Calibration(
        shadowsTint = value("ShadowTint", RecipePart.CALIBRATION, SIGNED_100),
        redHue = value("RedHue", RecipePart.CALIBRATION, SIGNED_100),
        redSaturation = value("RedSaturation", RecipePart.CALIBRATION, SIGNED_100),
        greenHue = value("GreenHue", RecipePart.CALIBRATION, SIGNED_100),
        greenSaturation = value("GreenSaturation", RecipePart.CALIBRATION, SIGNED_100),
        blueHue = value("BlueHue", RecipePart.CALIBRATION, SIGNED_100),
        blueSaturation = value("BlueSaturation", RecipePart.CALIBRATION, SIGNED_100),
    )

    private fun lens(): LensCorrection {
        val defaults = LensCorrection()
        val purple = value("DefringePurpleAmount", RecipePart.DEFRINGE, UNSIGNED_100)
        val green = value("DefringeGreenAmount", RecipePart.DEFRINGE, UNSIGNED_100)
        // Two hue limits against one range around a fixed colour: only the usual limits say the same thing.
        val movedPurple = purple > 0f && !(isOff("DefringePurpleHueLo", 30f) && isOff("DefringePurpleHueHi", 70f))
        val movedGreen = green > 0f && !(isOff("DefringeGreenHueLo", 40f) && isOff("DefringeGreenHueHi", 60f))
        listOf("DefringePurpleHueLo", "DefringePurpleHueHi", "DefringeGreenHueLo", "DefringeGreenHueHi").forEach { read += it }
        if (movedPurple || movedGreen) left(ImportedSetting.DEFRINGE_HUE)
        return LensCorrection(
            vignetteAmount = value("VignetteAmount", RecipePart.LENS_VIGNETTE, SIGNED_100),
            vignetteMidpoint = value("VignetteMidpoint", RecipePart.LENS_VIGNETTE, UNSIGNED_100, defaults.vignetteMidpoint),
            purpleAmount = purple,
            greenAmount = green,
        )
    }

    private fun profile(): String {
        val look = raw("Look.Name").orEmpty()
        val camera = raw("CameraProfile").orEmpty()
        read += "AutoGrayscaleMix"
        val mixed = GRAY_MIXER_FIELDS.any { !isOff(it) }
        if (flag("ConvertToGrayscale")) {
            parts += RecipePart.PROFILE
            if (mixed) left(ImportedSetting.GRAY_MIXER)
            if (look.isNotEmpty() && look !in BASE_PROFILES) left(ImportedSetting.CREATIVE_PROFILE)
            return RAW_PROFILE_MONOCHROME
        }
        BASE_PROFILES[look]?.let { parts += RecipePart.PROFILE; return it }
        if (look.isNotEmpty() || file.values["RGBTable"]?.isNotEmpty() == true) {
            left(ImportedSetting.CREATIVE_PROFILE)
            return RAW_PROFILE_COLOR
        }
        BASE_PROFILES[camera]?.let { parts += RecipePart.PROFILE; return it }
        if (camera !in PROFILES_OF_THE_PHOTO) left(ImportedSetting.CAMERA_PROFILE)
        return RAW_PROFILE_COLOR
    }

    private fun reportWhatHasNoPlace() {
        if (MASK_FIELDS.any { file.lists[it].orEmpty().isNotEmpty() }) left(ImportedSetting.MASKS, ImportGapReason.NOT_IMPORTED)
        read += MASK_FIELDS

        val old = OLD_TONE_FIELDS.any { !isOff(it) } || !isOff("HighlightRecovery")
        read += OLD_TONE_FIELDS
        if (old) left(ImportedSetting.OLD_TONE_CONTROLS, ImportGapReason.OLD_PROCESS)

        val cropped = flag("HasCrop") || !isOff("CropTop") || !isOff("CropLeft") || !isOff("CropBottom", 1f) || !isOff("CropRight", 1f)
        val scaled = !isOff("PerspectiveScale", 100f)
        listOf("CropWidth", "CropHeight", "CropUnits", "CropConstrainToWarp", "CropConstrainToUnitSquare").forEach { read += it }
        if (cropped || scaled || GEOMETRY_FIELDS.any { !isOff(it) }) left(ImportedSetting.GEOMETRY, ImportGapReason.NOT_IMPORTED)
        read += GEOMETRY_FIELDS

        if (flag("LensProfileEnable")) left(ImportedSetting.LENS_PROFILE)
        if (flag("AutoLateralCA")) left(ImportedSetting.CHROMATIC_ABERRATION)
        if (flag("AutoTone")) left(ImportedSetting.AUTO_TONE)
        if ("ISODependent" in file.lists || "ISODependent" in file.values) left(ImportedSetting.ISO_ADAPTIVE)
        if (file.lists["PointColors"].orEmpty().isNotEmpty()) left(ImportedSetting.POINT_COLOR)
        if (flag("LensBlur.Active")) left(ImportedSetting.LENS_BLUR)
        if (!isOff("CurveRefineSaturation", 100f)) left(ImportedSetting.OTHER)
        read += listOf("ISODependent", "PointColors", "ColorVariance", "LensBlur")

        val untouched = file.values.filterKeys { key ->
            key !in read && key !in INERT_FIELDS && !key.startsWith("Table_") && !key.startsWith("Look.") && !key.startsWith("LensBlur.")
        }
        val offNeutral = untouched.values.any { value -> value.isNotBlank() && value.toFloatOrNull() != 0f && !value.equals("False", ignoreCase = true) }
        val unknownList = file.lists.any { (key, items) -> key !in read && key !in INERT_FIELDS && items.isNotEmpty() }
        if (offNeutral || unknownList) left(ImportedSetting.OTHER)
    }
}

/** Straight between the points and level beyond the ends: only used to stack one curve on another. */
private fun List<CurvePoint>.heightAt(x: Float): Float {
    if (x <= first().x) return first().y.toFloat()
    if (x >= last().x) return last().y.toFloat()
    val after = indexOfFirst { it.x >= x }
    val a = this[after - 1]
    val b = this[after]
    return a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
}

private fun List<CurvePoint>.isIdentity() = this == listOf(CurvePoint(0, 0), CurvePoint(255, 255))

/** One preset, or null when the packet has nothing a recipe can hold. Invalid XML is the caller's [fields] being null. */
internal fun presetEntry(
    fields: XmpFields,
    fallbackName: String,
    collection: String,
    origin: RecipeOrigin,
): ImagoFileEntry {
    val conversion = XmpConversion(fields)
    val recipe = conversion.recipe()
    val name = presetText(fields.values["Name"], NAME_LIMIT).ifEmpty { cleanText(fallbackName, NAME_LIMIT) }
    if (conversion.parts.isEmpty() && conversion.gaps.isNotEmpty()) return ImagoFileEntry.NothingApplies(name, conversion.gaps.toList())
    if (conversion.parts.isEmpty() || !recipe.isSound()) return ImagoFileEntry.Invalid
    return ImagoFileEntry.Ready(
        name = name,
        collection = presetText(fields.values["Group"], COLLECTION_LIMIT).ifEmpty { collection },
        parts = conversion.parts,
        recipe = recipe,
        origin = origin,
        gaps = conversion.gaps.toList(),
    )
}
