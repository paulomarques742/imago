package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.model.Calibration
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.LensCorrection
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Effects
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Hsl
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.LinearMask
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.Perspective
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.RadialMask
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.WhiteBalance
import eu.studio742.imago.core.render.ToneCurveInterpolation
import eu.studio742.imago.core.render.buildToneCurveLut
import kotlin.math.roundToInt

/**
 * The editor's panels and the sheets they open, in the order a video made from the finished recipe
 * walks them — Light with its curve, Colour with HSL and grading, Detail, Effects — so a step is
 * named as the person knows it from the editor.
 *
 * Geometry comes first so that the photo does not change shape halfway through the adjustments.
 * Sharpening and noise reduction are inside the Detail step.
 */
enum class ReelPanel { GEOMETRY, LIGHT, CURVE, COLOR, HSL, COLOR_GRADING, DETAIL, EFFECTS, MASKS }

/** A control the video shows moving: the same identities the editor's sheets use. */
sealed interface ReelControl {
    val panel: ReelPanel

    data class Slider(val adjustment: Adjustment) : ReelControl {
        override val panel: ReelPanel get() = adjustment.reelPanel()
    }

    data class HslSlider(val band: HslColorBand, val component: HslComponent) : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.HSL
    }

    data class GradeSlider(val wheel: ColorGradeWheel, val component: ColorGradeComponent) : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.COLOR_GRADING
    }

    data class Curve(val channel: CurveChannel) : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.CURVE
    }

    /** The 90° turns and the flips: they jump, they do not slide. */
    data object Rotation : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.GEOMETRY
    }

    data object FlipHorizontal : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.GEOMETRY
    }

    data object FlipVertical : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.GEOMETRY
    }

    data object Straighten : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.GEOMETRY
    }

    data object Crop : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.GEOMETRY
    }

    data object Upright : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.GEOMETRY
    }

    /** The region of a mask appearing, moving or changing shape. */
    data class MaskArea(val maskId: String) : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.MASKS
    }

    data class MaskSlider(val maskId: String, val adjustment: Adjustment) : ReelControl {
        override val panel: ReelPanel get() = ReelPanel.MASKS
    }
}

internal fun Adjustment.reelPanel(): ReelPanel = when (this) {
    Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS,
    Adjustment.WHITES, Adjustment.BLACKS,
    -> ReelPanel.LIGHT
    Adjustment.TEMPERATURE, Adjustment.TINT, Adjustment.VIBRANCE, Adjustment.SATURATION -> ReelPanel.COLOR
    Adjustment.TEXTURE, Adjustment.CLARITY, Adjustment.DEHAZE,
    Adjustment.SHARPEN_AMOUNT, Adjustment.SHARPEN_RADIUS, Adjustment.SHARPEN_DETAIL, Adjustment.SHARPEN_MASKING,
    Adjustment.NR_LUMINANCE, Adjustment.NR_LUMINANCE_DETAIL, Adjustment.NR_CONTRAST, Adjustment.NR_COLOR, Adjustment.NR_COLOR_DETAIL, Adjustment.NR_COLOR_SMOOTHNESS,
    -> ReelPanel.DETAIL
    Adjustment.CAL_SHADOWS_TINT, Adjustment.CAL_RED_HUE, Adjustment.CAL_RED_SATURATION, Adjustment.CAL_GREEN_HUE, Adjustment.CAL_GREEN_SATURATION, Adjustment.CAL_BLUE_HUE, Adjustment.CAL_BLUE_SATURATION -> ReelPanel.COLOR
    Adjustment.LENS_VIGNETTE_AMOUNT, Adjustment.LENS_VIGNETTE_MIDPOINT, Adjustment.LENS_PURPLE_AMOUNT, Adjustment.LENS_PURPLE_RANGE, Adjustment.LENS_GREEN_AMOUNT, Adjustment.LENS_GREEN_RANGE -> ReelPanel.GEOMETRY
    Adjustment.VIGNETTE_AMOUNT, Adjustment.VIGNETTE_MIDPOINT, Adjustment.VIGNETTE_ROUNDNESS,
    Adjustment.VIGNETTE_FEATHER, Adjustment.GRAIN_AMOUNT, Adjustment.GRAIN_SIZE,
    Adjustment.GRAIN_ROUGHNESS,
    -> ReelPanel.EFFECTS
    Adjustment.GRADE_BLENDING, Adjustment.GRADE_BALANCE -> ReelPanel.COLOR_GRADING
    Adjustment.PERSPECTIVE_DISTORTION, Adjustment.PERSPECTIVE_VERTICAL, Adjustment.PERSPECTIVE_HORIZONTAL,
    Adjustment.PERSPECTIVE_ASPECT, Adjustment.PERSPECTIVE_SCALE, Adjustment.PERSPECTIVE_OFFSET_X,
    Adjustment.PERSPECTIVE_OFFSET_Y,
    -> ReelPanel.GEOMETRY
    Adjustment.MASK_AMOUNT -> ReelPanel.MASKS
}

