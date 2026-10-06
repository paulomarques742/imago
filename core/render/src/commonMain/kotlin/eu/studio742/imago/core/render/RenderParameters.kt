package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sign
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape

data class RenderParameters(
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val toneCurveRgb: List<Float> = identityToneCurve(),
    val hslBands: List<HslRenderBand> = neutralHslBands(),
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val colorGrade: ColorGrade = ColorGrade(),
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignetteAmount: Float = 0f,
    val vignetteMidpoint: Float = 50f,
    val vignetteRoundness: Float = 0f,
    val vignetteFeather: Float = 50f,
    val grainAmount: Float = 0f,
    val grainSize: Float = 25f,
    val grainRoughness: Float = 50f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropWidth: Float = 1f,
    val cropHeight: Float = 1f,
    val straighten: Float = 0f,
    val rotation: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    /**
     * The active masks, already resolved from the recipe.
     *
     * It has to be a constructor property. [isNeutral] and [isColorNeutral] work by `data class`
     * equality, and that equality is what stops `BitmapPhotoProcessor` from returning the bitmap
     * untouched: declared in the class body, a photo with masks would pass as neutral and export
     * without them, with no error along the way.
     */
    val masks: List<MaskRenderSpec> = emptyList(),
) {
    init {
        require(toneCurveRgb.size == CURVE_SAMPLE_COUNT)
        require(hslBands.size == HSL_BAND_COUNT)
    }

    val isNeutral: Boolean
        get() = this == RenderParameters()

    val isColorNeutral: Boolean
        get() = copy(
            cropX = 0f,
            cropY = 0f,
            cropWidth = 1f,
            cropHeight = 1f,
            straighten = 0f,
            rotation = 0,
            mirrorH = false,
            mirrorV = false,
        ) == RenderParameters()

    /**
     * True when some mask can change pixels, and only then is the weight field built.
     *
     * The list already arrives filtered by `recipeMasksToSpecs`: a mask that is off, has no components
     * or has every adjustment at neutral never appears here.
     */
    val needsMaskField: Boolean
        get() = masks.isNotEmpty()

    /** True if some mask touches one of these adjustments. */
    private fun anyMask(select: (LocalAdjustments) -> Float): Boolean =
        masks.any { select(it.adjustments) != 0f }

    /**
     * True when step 9 of the pipeline has work. Only then is it worth building the blur pyramid,
     * which is by far the most expensive part of the render.
     *
     * A mask counts as much as the global adjustment: with clarity at zero on the whole photo and at
     * 40 inside a mask, the pyramid is still needed.
     */
    val needsDetailStage: Boolean
        get() = texture != 0f || clarity != 0f || dehaze != 0f ||
            anyMask { it.texture } || anyMask { it.clarity } || anyMask { it.dehaze }

    /** True when step 12 has work. Colour grading is not local. */
    val needsColorGradingStage: Boolean
        get() = colorGrade.isActive

    /** True when step 13 has work. Vignette and grain are not local. */
    val needsEffectsStage: Boolean
        get() = vignetteAmount != 0f || grainAmount != 0f

    val needsClarityBlur: Boolean
        get() = clarity != 0f || texture != 0f || anyMask { it.clarity } || anyMask { it.texture }

    val needsTextureBlur: Boolean
        get() = texture != 0f || anyMask { it.texture }

    val needsDehazeStats: Boolean
        get() = dehaze != 0f || anyMask { it.dehaze }

    /**
     * True when highlights or shadows have work — and only then is the local adaptation mask worth the
     * pass it costs. Without it both curves fall back to the global behaviour, which is the same they
     * would give with a neighbourhood equal to the pixel itself.
     */
    val needsLocalToneMask: Boolean
        get() = highlights != 0f || shadows != 0f ||
            anyMask { it.highlights } || anyMask { it.shadows }
}

/**
 * A mask ready to render: the shapes with the angle already as cosine and sine, and the adjustments
 * that apply where it weighs.
 */
data class MaskRenderSpec(
    val components: List<MaskRenderComponent>,
    val inverted: Boolean = false,
    val adjustments: LocalAdjustments = LocalAdjustments(),
    /**
     * The mask's id in the recipe.
     *
     * The render does not need it, but the editor does: a mask that is off or has no adjustments
     * never reaches this list, and so a mask's index here — which is the channel it takes in the
     * weight field — is not the same as in the recipe. Without this id, the red overlay would light up
     * over the wrong mask as soon as an earlier one was turned off.
     */
    val id: String = "",
)

