package eu.studio742.imago.feature.editor

import kotlin.math.roundToInt
import eu.studio742.imago.core.model.ColorRange
import eu.studio742.imago.core.model.ColorSample
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LuminanceRange
import eu.studio742.imago.core.model.MAX_COLOR_SAMPLES
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape

/**
 * The range masks' mutations on the recipe: Lightroom's Luminance Range and Color Range.
 *
 * Pure functions like the rest of [MaskEditing]'s, and in a file of their own so that they are tested
 * with the engine alone. Each one returns the recipe intact when the component is not the range it
 * expects — a tap that arrives as the selection changes must not write a sample into a radial.
 */

/** The settings of a range a slider moves. */
enum class RangeSetting(val range: ClosedFloatingPointRange<Float>, val neutral: Float) {
    REFINE(0f..100f, 50f),
    SMOOTHNESS(0f..100f, 50f),
}

/**
 * A Color Range sample taken on the photo. With [add], it joins the others — Lightroom's Shift — up to
 * [MAX_COLOR_SAMPLES], after which the oldest gives way; without it, it replaces them all, as a plain
 * click does.
 */
fun EditRecipe.withColorSample(maskId: String, component: Int, sample: ColorSample, add: Boolean): EditRecipe =
    withRange(maskId, component, MaskShape.COLOR) {
        val range = it.color ?: ColorRange()
        val samples = (if (add) range.samples + sample else listOf(sample)).takeLast(MAX_COLOR_SAMPLES)
        it.copy(color = range.copy(samples = samples.map { kept -> kept.rounded() }))
    }

/** Lightroom's Alt-click on a sample: it goes, and the others stay. */
fun EditRecipe.withoutColorSample(maskId: String, component: Int, index: Int): EditRecipe =
    withRange(maskId, component, MaskShape.COLOR) {
        val range = it.color ?: return@withRange it
        if (index !in range.samples.indices) return@withRange it
        it.copy(color = range.copy(samples = range.samples.filterIndexed { position, _ -> position != index }))
    }

/** The range a Luminance Range's slider or eyedropper set; the ends may arrive in either order. */
fun EditRecipe.withLuminanceRange(maskId: String, component: Int, low: Float, high: Float): EditRecipe =
    withRange(maskId, component, MaskShape.LUMINANCE) {
        val range = it.luminance ?: LuminanceRange()
        it.copy(
            luminance = range.copy(
                low = roundedLightness(minOf(low, high)),
                high = roundedLightness(maxOf(low, high)),
            ),
        )
    }

/** Refine on a Color Range, Smoothness on a Luminance Range; the other pairing changes nothing. */
fun EditRecipe.withRangeSetting(maskId: String, component: Int, setting: RangeSetting, value: Float): EditRecipe {
    val clamped = value.coerceIn(setting.range).roundToInt().toFloat()
    return when (setting) {
        RangeSetting.REFINE -> withRange(maskId, component, MaskShape.COLOR) {
            it.copy(color = (it.color ?: ColorRange()).copy(refine = clamped))
        }
        RangeSetting.SMOOTHNESS -> withRange(maskId, component, MaskShape.LUMINANCE) {
            it.copy(luminance = (it.luminance ?: LuminanceRange()).copy(smoothness = clamped))
        }
    }
}

/** A range setting's value, or its neutral when the component is not that range. */
fun EditRecipe.rangeSettingValue(maskId: String, component: Int, setting: RangeSetting): Float {
    val shape = rangeComponent(maskId, component)
    return when (setting) {
        RangeSetting.REFINE -> shape?.color?.refine
        RangeSetting.SMOOTHNESS -> shape?.luminance?.smoothness
    } ?: setting.neutral
}

/** The range component at [component] of the mask, or null when that is not a range. */
fun EditRecipe.rangeComponent(maskId: String, component: Int): MaskComponent? =
    masks.firstOrNull { it.id == maskId }?.components?.getOrNull(component)?.takeIf { it.shape.isRange }

private fun EditRecipe.withRange(
    maskId: String,
    component: Int,
    shape: MaskShape,
    transform: (MaskComponent) -> MaskComponent,
): EditRecipe {
    val mask = masks.firstOrNull { it.id == maskId } ?: return this
    val current = mask.components.getOrNull(component)?.takeIf { it.shape == shape } ?: return this
    val updated = transform(current)
    if (updated == current) return this
    return copy(
        masks = masks.map { candidate ->
            if (candidate.id != maskId) {
                candidate
            } else {
                candidate.copy(components = candidate.components.mapIndexed { index, it -> if (index == component) updated else it })
            }
        },
    )
}

/** Four decimals of image and five of colour: the JSON stays short and nothing seen changes. */
private fun ColorSample.rounded() = copy(
    x = roundedPosition(x),
    y = roundedPosition(y),
    width = roundedPosition(width),
    height = roundedPosition(height),
    l = roundedColour(l),
    a = roundedColour(a),
    b = roundedColour(b),
    spread = roundedColour(spread),
)

private fun roundedPosition(value: Float): Float = kotlin.math.round(value * 10_000f) / 10_000f

private fun roundedColour(value: Float): Float = kotlin.math.round(value * 100_000f) / 100_000f

private fun roundedLightness(value: Float): Float = (value.coerceIn(0f, 100f) * 10f).roundToInt() / 10f