/** The order of the editor's sliders inside each panel. */
private val LIGHT_ORDER = listOf(
    Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS,
    Adjustment.SHADOWS, Adjustment.WHITES, Adjustment.BLACKS,
)
private val CALIBRATION_ORDER = listOf(
    Adjustment.CAL_SHADOWS_TINT, Adjustment.CAL_RED_HUE, Adjustment.CAL_RED_SATURATION, Adjustment.CAL_GREEN_HUE, Adjustment.CAL_GREEN_SATURATION, Adjustment.CAL_BLUE_HUE, Adjustment.CAL_BLUE_SATURATION,
)
private val COLOR_ORDER = listOf(Adjustment.TEMPERATURE, Adjustment.TINT, Adjustment.VIBRANCE, Adjustment.SATURATION)
private val DETAIL_ORDER = listOf(Adjustment.TEXTURE, Adjustment.CLARITY, Adjustment.DEHAZE)
private val NOISE_ORDER = listOf(
    Adjustment.NR_LUMINANCE, Adjustment.NR_LUMINANCE_DETAIL, Adjustment.NR_CONTRAST,
    Adjustment.NR_COLOR, Adjustment.NR_COLOR_DETAIL, Adjustment.NR_COLOR_SMOOTHNESS,
)
private val SHARPEN_ORDER = listOf(
    Adjustment.SHARPEN_AMOUNT, Adjustment.SHARPEN_RADIUS, Adjustment.SHARPEN_DETAIL, Adjustment.SHARPEN_MASKING,
)
private val EFFECTS_ORDER = listOf(
    Adjustment.VIGNETTE_AMOUNT, Adjustment.VIGNETTE_MIDPOINT, Adjustment.VIGNETTE_ROUNDNESS,
    Adjustment.VIGNETTE_FEATHER, Adjustment.GRAIN_AMOUNT, Adjustment.GRAIN_SIZE, Adjustment.GRAIN_ROUGHNESS,
)
/** The mask panel's order: the Amount on top, as in the editor, then the thirteen adjustments. */
private val LOCAL_ORDER = listOf(Adjustment.MASK_AMOUNT) + LIGHT_ORDER + COLOR_ORDER + DETAIL_ORDER

/**
 * The value a control shows for [recipe], or null for the controls that have no number — a curve,
 * a crop, a turn.
 *
 * It is read from the interpolated recipe, not interpolated on its own, so the number on screen is
 * always the one the photo under it was rendered with.
 */
fun ReelControl.valueIn(recipe: EditRecipe): Float? = when (this) {
    is ReelControl.Slider -> recipe.adjustmentValue(adjustment)
    is ReelControl.HslSlider -> recipe.hslBand(band).component(component)
    is ReelControl.GradeSlider -> recipe.colorGradeWheel(wheel).component(component)
    is ReelControl.MaskSlider -> recipe.maskSliderValue(maskId, adjustment)
    ReelControl.Straighten -> recipe.geometry.straighten
    is ReelControl.Curve, ReelControl.Rotation, ReelControl.FlipHorizontal, ReelControl.FlipVertical,
    ReelControl.Crop, ReelControl.Upright, is ReelControl.MaskArea,
    -> null
}

