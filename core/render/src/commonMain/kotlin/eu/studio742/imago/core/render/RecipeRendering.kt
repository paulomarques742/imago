package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

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
 */
fun EditRecipe.toRenderParameters() = RenderParameters(
    temperature = whiteBalance.temp,
    tint = whiteBalance.tint,
    exposure = tone.exposure,
    contrast = tone.contrast,
    highlights = tone.highlights,
    shadows = tone.shadows,
    whites = tone.whites,
    blacks = tone.blacks,
    toneCurveRgb = buildToneCurveLut(
        points = toneCurve.rgb.map { it.x to it.y },
        interpolation = if (processVersion >= 2) ToneCurveInterpolation.PCHIP else ToneCurveInterpolation.LINEAR,
    ),
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
    masks = if (processVersion >= 8) masks.toRenderSpecs() else emptyList(),
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
 */
private fun List<LocalMask>.toRenderSpecs(): List<MaskRenderSpec> = asSequence()
    .filter { it.enabled && it.components.isNotEmpty() && !it.adjustments.isNeutral }
    .take(MAX_LOCAL_MASKS)
    .map { mask ->
        MaskRenderSpec(
            components = mask.components.mapNotNull { it.toRenderComponent() },
            inverted = mask.inverted,
            adjustments = mask.adjustments,
            id = mask.id,
        )
    }
    .filter { it.components.isNotEmpty() }
    .toList()

/** A component without its shape's payload describes no region and is discarded. */
private fun MaskComponent.toRenderComponent(): MaskRenderComponent? = when (shape) {
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
    processVersion >= 7 -> geometry
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
