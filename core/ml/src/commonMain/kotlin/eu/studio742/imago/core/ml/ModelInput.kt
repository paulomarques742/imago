package eu.studio742.imago.core.ml

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** An oriented photo as packed ARGB, row by row. */
class RgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "Pixels do not match ${width}x$height" }
    }
}

/**
 * The model input as NCHW floats, normalised with the ImageNet mean and deviation both models were trained with.
 *
 * Resampling is an exact box filter written here rather than the platform's scaler, so Android and desktop feed
 * the model the same numbers and produce the same mask.
 */
internal fun modelInput(image: RgbImage, width: Int, height: Int): FloatArray {
    val columns = boxWeights(image.width, width)
    val rows = boxWeights(image.height, height)
    val out = FloatArray(3 * width * height)
    val plane = width * height
    val cache = LinkedHashMap<Int, DoubleArray>()
    for (y in 0 until height) {
        val acc = DoubleArray(3 * width)
        val weights = rows[y]
        for (k in weights.weights.indices) {
            val row = cache.getOrPut(weights.first + k) { resampleRow(image, weights.first + k, columns, width) }
            val w = weights.weights[k]
            for (i in acc.indices) acc[i] += w * row[i]
        }
        // A source row feeds at most the next output row too; older ones are done with.
        cache.keys.retainAll { it >= weights.first + weights.weights.size - 1 }
        for (x in 0 until width) for (c in 0..2) {
            out[c * plane + y * width + x] = ((acc[3 * x + c] - MEAN[c]) / STD[c]).toFloat()
        }
    }
    return out
}

private fun resampleRow(image: RgbImage, y: Int, columns: Array<BoxWeights>, width: Int): DoubleArray {
    val row = DoubleArray(3 * width)
    val base = y * image.width
    for (x in 0 until width) {
        val weights = columns[x]
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (k in weights.weights.indices) {
            val p = image.argb[base + weights.first + k]
            val w = weights.weights[k]
            r += w * ((p shr 16) and 0xff)
            g += w * ((p shr 8) and 0xff)
            b += w * (p and 0xff)
        }
        row[3 * x] = r / 255.0
        row[3 * x + 1] = g / 255.0
        row[3 * x + 2] = b / 255.0
    }
    return row
}

internal class BoxWeights(val first: Int, val weights: DoubleArray)

/** Output sample i averages the source over [i·s, (i+1)·s), with partial pixels weighted by their overlap. */
internal fun boxWeights(source: Int, target: Int): Array<BoxWeights> {
    val scale = source.toDouble() / target
    return Array(target) { i ->
        val start = i * scale
        val end = (i + 1) * scale
        val first = floor(start).toInt()
        val last = min(source, ceil(end).toInt())
        val weights = DoubleArray(last - first) { k -> min(end, first + k + 1.0) - maxOf(start, first + k.toDouble()) }
        val sum = weights.sum()
        for (k in weights.indices) weights[k] /= sum
        BoxWeights(first, weights)
    }
}

private val MEAN = doubleArrayOf(0.485, 0.456, 0.406)
private val STD = doubleArrayOf(0.229, 0.224, 0.225)
