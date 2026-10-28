package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.BRUSH_MASKS_PROCESS_VERSION
import eu.studio742.imago.core.model.COMBINED_MASKS_PROCESS_VERSION
import eu.studio742.imago.core.model.MAX_BRUSH_COMPONENTS
import eu.studio742.imago.core.model.MAX_COLOR_SAMPLES
import eu.studio742.imago.core.model.RANGE_MASKS_PROCESS_VERSION
import eu.studio742.imago.core.model.MAX_MASK_COMPONENTS
import eu.studio742.imago.core.model.maxLocalMasks
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.UPRIGHT_OFF
import eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME
import eu.studio742.imago.core.model.RAW_RENDERING_HEADROOM
import eu.studio742.imago.core.model.WIDE_HIGHLIGHTS_PROCESS_VERSION
import eu.studio742.imago.core.model.RAW_RENDERING_PROFILED
import eu.studio742.imago.core.model.rawExposure
import eu.studio742.imago.core.model.rawRendering
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

private fun EditRecipe.pointCurveLut(points: List<CurvePoint>) = buildToneCurveLut(
    points = points.map { it.x to it.y },
    interpolation = if (processVersion >= 2) ToneCurveInterpolation.PCHIP else ToneCurveInterpolation.LINEAR,
    flatBeyondEnds = processVersion >= 11,
)

/** The channel curves only exist from process 12; a missing one is the identity. */
private fun EditRecipe.channelCurveLut(points: List<CurvePoint>?) =
    if (processVersion >= 12 && points != null) pointCurveLut(points) else identityToneCurve()

/**
 * The recipe translated into what the render pipeline understands.
 *
 * It lives here, and not in the editor, because it stopped being only the editor's: the library grid
 * and the detail show the photo already processed, and a second translation next to this one would be
 * a second way for the same recipe to look different depending on the screen.
 *
 * The `processVersion`s keep the promise made to whoever edited before each step existed: a recipe
 * saved in version 5 did not know about crop, and reinterpreting it with the default crop would change
 * a photo the user considered finished.
 *
 * @param rawRendering how the photo's RAW development is shown: the recipe's own unless a caller
 *   renders a recipe from elsewhere — a preset's preview — on a photo developed its own way.
 * @param rawExposure the development's own exposure, likewise; or, for a RAW not yet developed by the
 *   editor, the one [CameraExposureMatch] finds.
 * @param showMask a mask the editor has open: it reaches the render even with no adjustments, so that
 *   its red overlay shows while it is being drawn — a brush has no outline, and without this nothing
 *   at all would show where it was painting. With no adjustments it changes no pixel.
 */
fun EditRecipe.toRenderParameters(
    showMask: String? = null,
    rawRendering: Int = rawRendering(),
    rawExposure: Float = rawExposure(),
) = RenderParameters(
    temperature = whiteBalance.temp,
    tint = whiteBalance.tint,
    lightWhiteBalance = processVersion >= 13,
    rawProfile = when {
        rawRendering < RAW_RENDERING_PROFILED -> RawProfile.NONE
        profile == RAW_PROFILE_MONOCHROME -> RawProfile.MONOCHROME
        else -> RawProfile.COLOR
    },
    rawExposure = if (rawRendering < RAW_RENDERING_PROFILED) 0f else rawExposure,
    highlightCeiling = if (rawRendering >= RAW_RENDERING_HEADROOM) RawProfile.HEADROOM_CEILING else 1f,
    wideHighlights = processVersion >= WIDE_HIGHLIGHTS_PROCESS_VERSION,
    exposure = tone.exposure,
    contrast = tone.contrast,
    highlights = tone.highlights,
    shadows = tone.shadows,
    whites = tone.whites,
    blacks = tone.blacks,
    toneCurveRgb = pointCurveLut(toneCurve.rgb),
    toneCurveRed = channelCurveLut(toneCurve.red),
    toneCurveGreen = channelCurveLut(toneCurve.green),
    toneCurveBlue = channelCurveLut(toneCurve.blue),
    hslBands = listOf(hsl.red, hsl.orange, hsl.yellow, hsl.green, hsl.aqua, hsl.blue, hsl.purple, hsl.magenta)
        .map { HslRenderBand(it.hue, it.saturation, it.luminance) },
    vibrance = presence.vibrance,
    saturation = presence.saturation,
    colorGrade = if (processVersion >= 9) colorGrading.toRenderGrade() else ColorGrade(),
    texture = presence.texture,
    clarity = presence.clarity,
    dehaze = presence.dehaze,
    vignetteAmount = effects.vignetteAmount,
    vignetteMidpoint = effects.vignetteMidpoint,
    vignetteRoundness = effects.vignetteRoundness,
    vignetteFeather = effects.vignetteFeather,
    grainAmount = effects.grainAmount,
    grainSize = effects.grainSize,
    grainRoughness = effects.grainRoughness,
    cropX = if (processVersion >= 6) normalizedCropOrigin(geometry.cropRect.x) else 0f,
    cropY = if (processVersion >= 6) normalizedCropOrigin(geometry.cropRect.y) else 0f,
    cropWidth = if (processVersion >= 6) normalizedCropSize(geometry.cropRect.x, geometry.cropRect.w) else 1f,
    cropHeight = if (processVersion >= 6) normalizedCropSize(geometry.cropRect.y, geometry.cropRect.h) else 1f,
    straighten = if (processVersion >= 7) geometry.straighten.coerceIn(-45f, 45f) else 0f,
    rotation = if (processVersion >= 4) geometry.rotation else 0,
    mirrorH = processVersion >= 4 && geometry.mirrorH,
    mirrorV = processVersion >= 4 && geometry.mirrorV,
    perspective = when {
        processVersion >= 14 -> geometry.perspective.toRenderPerspective()
        processVersion >= 10 -> geometry.perspective.toRenderPerspective().copy(distortion = 0f)
        else -> PerspectiveParameters()
    },
    masks = if (processVersion >= 8) masks.toRenderSpecs(processVersion, showMask) else emptyList(),
)