/**
 * A shape inside a mask, with everything the maths needs and nothing more.
 *
 * The fields of both shapes live in the same type instead of a sealed hierarchy because that is how
 * they reach the shader: a fixed-size uniform block, indexed by [shape]. Keeping the same layout on
 * both sides is what makes parity verifiable.
 */
data class MaskRenderComponent(
    val shape: MaskShape,
    val op: MaskOp = MaskOp.ADD,
    val inverted: Boolean = false,
    val centreX: Float = 0.5f,
    val centreY: Float = 0.5f,
    val cosAngle: Float = 1f,
    val sinAngle: Float = 0f,
    val width: Float = 0.5f,
    val radiusX: Float = 0.3f,
    val radiusY: Float = 0.3f,
    val feather: Float = 50f,
    val roundness: Float = 0f,
)

/** Minimum zoom that keeps the canvas fully covered during a fine rotation. */
fun straightenCoverScale(imageAspect: Float, degrees: Float): Float {
    if (degrees == 0f) return 1f
    val safeAspect = imageAspect.coerceAtLeast(0.0001f)
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = abs(cos(radians)).toFloat()
    val sine = abs(sin(radians)).toFloat()
    return maxOf(
        cosine + sine / safeAspect,
        cosine + sine * safeAspect,
    )
}

/**
 * A step 12 wheel translated into what the pipeline uses: a **luminance-free** chroma shift, with
 * the saturation already applied, and the luminance shift.
 *
 * The hue is converted to chroma here, once per render, and not in the shader nor in the pixel loop.
 * That does two things at once: it takes an HSV conversion off the hot path, and — more importantly —
 * it makes the CPU and the GPU start from exactly the same triple instead of each converting its own
 * angle. It is also what keeps neutral at zero: a wheel with zero saturation has no colour at all,
 * and without this translation a 0° hue saved in a neutral recipe reached the pipeline as red and
 * made a neutral recipe stop being one.
 */
data class ColorGradeRange(
    val chromaRed: Float = 0f,
    val chromaGreen: Float = 0f,
    val chromaBlue: Float = 0f,
    val luminance: Float = 0f,
) {
    val isActive: Boolean
        get() = chromaRed != 0f || chromaGreen != 0f || chromaBlue != 0f || luminance != 0f
}

/**
 * The whole of step 12, ready to render.
 *
 * [blending] and [balance] only gain meaning with one of the three tonal wheels off neutral, and so
 * [colorGradeOf] returns the full neutral when no wheel has work: without that, a recipe with
 * blending changed and no colour chosen stopped being neutral and sent the photo to be reprocessed
 * to give exactly the same result.
 */
data class ColorGrade(
    val shadows: ColorGradeRange = ColorGradeRange(),
    val midtones: ColorGradeRange = ColorGradeRange(),
    val highlights: ColorGradeRange = ColorGradeRange(),
    val global: ColorGradeRange = ColorGradeRange(),
    val blending: Float = 50f,
    val balance: Float = 0f,
) {
    val isActive: Boolean
        get() = shadows.isActive || midtones.isActive || highlights.isActive || global.isActive
}

/** A recipe wheel as the pipeline wants it. See [ColorGradeRange]. */
fun colorGradeRangeOf(hue: Float, saturation: Float, luminance: Float): ColorGradeRange {
    val amount = (saturation / 100f).coerceIn(0f, 1f)
    if (amount == 0f) return ColorGradeRange(luminance = luminance.coerceIn(-100f, 100f))
    val tint = PhotoEffects.hueTint(hue)
    val grey = PhotoEffects.luminance(tint[0], tint[1], tint[2])
    return ColorGradeRange(
        chromaRed = (tint[0] - grey) * amount,
        chromaGreen = (tint[1] - grey) * amount,
        chromaBlue = (tint[2] - grey) * amount,
        luminance = luminance.coerceIn(-100f, 100f),
    )
}

/** The four wheels and the two controls, with neutral reduced to a single value. */
fun colorGradeOf(
    shadows: ColorGradeRange,
    midtones: ColorGradeRange,
    highlights: ColorGradeRange,
    global: ColorGradeRange,
    blending: Float,
    balance: Float,
): ColorGrade {
    val grade = ColorGrade(
        shadows = shadows,
        midtones = midtones,
        highlights = highlights,
        global = global,
        blending = blending.coerceIn(0f, 100f),
        balance = balance.coerceIn(-100f, 100f),
    )
    return if (grade.isActive) grade else ColorGrade()
}

