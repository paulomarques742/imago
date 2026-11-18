package eu.studio742.imago.core.ml

import kotlin.math.roundToInt

/**
 * A model's raw output, quantised to 8 bits: the part of a generated mask that is stored in the recipe.
 *
 * It is never recomputed for the photo it was made for, so a new model or runtime cannot change an edit.
 * Refinement to the photo's resolution happens later, from this and the original.
 */
class GeneratedMask(val model: MaskModel, val width: Int, val height: Int, val weights: ByteArray) {
    init {
        require(weights.size == width * height) { "Weights do not match ${width}x$height" }
    }

    /** The weight at (x, y), from 0 to 1. */
    fun weight(x: Int, y: Int): Float = (weights[y * width + x].toInt() and 0xff) / 255f
}

/** Turns a model's float output into a [GeneratedMask]; the subject model's saliency is stretched to 0..1 first. */
internal fun generatedMask(model: MaskModel, width: Int, height: Int, output: FloatArray): GeneratedMask {
    var low = 0f
    var high = 1f
    if (model == MaskModel.SUBJECT) {
        // U²-Net's saliency is relative to the photo; without the stretch a quiet subject would come out faint.
        low = output.min()
        high = output.max()
    }
    val span = (high - low).coerceAtLeast(1e-12f)
    val bytes = ByteArray(output.size) { i -> (((output[i] - low) / span).coerceIn(0f, 1f) * 255f).roundToInt().toByte() }
    return GeneratedMask(model, width, height, bytes)
}