fun eu.studio742.imago.core.model.Perspective.toRenderPerspective() = PerspectiveParameters(
    vertical = vertical.coerceIn(-100f, 100f),
    horizontal = horizontal.coerceIn(-100f, 100f),
    aspect = aspect.coerceIn(-100f, 100f),
    scale = scale.coerceIn(-50f, 100f),
    offsetX = offsetX.coerceIn(-100f, 100f),
    offsetY = offsetY.coerceIn(-100f, 100f),
    constrainCrop = constrainCrop,
    // A mode that is off finds nothing, whatever angles were left behind.
    uprightRoll = if (upright == UPRIGHT_OFF) 0f else uprightRoll.coerceIn(-45f, 45f),
    uprightPitch = if (upright == UPRIGHT_OFF) 0f else uprightPitch.coerceIn(-45f, 45f),
    uprightYaw = if (upright == UPRIGHT_OFF) 0f else uprightYaw.coerceIn(-45f, 45f),
    distortion = distortion.coerceIn(-100f, 100f),
)

/** The four wheels of step 12, with the hue already converted to chroma. See [ColorGradeRange]. */
private fun eu.studio742.imago.core.model.ColorGrading.toRenderGrade(): ColorGrade = colorGradeOf(
    shadows = shadows.toRenderRange(),
    midtones = midtones.toRenderRange(),
    highlights = highlights.toRenderRange(),
    global = global.toRenderRange(),
    blending = blending,
    balance = balance,
)

private fun eu.studio742.imago.core.model.ColorWheel.toRenderRange(): ColorGradeRange =
    colorGradeRangeOf(hue = hue, saturation = saturation, luminance = luminance)

/**
 * The masks that really have work to do, ready to render.
 *
 * The filtering happens here and not in the render because this is where it is known what an empty
 * mask is: off, without components, or with all thirteen adjustments at neutral. One of those
 * reaching the pipeline would cost a channel of the weight field and a pass that changed not a single
 * pixel.
 *
 * The angle is converted to cosine and sine already here, once per component instead of once per
 * texel — and, more than that, so that the CPU and the GPU start from exactly the same two numbers
 * instead of each doing its own trigonometry.
 *
 * The Amount is applied here too, to the parameters: the shader and the pixel loop never learn it
 * exists. The component budget is also enforced here and not in the GPU's uniform upload, so that a
 * recipe past it loses the same components on both sides.
 */
private fun List<LocalMask>.toRenderSpecs(processVersion: Int, showMask: String?): List<MaskRenderSpec> {
    val combined = processVersion >= COMBINED_MASKS_PROCESS_VERSION
    var budget = MAX_MASK_COMPONENTS
    var brushes = 0
    return asSequence()
        .map { mask -> mask to if (combined) mask.effectiveAdjustments else mask.adjustments }
        .filter { (mask, adjustments) ->
            mask.enabled && mask.components.isNotEmpty() && (!adjustments.isNeutral || mask.id == showMask)
        }
        .take(maxLocalMasks(processVersion))
        .map { (mask, adjustments) ->
            val components = mask.components
                .mapNotNull { it.toRenderComponent(processVersion) }
                .take(budget)
                .mapNotNull { component ->
                    when {
                        !component.hasRasterContent -> component
                        brushes < MAX_BRUSH_COMPONENTS -> component.copy(brushIndex = brushes++)
                        else -> null
                    }
                }
            budget -= components.size
            MaskRenderSpec(
                components = components,
                inverted = mask.inverted,
                adjustments = adjustments,
                id = mask.id,
            )
        }
        .filter { it.components.isNotEmpty() }
        .toList()
}