data class HslRenderBand(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
)

const val CURVE_SAMPLE_COUNT = 256
const val HSL_BAND_COUNT = 8

fun identityToneCurve(): List<Float> = List(CURVE_SAMPLE_COUNT) { it / 255f }

fun neutralHslBands(): List<HslRenderBand> = List(HSL_BAND_COUNT) { HslRenderBand() }

enum class ToneCurveInterpolation { LINEAR, PCHIP }

fun buildToneCurveLut(
    points: List<Pair<Int, Int>>,
    interpolation: ToneCurveInterpolation = ToneCurveInterpolation.PCHIP,
): List<Float> {
    val normalized = (points + listOf(0 to 0, 255 to 255))
        .map { (x, y) -> x.coerceIn(0, 255) to y.coerceIn(0, 255) }
        .distinctBy { it.first }
        .sortedBy { it.first }
    return when (interpolation) {
        ToneCurveInterpolation.LINEAR -> buildLinearToneCurveLut(normalized)
        ToneCurveInterpolation.PCHIP -> buildPchipToneCurveLut(normalized)
    }
}

private fun buildLinearToneCurveLut(points: List<Pair<Int, Int>>): List<Float> =
    List(CURVE_SAMPLE_COUNT) { sample ->
        val rightIndex = points.indexOfFirst { it.first >= sample }.takeIf { it >= 0 } ?: points.lastIndex
        val leftIndex = (rightIndex - 1).coerceAtLeast(0)
        val left = points[leftIndex]
        val right = points[rightIndex]
        if (left.first == right.first) {
            left.second / 255f
        } else {
            val amount = (sample - left.first).toFloat() / (right.first - left.first)
            (left.second + (right.second - left.second) * amount) / 255f
        }
    }

private fun buildPchipToneCurveLut(points: List<Pair<Int, Int>>): List<Float> {
    if (points.size <= 2) return buildLinearToneCurveLut(points)
    val x = DoubleArray(points.size) { points[it].first.toDouble() }
    val y = DoubleArray(points.size) { points[it].second.toDouble() }
    val intervals = DoubleArray(points.lastIndex) { x[it + 1] - x[it] }
    val slopes = DoubleArray(points.lastIndex) { (y[it + 1] - y[it]) / intervals[it] }
    val derivatives = DoubleArray(points.size)

    derivatives[0] = endpointDerivative(intervals[0], intervals[1], slopes[0], slopes[1])
    derivatives[points.lastIndex] = endpointDerivative(
        intervals[intervals.lastIndex],
        intervals[intervals.lastIndex - 1],
        slopes[slopes.lastIndex],
        slopes[slopes.lastIndex - 1],
    )
    for (index in 1 until points.lastIndex) {
        val before = slopes[index - 1]
        val after = slopes[index]
        derivatives[index] = if (before == 0.0 || after == 0.0 || sign(before) != sign(after)) {
            0.0
        } else {
            val weightBefore = 2.0 * intervals[index] + intervals[index - 1]
            val weightAfter = intervals[index] + 2.0 * intervals[index - 1]
            (weightBefore + weightAfter) / (weightBefore / before + weightAfter / after)
        }
    }

    return List(CURVE_SAMPLE_COUNT) { sample ->
        if (sample == 255) return@List y.last().toFloat() / 255f
        val segment = points.indexOfLast { it.first <= sample }.coerceIn(0, points.lastIndex - 1)
        val width = intervals[segment]
        val amount = (sample - x[segment]) / width
        val amount2 = amount * amount
        val amount3 = amount2 * amount
        val value =
            (2.0 * amount3 - 3.0 * amount2 + 1.0) * y[segment] +
                (amount3 - 2.0 * amount2 + amount) * width * derivatives[segment] +
                (-2.0 * amount3 + 3.0 * amount2) * y[segment + 1] +
                (amount3 - amount2) * width * derivatives[segment + 1]
        value.coerceIn(0.0, 255.0).toFloat() / 255f
    }
}

private fun endpointDerivative(
    firstInterval: Double,
    secondInterval: Double,
    firstSlope: Double,
    secondSlope: Double,
): Double {
    val candidate = ((2.0 * firstInterval + secondInterval) * firstSlope - firstInterval * secondSlope) /
        (firstInterval + secondInterval)
    return when {
        sign(candidate) != sign(firstSlope) -> 0.0
        sign(firstSlope) != sign(secondSlope) && abs(candidate) > abs(3.0 * firstSlope) -> 3.0 * firstSlope
        else -> candidate
    }
}
