package eu.studio742.imago.core.ml

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A segmentation model the editor can run, with the input it expects and how its output becomes a mask.
 *
 * [id] and [version] go into the recipe next to the mask a model produced, so a stored mask can always be
 * traced to the weights that made it.
 */
enum class MaskModel(val id: String, val version: Int, val fileName: String) {
    /** U²-Net's small variant (u2netp): the most salient object, at a fixed 320 × 320. */
    SUBJECT("u2netp", 1, "u2netp.onnx"),

    /**
     * PP-MobileSeg Base trained on ADE20K, cut before its last upsampling and ending in the sky
     * class's softmax: the mask comes out at 1/8 of the input.
     */
    SKY("ppmobileseg-base-ade20k-sky", 1, "sky_base.onnx");

    /** The model's input for a photo of this size: fixed for [SUBJECT], aspect-preserving for [SKY]. */
    fun inputSize(width: Int, height: Int): Pair<Int, Int> = when (this) {
        SUBJECT -> 320 to 320
        SKY -> {
            // The short side at 768 measured clearly better than 512 on skies split by an object.
            val scale = SKY_SHORT_SIDE.toDouble() / min(width, height)
            snap(width * scale) to snap(height * scale)
        }
    }

    private fun snap(side: Double) = max(32, (side / 32).roundToInt() * 32)
}

private const val SKY_SHORT_SIDE = 768