/** What moves between two recipes, in panel order and, inside a panel, in the order of its sliders. */
internal fun changedControls(from: EditRecipe, to: EditRecipe): List<ReelControl> = buildList {
    val a = from.geometry
    val b = to.geometry
    if (a.rotation != b.rotation) add(ReelControl.Rotation)
    if (a.mirrorH != b.mirrorH) add(ReelControl.FlipHorizontal)
    if (a.mirrorV != b.mirrorV) add(ReelControl.FlipVertical)
    if (a.straighten != b.straighten) add(ReelControl.Straighten)
    if (a.cropRect != b.cropRect || a.aspectLock != b.aspectLock) add(ReelControl.Crop)
    val pa = a.perspective
    val pb = b.perspective
    if (pa.upright != pb.upright || pa.uprightRoll != pb.uprightRoll || pa.uprightPitch != pb.uprightPitch ||
        pa.uprightYaw != pb.uprightYaw || pa.guides != pb.guides || pa.constrainCrop != pb.constrainCrop
    ) {
        add(ReelControl.Upright)
    }
    addSliders(from, to, PERSPECTIVE_ADJUSTMENTS)
    addSliders(from, to, LIGHT_ORDER)
    CurveChannel.entries
        .filter { from.editableCurvePoints(it) != to.editableCurvePoints(it) }
        .forEach { add(ReelControl.Curve(it)) }
    addSliders(from, to, COLOR_ORDER + CALIBRATION_ORDER)
    for (component in HslComponent.entries) {
        for (band in HslColorBand.entries) {
            if (from.hslBand(band).component(component) != to.hslBand(band).component(component)) {
                add(ReelControl.HslSlider(band, component))
            }
        }
    }
    for (wheel in ColorGradeWheel.entries) {
        for (component in ColorGradeComponent.entries) {
            if (from.colorGradeWheel(wheel).component(component) != to.colorGradeWheel(wheel).component(component)) {
                add(ReelControl.GradeSlider(wheel, component))
            }
        }
    }
    addSliders(from, to, listOf(Adjustment.GRADE_BLENDING, Adjustment.GRADE_BALANCE))
    addSliders(from, to, DETAIL_ORDER + SHARPEN_ORDER + NOISE_ORDER)
    addSliders(from, to, EFFECTS_ORDER)
    for (id in maskIds(from, to)) {
        val maskA = from.mask(id)
        val maskB = to.mask(id)
        if (maskA == null || maskB == null || maskA.components != maskB.components ||
            maskA.inverted != maskB.inverted || maskA.enabled != maskB.enabled
        ) {
            add(ReelControl.MaskArea(id))
        }
        LOCAL_ORDER
            .filter { from.maskSliderValue(id, it) != to.maskSliderValue(id, it) }
            .forEach { add(ReelControl.MaskSlider(id, it)) }
    }
}

private fun MutableList<ReelControl>.addSliders(from: EditRecipe, to: EditRecipe, order: List<Adjustment>) {
    order.filter { from.adjustmentValue(it) != to.adjustmentValue(it) }.forEach { add(ReelControl.Slider(it)) }
}

/** The target's masks first, in its order, then those only the start had: they fade out. */
private fun maskIds(from: EditRecipe, to: EditRecipe): List<String> =
    (to.masks.map { it.id } + from.masks.map { it.id }).distinct()

/**
 * The photo before any adjustment: the same asset, process version and RAW development, everything
 * else at neutral — the default profile included.
 *
 * The masks stay, with their adjustments at zero — a mask without adjustments changes no pixel, and
 * keeping it lets the step that raises its sliders show the region it acts on.
 */
internal fun EditRecipe.neutral(): EditRecipe = EditRecipe(
    schemaVersion = schemaVersion,
    assetId = assetId,
    originalChecksum = originalChecksum,
    processVersion = processVersion,
    createdAt = createdAt,
    updatedAt = updatedAt,
    derivedAssetId = derivedAssetId,
    masks = masks.map { it.copy(adjustments = LocalAdjustments()) },
    rawDevelopment = rawDevelopment,
)

/** Whether two recipes draw the same photo, ignoring when they were written. */
internal fun EditRecipe.drawsLike(other: EditRecipe): Boolean =
    copy(createdAt = "", updatedAt = "") == other.copy(createdAt = "", updatedAt = "")

/**
 * The recipe a fraction [t] of the way from [from] to [to].
 *
 * Every number moves in a straight line. What has no in-between — the 90° turns, the flips, the
 * Upright mode, a mask's inversion, the profile — changes halfway. The ends are returned as they are, so the
 * last frame of a step is exactly the recipe the editor saved.
 */
