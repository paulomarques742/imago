package eu.studio742.imago.core.render

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import eu.studio742.imago.core.model.BrushStroke

/**
 * The long side of a brush raster, in pixels.
 *
 * Fixed, and not the photo's resolution, so that the preview and the export paint the same raster:
 * the GPU receives exactly the numbers the CPU samples. At 2048 a hard edge on a 24 Mpx export is
 * about three pixels wide, which is what a brush with no feather looks like on screen anyway.
 */
const val BRUSH_RASTER_SIDE = 2048

/** The raster's size for an image of this aspect ratio, the long side at [BRUSH_RASTER_SIDE]. */
internal fun brushRasterSize(imageAspect: Float): Pair<Int, Int> = if (imageAspect >= 1f) {
    BRUSH_RASTER_SIDE to (BRUSH_RASTER_SIDE / imageAspect).roundToInt().coerceAtLeast(1)
} else {
    (BRUSH_RASTER_SIDE * imageAspect).roundToInt().coerceAtLeast(1) to BRUSH_RASTER_SIDE
}

/**
 * A brush component's weight over the whole image, in normalised image coordinates like every mask.
 *
 * Sixteen bits per pixel, as fixed point: a stroke with a low flow lays down increments far below
 * 1/255, and eight bits would round them to nothing at the edge of the feather. A float per pixel would
 * be twice the memory for a precision nobody can see.
 */
class BrushRaster internal constructor(val width: Int, val height: Int) {
    internal val data = ShortArray(width * height)

    private var dirtyLeft = Int.MAX_VALUE
    private var dirtyTop = Int.MAX_VALUE
    private var dirtyRight = Int.MIN_VALUE
    private var dirtyBottom = Int.MIN_VALUE

    /** The weight of one pixel, from 0 to 1. */
    fun valueAt(index: Int): Float = (data[index].toInt() and 0xFFFF) / MAX_LEVEL

    internal fun set(index: Int, value: Float) {
        data[index] = (value.coerceIn(0f, 1f) * MAX_LEVEL).roundToInt().toShort()
    }

    internal fun clear() {
        data.fill(0)
        markDirty(0, 0, width, height)
    }

    internal fun markDirty(left: Int, top: Int, right: Int, bottom: Int) {
        dirtyLeft = min(dirtyLeft, left)
        dirtyTop = min(dirtyTop, top)
        dirtyRight = max(dirtyRight, right)
        dirtyBottom = max(dirtyBottom, bottom)
    }

    /**
     * The rectangle changed since the last call — `left, top, right, bottom`, exclusive on the right
     * and bottom — or null when nothing changed. The GPU uploads only this part while a stroke is
     * being painted.
     */
    fun takeDirty(): IntArray? {
        if (dirtyLeft >= dirtyRight || dirtyTop >= dirtyBottom) return null
        val rect = intArrayOf(
            dirtyLeft.coerceAtLeast(0),
            dirtyTop.coerceAtLeast(0),
            dirtyRight.coerceAtMost(width),
            dirtyBottom.coerceAtMost(height),
        )
        dirtyLeft = Int.MAX_VALUE
        dirtyTop = Int.MAX_VALUE
        dirtyRight = Int.MIN_VALUE
        dirtyBottom = Int.MIN_VALUE
        return rect
    }

    /**
     * The weight at a point of the image, sampled as the GL sampler would: texel centres at half
     * pixels, bilinear, clamped at the borders. The same convention as [LocalMaskField.at].
     */
    fun sample(x: Float, y: Float): Float {
        val px = x * width - 0.5f
        val py = y * height - 0.5f
        val left = floor(px).toInt()
        val top = floor(py).toInt()
        val fx = px - left
        val fy = py - top
        val x0 = left.coerceIn(0, width - 1)
        val x1 = (left + 1).coerceIn(0, width - 1)
        val y0 = top.coerceIn(0, height - 1)
        val y1 = (top + 1).coerceIn(0, height - 1)
        val upper = valueAt(y0 * width + x0) + (valueAt(y0 * width + x1) - valueAt(y0 * width + x0)) * fx
        val lower = valueAt(y1 * width + x0) + (valueAt(y1 * width + x1) - valueAt(y1 * width + x0)) * fx
        return upper + (lower - upper) * fy
    }

    private companion object {
        const val MAX_LEVEL = 65535f
    }
}