/**
 * A component without its shape's payload describes no region and is discarded, and so is a brush or a
 * range in a recipe from before they existed.
 */
private fun MaskComponent.toRenderComponent(processVersion: Int): MaskRenderComponent? = when (shape) {
    MaskShape.LUMINANCE -> luminance?.takeIf { processVersion >= RANGE_MASKS_PROCESS_VERSION }?.let {
        MaskRenderComponent(shape = MaskShape.LUMINANCE, op = op, inverted = inverted, luminance = it)
    }
    MaskShape.COLOR -> color?.takeIf { processVersion >= RANGE_MASKS_PROCESS_VERSION }?.let {
        MaskRenderComponent(
            shape = MaskShape.COLOR,
            op = op,
            inverted = inverted,
            colorRange = it.copy(samples = it.samples.take(MAX_COLOR_SAMPLES)),
        )
    }
    MaskShape.BRUSH -> brush?.takeIf { processVersion >= BRUSH_MASKS_PROCESS_VERSION }?.let {
        MaskRenderComponent(
            shape = MaskShape.BRUSH,
            op = op,
            inverted = inverted,
            strokes = it.strokes.filter { stroke -> stroke.points.size >= 2 && stroke.radius > 0f },
        )
    }
    MaskShape.LINEAR -> linear?.let {
        val radians = Math.toRadians(it.angle.toDouble())
        MaskRenderComponent(
            shape = MaskShape.LINEAR,
            op = op,
            inverted = inverted,
            centreX = it.x,
            centreY = it.y,
            cosAngle = cos(radians).toFloat(),
            sinAngle = sin(radians).toFloat(),
            width = it.width.coerceAtLeast(0f),
        )
    }
    MaskShape.RADIAL -> radial?.let {
        val radians = Math.toRadians(it.angle.toDouble())
        MaskRenderComponent(
            shape = MaskShape.RADIAL,
            op = op,
            inverted = inverted,
            centreX = it.x,
            centreY = it.y,
            cosAngle = cos(radians).toFloat(),
            sinAngle = sin(radians).toFloat(),
            radiusX = it.radiusX.coerceAtLeast(0.0001f),
            radiusY = it.radiusY.coerceAtLeast(0.0001f),
            feather = it.feather.coerceIn(0f, 100f),
            roundness = it.roundness.coerceIn(0f, 100f),
        )
    }
}

/** The part of the geometry this recipe's process version already knew. */
fun EditRecipe.activeGeometry(): Geometry = when {
    processVersion >= 10 -> geometry
    processVersion >= 7 -> geometry.copy(perspective = eu.studio742.imago.core.model.Perspective())
    processVersion >= 6 -> geometry.copy(straighten = 0f)
    processVersion >= 4 -> geometry.copy(cropRect = CropRect(), straighten = 0f, aspectLock = null)
    else -> Geometry()
}

/** Whether the recipe changes anything at all. A neutral one justifies processing no thumbnail. */
fun EditRecipe.changesTheImage(): Boolean = !toRenderParameters().isNeutral

/**
 * The aspect ratio the photo ends up with after the recipe's geometry.
 *
 * The grid reserves the tile's space before the thumbnail arrives; without this, a rotated or cropped
 * photo went into a box with the original's shape and appeared cut off.
 */
fun EditRecipe.editedAspectRatio(sourceWidth: Long, sourceHeight: Long): Float? {
    if (sourceWidth <= 0L || sourceHeight <= 0L) return null
    val geometry = activeGeometry()
    val quarterTurn = (((geometry.rotation % 360) + 360) % 360) % 180 != 0
    val width = (if (quarterTurn) sourceHeight else sourceWidth) * geometry.cropRect.w
    val height = (if (quarterTurn) sourceWidth else sourceHeight) * geometry.cropRect.h
    return (width / height).toFloat().takeIf { it.isFinite() && it > 0f }
}

internal fun normalizedCropOrigin(value: Float): Float = value.coerceIn(0f, 0.9999f)

internal fun normalizedCropSize(origin: Float, size: Float): Float =
    size.coerceIn(0.0001f, 1f - normalizedCropOrigin(origin))