internal fun interpolateRecipe(from: EditRecipe, to: EditRecipe, t: Float): EditRecipe {
    if (t <= 0f) return from
    if (t >= 1f) return to
    val late = t >= 0.5f
    return to.copy(
        profile = if (late) to.profile else from.profile,
        whiteBalance = WhiteBalance(
            temp = lerp(from.whiteBalance.temp, to.whiteBalance.temp, t),
            tint = lerp(from.whiteBalance.tint, to.whiteBalance.tint, t),
        ),
        tone = Tone(
            exposure = lerp(from.tone.exposure, to.tone.exposure, t),
            contrast = lerp(from.tone.contrast, to.tone.contrast, t),
            highlights = lerp(from.tone.highlights, to.tone.highlights, t),
            shadows = lerp(from.tone.shadows, to.tone.shadows, t),
            whites = lerp(from.tone.whites, to.tone.whites, t),
            blacks = lerp(from.tone.blacks, to.tone.blacks, t),
        ),
        presence = Presence(
            texture = lerp(from.presence.texture, to.presence.texture, t),
            clarity = lerp(from.presence.clarity, to.presence.clarity, t),
            dehaze = lerp(from.presence.dehaze, to.presence.dehaze, t),
            vibrance = lerp(from.presence.vibrance, to.presence.vibrance, t),
            saturation = lerp(from.presence.saturation, to.presence.saturation, t),
        ),
        calibration = Calibration(
            shadowsTint = lerp(from.calibration.shadowsTint, to.calibration.shadowsTint, t),
            redHue = lerp(from.calibration.redHue, to.calibration.redHue, t),
            redSaturation = lerp(from.calibration.redSaturation, to.calibration.redSaturation, t),
            greenHue = lerp(from.calibration.greenHue, to.calibration.greenHue, t),
            greenSaturation = lerp(from.calibration.greenSaturation, to.calibration.greenSaturation, t),
            blueHue = lerp(from.calibration.blueHue, to.calibration.blueHue, t),
            blueSaturation = lerp(from.calibration.blueSaturation, to.calibration.blueSaturation, t),
        ),
        lens = LensCorrection(
            vignetteAmount = lerp(from.lens.vignetteAmount, to.lens.vignetteAmount, t),
            vignetteMidpoint = lerp(from.lens.vignetteMidpoint, to.lens.vignetteMidpoint, t),
            purpleAmount = lerp(from.lens.purpleAmount, to.lens.purpleAmount, t),
            purpleRange = lerp(from.lens.purpleRange, to.lens.purpleRange, t),
            greenAmount = lerp(from.lens.greenAmount, to.lens.greenAmount, t),
            greenRange = lerp(from.lens.greenRange, to.lens.greenRange, t),
        ),
        toneCurve = interpolateCurves(from, to, t),
        hsl = Hsl(
            red = lerp(from.hsl.red, to.hsl.red, t),
            orange = lerp(from.hsl.orange, to.hsl.orange, t),
            yellow = lerp(from.hsl.yellow, to.hsl.yellow, t),
            green = lerp(from.hsl.green, to.hsl.green, t),
            aqua = lerp(from.hsl.aqua, to.hsl.aqua, t),
            blue = lerp(from.hsl.blue, to.hsl.blue, t),
            purple = lerp(from.hsl.purple, to.hsl.purple, t),
            magenta = lerp(from.hsl.magenta, to.hsl.magenta, t),
        ),
        colorGrading = ColorGrading(
            shadows = lerp(from.colorGrading.shadows, to.colorGrading.shadows, t),
            midtones = lerp(from.colorGrading.midtones, to.colorGrading.midtones, t),
            highlights = lerp(from.colorGrading.highlights, to.colorGrading.highlights, t),
            global = lerp(from.colorGrading.global, to.colorGrading.global, t),
            blending = lerp(from.colorGrading.blending, to.colorGrading.blending, t),
            balance = lerp(from.colorGrading.balance, to.colorGrading.balance, t),
        ),
        detail = Detail(
            sharpenAmount = lerp(from.detail.sharpenAmount, to.detail.sharpenAmount, t),
            sharpenRadius = lerp(from.detail.sharpenRadius, to.detail.sharpenRadius, t),
            sharpenDetail = lerp(from.detail.sharpenDetail, to.detail.sharpenDetail, t),
            sharpenMasking = lerp(from.detail.sharpenMasking, to.detail.sharpenMasking, t),
            noiseReductionLuminance = lerp(from.detail.noiseReductionLuminance, to.detail.noiseReductionLuminance, t),
            noiseReductionLuminanceDetail = lerp(from.detail.noiseReductionLuminanceDetail, to.detail.noiseReductionLuminanceDetail, t),
            noiseReductionContrast = lerp(from.detail.noiseReductionContrast, to.detail.noiseReductionContrast, t),
            noiseReductionColor = lerp(from.detail.noiseReductionColor, to.detail.noiseReductionColor, t),
            noiseReductionColorDetail = lerp(from.detail.noiseReductionColorDetail, to.detail.noiseReductionColorDetail, t),
            noiseReductionColorSmoothness = lerp(from.detail.noiseReductionColorSmoothness, to.detail.noiseReductionColorSmoothness, t),
        ),
        effects = Effects(
            vignetteAmount = lerp(from.effects.vignetteAmount, to.effects.vignetteAmount, t),
            vignetteMidpoint = lerp(from.effects.vignetteMidpoint, to.effects.vignetteMidpoint, t),
            vignetteRoundness = lerp(from.effects.vignetteRoundness, to.effects.vignetteRoundness, t),
            vignetteFeather = lerp(from.effects.vignetteFeather, to.effects.vignetteFeather, t),
            grainAmount = lerp(from.effects.grainAmount, to.effects.grainAmount, t),
            grainSize = lerp(from.effects.grainSize, to.effects.grainSize, t),
            grainRoughness = lerp(from.effects.grainRoughness, to.effects.grainRoughness, t),
        ),
        geometry = interpolateGeometry(from.geometry, to.geometry, t, late),
        masks = maskIds(from, to).map { id ->
            val a = from.mask(id)
            val b = to.mask(id)
            interpolateMask(
                a ?: b!!.copy(adjustments = LocalAdjustments()),
                b ?: a!!.copy(adjustments = LocalAdjustments()),
                t,
                late,
            )
        },
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun lerp(a: HslBand, b: HslBand, t: Float) = HslBand(
    hue = lerp(a.hue, b.hue, t),
    saturation = lerp(a.saturation, b.saturation, t),
    luminance = lerp(a.luminance, b.luminance, t),
)

/**
 * A wheel's hue goes round the short way, and a wheel at the centre has no hue to come from: going
 * from zero radius, the handle leaves in the final direction instead of sweeping the circle.
 */
private fun lerp(a: ColorWheel, b: ColorWheel, t: Float): ColorWheel {
    val hue = when {
        a.saturation == 0f -> b.hue
        b.saturation == 0f -> a.hue
        else -> {
            val turn = ((b.hue - a.hue) % 360f + 540f) % 360f - 180f
            positiveHue(a.hue + turn * t)
        }
    }
    return ColorWheel(
        hue = hue,
        saturation = lerp(a.saturation, b.saturation, t),
        luminance = lerp(a.luminance, b.luminance, t),
    )
}

private fun interpolateGeometry(a: Geometry, b: Geometry, t: Float, late: Boolean): Geometry {
    val pa = a.perspective
    val pb = b.perspective
    return Geometry(
        cropRect = CropRect(
            x = lerp(a.cropRect.x, b.cropRect.x, t),
            y = lerp(a.cropRect.y, b.cropRect.y, t),
            w = lerp(a.cropRect.w, b.cropRect.w, t),
            h = lerp(a.cropRect.h, b.cropRect.h, t),
        ),
        straighten = lerp(a.straighten, b.straighten, t),
        rotation = if (late) b.rotation else a.rotation,
        mirrorH = if (late) b.mirrorH else a.mirrorH,
        mirrorV = if (late) b.mirrorV else a.mirrorV,
        aspectLock = if (late) b.aspectLock else a.aspectLock,
        perspective = Perspective(
            vertical = lerp(pa.vertical, pb.vertical, t),
            horizontal = lerp(pa.horizontal, pb.horizontal, t),
            aspect = lerp(pa.aspect, pb.aspect, t),
            scale = lerp(pa.scale, pb.scale, t),
            offsetX = lerp(pa.offsetX, pb.offsetX, t),
            offsetY = lerp(pa.offsetY, pb.offsetY, t),
            constrainCrop = if (late) pb.constrainCrop else pa.constrainCrop,
            upright = if (late) pb.upright else pa.upright,
            uprightRoll = lerp(pa.uprightRoll, pb.uprightRoll, t),
            uprightPitch = lerp(pa.uprightPitch, pb.uprightPitch, t),
            uprightYaw = lerp(pa.uprightYaw, pb.uprightYaw, t),
            guides = if (late) pb.guides else pa.guides,
        ),
    )
}

private fun interpolateMask(a: LocalMask, b: LocalMask, t: Float, late: Boolean): LocalMask = b.copy(
    enabled = if (late) b.enabled else a.enabled,
    inverted = if (late) b.inverted else a.inverted,
    components = interpolateComponents(a.components, b.components, t, late),
    adjustments = LocalAdjustments(
        exposure = lerp(a.adjustments.exposure, b.adjustments.exposure, t),
        contrast = lerp(a.adjustments.contrast, b.adjustments.contrast, t),
        highlights = lerp(a.adjustments.highlights, b.adjustments.highlights, t),
        shadows = lerp(a.adjustments.shadows, b.adjustments.shadows, t),
        whites = lerp(a.adjustments.whites, b.adjustments.whites, t),
        blacks = lerp(a.adjustments.blacks, b.adjustments.blacks, t),
        temp = lerp(a.adjustments.temp, b.adjustments.temp, t),
        tint = lerp(a.adjustments.tint, b.adjustments.tint, t),
        vibrance = lerp(a.adjustments.vibrance, b.adjustments.vibrance, t),
        saturation = lerp(a.adjustments.saturation, b.adjustments.saturation, t),
        texture = lerp(a.adjustments.texture, b.adjustments.texture, t),
        clarity = lerp(a.adjustments.clarity, b.adjustments.clarity, t),
        dehaze = lerp(a.adjustments.dehaze, b.adjustments.dehaze, t),
    ),
)

/** A mask that keeps its shapes slides between them; one whose shapes changed swaps halfway. */
private fun interpolateComponents(
    a: List<MaskComponent>,
    b: List<MaskComponent>,
    t: Float,
    late: Boolean,
): List<MaskComponent> {
    if (a.size != b.size || a.zip(b).any { (x, y) -> x.shape != y.shape }) return if (late) b else a
    return a.zip(b) { x, y ->
        y.copy(
            op = if (late) y.op else x.op,
            inverted = if (late) y.inverted else x.inverted,
            linear = lerpLinear(x.linear, y.linear, t),
            radial = lerpRadial(x.radial, y.radial, t),
        )
    }
}

private fun lerpLinear(a: LinearMask?, b: LinearMask?, t: Float): LinearMask? {
    if (a == null || b == null) return b
    return LinearMask(
        x = lerp(a.x, b.x, t),
        y = lerp(a.y, b.y, t),
        angle = lerp(a.angle, b.angle, t),
        width = lerp(a.width, b.width, t),
    )
}

private fun lerpRadial(a: RadialMask?, b: RadialMask?, t: Float): RadialMask? {
    if (a == null || b == null) return b
    return RadialMask(
        x = lerp(a.x, b.x, t),
        y = lerp(a.y, b.y, t),
        radiusX = lerp(a.radiusX, b.radiusX, t),
        radiusY = lerp(a.radiusY, b.radiusY, t),
        angle = lerp(a.angle, b.angle, t),
        roundness = lerp(a.roundness, b.roundness, t),
        feather = lerp(a.feather, b.feather, t),
    )
}

/**
 * Point by point when both curves have as many points; otherwise both are sampled at every point
 * either one has, and the samples move. The in-between curve passes through the blend of the two at
 * those points, which is close to blending the drawn curves and needs no new curve format.
 */
private fun interpolateCurves(from: EditRecipe, to: EditRecipe, t: Float): ToneCurve {
    fun channel(channel: CurveChannel, unchanged: List<CurvePoint>?): List<CurvePoint>? {
        val a = from.editableCurvePoints(channel)
        val b = to.editableCurvePoints(channel)
        if (a == b) return unchanged
        if (a.size == b.size) {
            return a.zip(b) { p, q ->
                CurvePoint(lerp(p.x.toFloat(), q.x.toFloat(), t).roundToInt(), lerp(p.y.toFloat(), q.y.toFloat(), t).roundToInt())
            }.distinctBy { it.x }
        }
        val sampledA = from.curveLut(a)
        val sampledB = to.curveLut(b)
        return (a.map { it.x } + b.map { it.x }).distinct().sorted().map { x ->
            CurvePoint(x, (lerp(sampledA[x], sampledB[x], t) * 255f).roundToInt().coerceIn(0, 255))
        }
    }
    return ToneCurve(
        rgb = channel(CurveChannel.RGB, to.toneCurve.rgb)!!,
        red = channel(CurveChannel.RED, to.toneCurve.red),
        green = channel(CurveChannel.GREEN, to.toneCurve.green),
        blue = channel(CurveChannel.BLUE, to.toneCurve.blue),
    )
}

/** The curve as the render draws it for this recipe's process version: one sample per input level. */
internal fun EditRecipe.curveLut(points: List<CurvePoint>): List<Float> = buildToneCurveLut(
    points = points.map { it.x to it.y },
    interpolation = if (processVersion >= 2) ToneCurveInterpolation.PCHIP else ToneCurveInterpolation.LINEAR,
    flatBeyondEnds = processVersion >= 11,
)
