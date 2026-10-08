package eu.studio742.imago.core.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A straight edge found in the photo, in the original image's normalised coordinates, with its
 * length in the analysed image's pixels.
 */
data class LineSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val length: Float)

/**
 * The straight edges of a photo, for the automatic Upright modes.
 *
 * It follows the idea of the LSD detector (Grompone von Gioi et al., "LSD: a Line Segment Detector",
 * IPOL 2012), written here from the paper's description and simplified: pixels whose level line —
 * the direction along the edge, across the gradient — points the same way within a tolerance are
 * grown into regions from the strongest gradient down, and a region long and thin enough, filled
 * enough by its own pixels, is a segment. The paper's a-contrario validation is left out: the
 * Upright solver weighs segments by their length and discounts the ones that disagree, which does
 * the job the validation would do for this use.
 *
 * [source] is expected already reduced: a thousand pixels on the long side see every edge a building
 * has, and keep the work to a fraction of a second.
 */
fun detectLineSegments(source: PixelBuffer): List<LineSegment> {
    val width = source.width
    val height = source.height
    if (width < 8 || height < 8) return emptyList()
    val luminance = FloatArray(width * height)
    for (index in luminance.indices) {
        val pixel = source.pixels[index]
        luminance[index] = 0.299f * (pixel ushr 16 and 255) + 0.587f * (pixel ushr 8 and 255) + 0.114f * (pixel and 255)
    }

    blur(luminance, width, height)

    // The gradient on 2×2 cells, as LSD computes it: it is centred between pixels and sees thin edges
    // the 3×3 operators blur.
    val magnitude = FloatArray(width * height)
    val angle = FloatArray(width * height)
    for (y in 0 until height - 1) {
        for (x in 0 until width - 1) {
            val i = y * width + x
            val a = luminance[i]
            val b = luminance[i + 1]
            val c = luminance[i + width]
            val d = luminance[i + width + 1]
            val gx = (b + d - a - c) / 2f
            val gy = (c + d - a - b) / 2f
            val m = hypot(gx, gy)
            magnitude[i] = m
            angle[i] = atan2(gx, -gy)
        }
    }

    // The strongest first: a region grown from a strong pixel takes the weak ones along its edge, and
    // not the other way round. Buckets, not a sort, as the paper does.
    val maxMagnitude = magnitude.maxOrNull() ?: 0f
    if (maxMagnitude <= GRADIENT_THRESHOLD) return emptyList()
    val buckets = Array(BUCKETS) { IntArrayList() }
    for (i in magnitude.indices) {
        if (magnitude[i] <= GRADIENT_THRESHOLD) continue
        val bucket = ((magnitude[i] / maxMagnitude) * (BUCKETS - 1)).toInt()
        buckets[BUCKETS - 1 - bucket].add(i)
    }

    val used = BooleanArray(width * height)
    val region = IntArrayList()
    val minLength = max(MIN_LENGTH_PIXELS, MIN_LENGTH_FRACTION * max(width, height))
    val segments = mutableListOf<LineSegment>()
    for (bucket in buckets) {
        for (k in 0 until bucket.size) {
            val seed = bucket[k]
            if (used[seed]) continue
            region.clear()
            region.add(seed)
            used[seed] = true
            var sumX = cos(angle[seed])
            var sumY = sin(angle[seed])
            var regionAngle = angle[seed]
            var cursor = 0
            while (cursor < region.size) {
                val pixel = region[cursor++]
                val px = pixel % width
                val py = pixel / width
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx
                        val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= width - 1 || ny >= height - 1) continue
                        val neighbour = ny * width + nx
                        if (used[neighbour] || magnitude[neighbour] <= GRADIENT_THRESHOLD) continue
                        if (angleDifference(angle[neighbour], regionAngle) > ANGLE_TOLERANCE) continue
                        used[neighbour] = true
                        region.add(neighbour)
                        sumX += cos(angle[neighbour])
                        sumY += sin(angle[neighbour])
                        regionAngle = atan2(sumY, sumX)
                    }
                }
            }
            if (region.size < MIN_REGION_PIXELS) continue
            segmentOf(region, width, magnitude, minLength)?.let { segment ->
                segments += LineSegment(
                    x1 = (segment[0] + 0.5f) / width,
                    y1 = (segment[1] + 0.5f) / height,
                    x2 = (segment[2] + 0.5f) / width,
                    y2 = (segment[3] + 0.5f) / height,
                    length = segment[4],
                )
            }
        }
    }
    return segments
}