/**
 * The original photo's colours at the raster's size, for the Auto Mask.
 *
 * The original and not the edited photo: a mask that changed when an exposure moved would be a mask
 * that moved under the person's feet. Each texel is the plain average of the photo's pixels it covers,
 * so the preview, built from a reduced photo, and the export, built from the full one, see nearly the
 * same colours.
 */
class BrushReference internal constructor(val width: Int, val height: Int, private val rgb: FloatArray) {
    /** The colour of a texel, as gamma-encoded RGB from 0 to 1, into [out]. */
    internal fun colour(index: Int, out: FloatArray) {
        out[0] = rgb[index * 3]
        out[1] = rgb[index * 3 + 1]
        out[2] = rgb[index * 3 + 2]
    }

    /** The colour at a point given in texels, bilinear — what is under the brush's centre. */
    internal fun colourAt(px: Float, py: Float, out: FloatArray) {
        val sx = (px - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val sy = (py - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = sx - x0
        val fy = sy - y0
        for (channel in 0 until 3) {
            val a = rgb[(y0 * width + x0) * 3 + channel]
            val b = rgb[(y0 * width + x1) * 3 + channel]
            val c = rgb[(y1 * width + x0) * 3 + channel]
            val d = rgb[(y1 * width + x1) * 3 + channel]
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            out[channel] = top + (bottom - top) * fy
        }
    }

    companion object {
        /** [source] is the photo in image space — before crop and rotation — at any resolution. */
        fun of(source: PixelSurface): BrushReference {
            val (width, height) = brushRasterSize(source.width.toFloat() / source.height)
            val sums = DoubleArray(width * height * 3)
            val counts = IntArray(width * height)
            val row = IntArray(source.width)
            for (sy in 0 until source.height) {
                source.getPixels(row, 0, source.width, 0, sy, source.width, 1)
                val ty = (sy.toLong() * height / source.height).toInt()
                for (sx in 0 until source.width) {
                    val tx = (sx.toLong() * width / source.width).toInt()
                    val texel = ty * width + tx
                    val argb = row[sx]
                    sums[texel * 3] += ((argb ushr 16) and 0xFF).toDouble()
                    sums[texel * 3 + 1] += ((argb ushr 8) and 0xFF).toDouble()
                    sums[texel * 3 + 2] += (argb and 0xFF).toDouble()
                    counts[texel]++
                }
            }
            val rgb = FloatArray(width * height * 3)
            for (texel in 0 until width * height) {
                // A photo smaller than the raster leaves texels no pixel fell in; they take the
                // pixel that covers them.
                val count = counts[texel]
                if (count > 0) {
                    for (channel in 0 until 3) rgb[texel * 3 + channel] = (sums[texel * 3 + channel] / count / 255.0).toFloat()
                } else {
                    val tx = texel % width
                    val ty = texel / width
                    val sx = ((tx + 0.5f) * source.width / width).toInt().coerceIn(0, source.width - 1)
                    val sy = ((ty + 0.5f) * source.height / height).toInt().coerceIn(0, source.height - 1)
                    source.getPixels(row, 0, source.width, 0, sy, source.width, 1)
                    val argb = row[sx]
                    rgb[texel * 3] = ((argb ushr 16) and 0xFF) / 255f
                    rgb[texel * 3 + 1] = ((argb ushr 8) and 0xFF) / 255f
                    rgb[texel * 3 + 2] = (argb and 0xFF) / 255f
                }
            }
            return BrushReference(width, height, rgb)
        }
    }
}

/**
 * Strokes into a raster.
 *
 * A stroke is a row of dabs along the finger's path, a fixed fraction of the radius apart. Each dab
 * lays down `flow × falloff` of the way to the stroke's density — Photoshop's and Lightroom's flow, so
 * that a slow stroke or a second pass builds up — and never takes away what is already above it. The
 * eraser multiplies what is there by what the dab leaves. Painting dab by dab and not stroke by
 * stroke is also what lets a stroke being drawn only paint its newest dabs.
 */
internal object BrushRasterizer {
    /** The distance between dabs, as a fraction of the radius. Photoshop's default is a quarter. */
    const val SPACING = 0.2f

    /**
     * The colour distance at which the Auto Mask starts letting go, and where it has let go — in
     * gamma-encoded RGB, normalised to 1 for black to white. Not measured against Lightroom, which
     * does not publish its own; here so that it is tuned in one place.
     */
    const val AUTO_MASK_NEAR = 0.06f
    const val AUTO_MASK_FAR = 0.16f

    /** The centres of the stroke's dabs, as `x0, y0, …` in raster pixels. Appending points only appends dabs. */
    fun dabs(stroke: BrushStroke, raster: BrushRaster): FloatArray {
        val points = stroke.points
        if (points.size < 2) return FloatArray(0)
        val radius = stroke.radius * raster.height
        val spacing = max(radius * SPACING, 0.5f)
        val out = ArrayList<Float>()
        var ax = points[0] * raster.width
        var ay = points[1] * raster.height
        out += ax
        out += ay
        var since = 0f
        var index = 2
        while (index + 1 < points.size) {
            val bx = points[index] * raster.width
            val by = points[index + 1] * raster.height
            val segment = hypot(bx - ax, by - ay)
            if (segment > 0f) {
                var travelled = 0f
                while (since + (segment - travelled) >= spacing) {
                    travelled += spacing - since
                    out += ax + (bx - ax) * travelled / segment
                    out += ay + (by - ay) * travelled / segment
                    since = 0f
                }
                since += segment - travelled
            }
            ax = bx
            ay = by
            index += 2
        }
        return out.toFloatArray()
    }

    /** Paints the dabs from [fromDab] on. Returns how many dabs the stroke has, painted or not. */
    fun paint(raster: BrushRaster, stroke: BrushStroke, fromDab: Int, reference: BrushReference?): Int {
        val centres = dabs(stroke, raster)
        val count = centres.size / 2
        if (fromDab >= count) return count
        val radius = max(stroke.radius * raster.height, 0.5f)
        val inner = radius * (1f - (stroke.feather / 100f).coerceIn(0f, 1f))
        val flow = (stroke.flow / 100f).coerceIn(0f, 1f)
        val density = (stroke.density / 100f).coerceIn(0f, 1f)
        val auto = if (stroke.autoMask) reference?.takeIf { it.width == raster.width && it.height == raster.height } else null
        val centreColour = FloatArray(3)
        val pixelColour = FloatArray(3)
        for (dab in fromDab until count) {
            val cx = centres[dab * 2]
            val cy = centres[dab * 2 + 1]
            val left = floor(cx - radius).toInt().coerceAtLeast(0)
            val right = (floor(cx + radius).toInt() + 1).coerceAtMost(raster.width)
            val top = floor(cy - radius).toInt().coerceAtLeast(0)
            val bottom = (floor(cy + radius).toInt() + 1).coerceAtMost(raster.height)
            if (left >= right || top >= bottom) continue
            auto?.colourAt(cx, cy, centreColour)
            for (y in top until bottom) {
                for (x in left until right) {
                    val distance = hypot(x + 0.5f - cx, y + 0.5f - cy)
                    if (distance >= radius) continue
                    var alpha = flow * falloff(distance, inner, radius)
                    if (alpha <= 0f) continue
                    val index = y * raster.width + x
                    if (auto != null) {
                        auto.colour(index, pixelColour)
                        alpha *= autoMaskFactor(centreColour, pixelColour)
                        if (alpha <= 0f) continue
                    }
                    val current = raster.valueAt(index)
                    val next = if (stroke.erase) {
                        current * (1f - alpha * density)
                    } else if (current < density) {
                        current + (density - current) * alpha
                    } else {
                        current
                    }
                    if (next != current) raster.set(index, next)
                }
            }
            raster.markDirty(left, top, right, bottom)
        }
        return count
    }

    /** Full inside the hard core, then a smooth fall to zero at the radius. */
    fun falloff(distance: Float, inner: Float, radius: Float): Float {
        if (distance <= inner) return 1f
        if (radius <= inner) return 0f
        val t = ((distance - inner) / (radius - inner)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    fun autoMaskFactor(centre: FloatArray, pixel: FloatArray): Float {
        val dr = pixel[0] - centre[0]
        val dg = pixel[1] - centre[1]
        val db = pixel[2] - centre[2]
        val distance = sqrt(dr * dr + dg * dg + db * db) / SQRT_THREE
        val t = ((distance - AUTO_MASK_NEAR) / (AUTO_MASK_FAR - AUTO_MASK_NEAR)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    private val SQRT_THREE = sqrt(3f)
}

/** The rasters of a render's brush components, by the index [MaskRenderComponent.brushIndex] gives them. */
class BrushRasters internal constructor(private val rasters: List<BrushRaster?>) {
    operator fun get(index: Int): BrushRaster? = rasters.getOrNull(index)

    val count: Int get() = rasters.size

    companion object {
        val NONE = BrushRasters(emptyList())

        /**
         * The rasters of [parameters]' brushes, painted from scratch. [source] is the photo in image
         * space, before crop and rotation; it is only read when a stroke has the Auto Mask on.
         */
        fun of(parameters: RenderParameters, source: PixelSurface): BrushRasters {
            if (!parameters.hasBrushes) return NONE
            return BrushRasterCache().rasters(parameters, source.width.toFloat() / source.height, source) { BrushReference.of(source) }
        }
    }
}

/**
 * Brush rasters kept between frames, painting only what changed.
 *
 * While a stroke is being drawn every frame brings the same strokes with a few more points at the end
 * of the last one, and repainting the whole brush for that would be thousands of dabs for the five
 * that are new. Anything other than strokes added or the last one extended — an undo, a different
 * photo — paints the raster again from nothing.
 */
class BrushRasterCache {
    private class Entry(val raster: BrushRaster, var strokes: List<BrushStroke>, var lastStrokeDabs: Int)

    private val entries = HashMap<Int, Entry>()
    private var source: Any? = null
    private var reference: BrushReference? = null

    /**
     * @param source identifies the photo: a different one throws everything away.
     * @param referenceOf builds the Auto Mask's colours, called at most once per photo and only if a
     *   stroke needs them.
     */
    @Synchronized
    fun rasters(
        parameters: RenderParameters,
        imageAspect: Float,
        source: Any,
        referenceOf: () -> BrushReference,
    ): BrushRasters {
        if (source !== this.source) {
            entries.clear()
            reference = null
            this.source = source
        }
        val brushes = parameters.masks.flatMap { it.components }.filter { it.brushIndex >= 0 }
        if (brushes.isEmpty()) return BrushRasters.NONE
        val (width, height) = brushRasterSize(imageAspect)
        val needsReference = brushes.any { component -> component.strokes.any { it.autoMask } }
        val colours = if (needsReference) reference ?: referenceOf().also { reference = it } else null
        val rasters = arrayOfNulls<BrushRaster>(brushes.maxOf { it.brushIndex } + 1)
        for (component in brushes) {
            val entry = entries[component.brushIndex]
                ?.takeIf { it.raster.width == width && it.raster.height == height }
                ?: Entry(BrushRaster(width, height), emptyList(), 0).also { entries[component.brushIndex] = it }
            update(entry, component.strokes, colours)
            rasters[component.brushIndex] = entry.raster
        }
        entries.keys.retainAll(brushes.map { it.brushIndex }.toSet())
        return BrushRasters(rasters.toList())
    }

    private fun update(entry: Entry, strokes: List<BrushStroke>, reference: BrushReference?) {
        val old = entry.strokes
        if (strokes === old) return
        val kept = old.size - 1
        val extends = old.isNotEmpty() && strokes.size >= old.size &&
            (0 until kept).all { strokes[it] === old[it] || strokes[it] == old[it] } &&
            strokes[kept].continues(old[kept])
        if (extends) {
            entry.lastStrokeDabs = BrushRasterizer.paint(entry.raster, strokes[kept], entry.lastStrokeDabs, reference)
            for (index in old.size until strokes.size) {
                entry.lastStrokeDabs = BrushRasterizer.paint(entry.raster, strokes[index], 0, reference)
            }
        } else {
            entry.raster.clear()
            entry.lastStrokeDabs = 0
            for (stroke in strokes) entry.lastStrokeDabs = BrushRasterizer.paint(entry.raster, stroke, 0, reference)
        }
        entry.strokes = strokes
    }

    /** The same brush, with the same points at the start: only its newest dabs are missing. */
    private fun BrushStroke.continues(previous: BrushStroke): Boolean =
        this === previous || (
            radius == previous.radius && feather == previous.feather && flow == previous.flow &&
                density == previous.density && erase == previous.erase && autoMask == previous.autoMask &&
                points.size >= previous.points.size &&
                (previous.points.indices).all { points[it] == previous.points[it] }
            )
}
