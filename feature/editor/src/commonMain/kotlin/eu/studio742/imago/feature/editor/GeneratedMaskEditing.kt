package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.ml.GeneratedMask
import eu.studio742.imago.core.ml.MaskModel
import eu.studio742.imago.core.ml.ModelSource
import eu.studio742.imago.core.ml.RgbImage
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.GeneratedWeights
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.render.PixelSurface

/**
 * Where this build gets the mask models from, or null where it offers no generated masks: a release build
 * for now, or a device the runtime does not run on. With null, Subject, Sky and Background are left out.
 */
class GeneratedMaskTools(val source: ModelSource?)

/** The desktop's [GeneratedMaskTools], given by the app; Android injects them. */
val LocalGeneratedMaskTools = androidx.compose.runtime.staticCompositionLocalOf { GeneratedMaskTools(null) }

/** The model that finds this shape, or null for a shape drawn by hand. Background is the subject's model, inverted. */
val MaskShape.maskModel: MaskModel?
    get() = when (this) {
        MaskShape.SUBJECT, MaskShape.BACKGROUND -> MaskModel.SUBJECT
        MaskShape.SKY -> MaskModel.SKY
        MaskShape.LINEAR, MaskShape.RADIAL, MaskShape.BRUSH, MaskShape.LUMINANCE, MaskShape.COLOR -> null
    }

/** The models this recipe still has to run: generated shapes that came from another photo, or were just made. */
val EditRecipe.missingMaskModels: Set<MaskModel>
    get() = masks.flatMap { it.components }.filter { it.generated == null }.mapNotNullTo(mutableSetOf()) { it.shape.maskModel }

/** Every generated shape still without weights takes those its model found on this photo. */
fun EditRecipe.withGeneratedWeights(found: Map<MaskModel, GeneratedWeights>): EditRecipe {
    if (found.isEmpty() || missingMaskModels.isEmpty()) return this
    return copy(
        masks = masks.map { mask ->
            mask.copy(
                components = mask.components.map { component ->
                    val weights = component.shape.maskModel?.let(found::get)
                    if (component.generated == null && weights != null) component.copy(generated = weights) else component
                },
            )
        },
    )
}

/** What the model returned, as the recipe keeps it. */
fun GeneratedMask.toWeights(): GeneratedWeights = GeneratedWeights.of(model.id, model.version, width, height, weights)

/** The photo, in image space, as the models read it. */
fun PixelSurface.toRgbImage(): RgbImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RgbImage(width, height, pixels)
}

/** A generated mask on its way: [downloaded] is the model's download so far, or null once the model is running. */
data class MaskGeneration(val shape: MaskShape, val downloaded: Float?)