/**
 * A light blur, [1 2 1] across and down. LSD smooths before the gradient for the same reason: on a
 * slanted edge without anti-aliasing, or a JPEG's blocks, the raw gradient turns with every step of
 * the staircase, and the edge breaks into short pieces that are all exactly upright.
 */
private fun blur(values: FloatArray, width: Int, height: Int) {
    val row = FloatArray(width)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            val left = values[offset + maxOf(x - 1, 0)]
            val right = values[offset + minOf(x + 1, width - 1)]
            row[x] = (left + 2 * values[offset + x] + right) / 4f
        }
        row.copyInto(values, offset)
    }
    val column = FloatArray(height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            val up = values[maxOf(y - 1, 0) * width + x]
            val down = values[minOf(y + 1, height - 1) * width + x]
            column[y] = (up + 2 * values[y * width + x] + down) / 4f
        }
        for (y in 0 until height) values[y * width + x] = column[y]
    }
}

/**
 * The rectangle a region fills: its axis from the gradient-weighted second moments, its length and
 * width from how far the pixels reach along and across it. Null when it is too short, too wide for
 * its length, or too empty to be one edge.
 */
private fun segmentOf(region: IntArrayList, width: Int, magnitude: FloatArray, minLength: Float): FloatArray? {
    var total = 0.0
    var cx = 0.0
    var cy = 0.0
    for (k in 0 until region.size) {
        val pixel = region[k]
        val m = magnitude[pixel].toDouble()
        total += m
        cx += m * (pixel % width)
        cy += m * (pixel / width)
    }
    cx /= total
    cy /= total
    var ixx = 0.0
    var iyy = 0.0
    var ixy = 0.0
    for (k in 0 until region.size) {
        val pixel = region[k]
        val m = magnitude[pixel].toDouble()
        val dx = pixel % width - cx
        val dy = pixel / width - cy
        ixx += m * dx * dx
        iyy += m * dy * dy
        ixy += m * dx * dy
    }
    // The axis along which the pixels spread most.
    val theta = 0.5 * atan2(2 * ixy, ixx - iyy)
    val ux = cos(theta)
    val uy = sin(theta)
    var minAlong = Double.MAX_VALUE
    var maxAlong = -Double.MAX_VALUE
    var minAcross = Double.MAX_VALUE
    var maxAcross = -Double.MAX_VALUE
    for (k in 0 until region.size) {
        val pixel = region[k]
        val dx = pixel % width - cx
        val dy = pixel / width - cy
        val along = dx * ux + dy * uy
        val across = -dx * uy + dy * ux
        minAlong = min(minAlong, along)
        maxAlong = max(maxAlong, along)
        minAcross = min(minAcross, across)
        maxAcross = max(maxAcross, across)
    }
    val length = maxAlong - minAlong + 1
    val thickness = maxAcross - minAcross + 1
    if (length < minLength || length < MIN_ELONGATION * thickness) return null
    if (region.size / (length * thickness) < MIN_DENSITY) return null
    return floatArrayOf(
        (cx + ux * minAlong).toFloat(),
        (cy + uy * minAlong).toFloat(),
        (cx + ux * maxAlong).toFloat(),
        (cy + uy * maxAlong).toFloat(),
        length.toFloat(),
    )
}

/**
 * The angle between two directions, in 0..π. Both come from `atan2`, so one subtraction is enough —
 * and it stays in floats: a float π compared with a double π is larger, and a loop on that
 * comparison never ended.
 */
private fun angleDifference(a: Float, b: Float): Float {
    val difference = abs(a - b)
    return if (difference > PI_FLOAT) TWO_PI_FLOAT - difference else difference
}

private const val PI_FLOAT = PI.toFloat()
private const val TWO_PI_FLOAT = (2 * PI).toFloat()

/** A growable list of ints, without boxing every pixel of a region. */
private class IntArrayList {
    private var values = IntArray(64)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int) = values[index]

    fun clear() {
        size = 0
    }
}

/** LSD's 22.5°: two pixels of the same edge rarely disagree by more. */
private val ANGLE_TOLERANCE = (PI / 8).toFloat()

/** LSD's q / sin τ, with q = 2 grey levels of quantisation noise. */
private val GRADIENT_THRESHOLD = (2.0 / sin(PI / 8)).toFloat()
private const val BUCKETS = 1024
private const val MIN_REGION_PIXELS = 12
private const val MIN_LENGTH_PIXELS = 20f
private const val MIN_LENGTH_FRACTION = 0.025f
private const val MIN_ELONGATION = 4.0
private const val MIN_DENSITY = 0.6

