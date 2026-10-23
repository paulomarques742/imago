package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.Retouch
import eu.studio742.imago.core.model.RetouchMode
import java.util.concurrent.CancellationException
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lightroom's Remove tool on the original photo's pixels: step 1a, before everything else.
 *
 * There is no shader twin. Corrections only change when one is drawn or moved, never while a slider is
 * dragged, so the corrected photo is worked out once here and becomes the source of the rest of the
 * pipeline — the Android preview uploads it as its texture, the desktop and the export start from it.
 * The preview and the file agree because they are the same pixels, not because two copies of the
 * maths were kept in step.
 *
 * Everything that has a shape is worked out on the brush raster ([BRUSH_RASTER_SIDE] on the long side),
 * whatever the photo's resolution: the area, the Heal's membrane, the automatic source. That is what
 * makes a correction drawn on the preview come out the same on a 24 Mpx export.
 */
object Retouching {
    /**
     * Texels kept around an area: the ring the Heal reads its boundary from. Two is the least that
     * still holds a texel fully outside the feather's bilinear reach.
     */
    private const val MARGIN = 2

    /** Gauss-Seidel sweeps per level of the membrane's pyramid. Fixed, so every device ends in the same place. */
    private const val SWEEPS = 48

    /** Over-relaxation of the sweeps: past 1 it converges several times faster, and below 2 it stays stable. */
    private const val OMEGA = 1.8f

    /** [source] with [retouches] applied in order; [source] itself when there is nothing to apply. */
    fun apply(source: PixelBuffer, retouches: List<Retouch>): PixelBuffer {
        val active = retouches.filter { it.opacity > 0f && it.strokes.isNotEmpty() && it.mode != RetouchMode.REMOVE }
        if (active.isEmpty()) return source
        val image = source.copy()
        for (retouch in active) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            applyOne(image, retouch)
        }
        return image
    }

    /**
     * Where the source of a new Heal or Clone starts: the offset, in units of the image's width and
     * height, to the nearby area whose surroundings look most like the destination's. Null when no
     * area of that size fits in the photo next to it.
     *
     * [reference] is the photo at the raster's size, as the Auto Mask reads it, so the proposal is the
     * same from the preview and from the full photo.
     */
    fun proposeSource(reference: BrushReference, strokes: List<BrushStroke>): Pair<Float, Float>? {
        val zone = Zone.of(strokes, reference.width, reference.height) ?: return null
        val width = reference.width
        val height = reference.height
        val extent = max(zone.right - zone.left, zone.bottom - zone.top)
        val band = max(MARGIN, extent / 6)
        // The ring around the area, as texel indices: what the source's ring is compared against.
        val ring = ArrayList<Int>()
        val distance = zone.distanceOutside(band)
        for (y in max(0, zone.top - band) until min(height, zone.bottom + band)) {
            for (x in max(0, zone.left - band) until min(width, zone.right + band)) {
                val d = distance(x, y)
                if (d in 1..band) ring += y * width + x
            }
        }
        if (ring.isEmpty()) return null
        val colour = FloatArray(3)
        val target = FloatArray(ring.size * 3)
        ring.forEachIndexed { i, texel ->
            reference.colour(texel, colour)
            colour.copyInto(target, i * 3)
        }

        fun fits(dx: Int, dy: Int): Boolean =
            zone.left - band + dx >= 0 && zone.top - band + dy >= 0 &&
                zone.right + band + dx <= width && zone.bottom + band + dy <= height

        fun cost(dx: Int, dy: Int): Float {
            var sum = 0f
            ring.forEachIndexed { i, texel ->
                reference.colour(texel + dy * width + dx, colour)
                val r = colour[0] - target[i * 3]
                val g = colour[1] - target[i * 3 + 1]
                val b = colour[2] - target[i * 3 + 2]
                sum += r * r + g * g + b * b
            }
            return sum / ring.size
        }

        // Rings of candidates from just clear of the area to a few times its size, then a local search
        // around the best. The order is fixed and ties keep the first, so the proposal is reproducible.
        var best: Triple<Int, Int, Float>? = null
        val clear = extent + band
        for (step in 0 until 6) {
            val radius = clear * (1f + step * 0.5f)
            val directions = 16 + step * 4
            for (k in 0 until directions) {
                val angle = 2.0 * Math.PI * k / directions
                val dx = (radius * cos(angle)).roundToInt()
                val dy = (radius * sin(angle)).roundToInt()
                if (!fits(dx, dy)) continue
                val c = cost(dx, dy)
                if (best == null || c < best.third) best = Triple(dx, dy, c)
            }
        }
        var (bx, by, bc) = best ?: return null
        var span = max(1, extent / 8)
        while (span >= 1) {
            var moved = true
            while (moved) {
                moved = false
                for ((sx, sy) in listOf(span to 0, -span to 0, 0 to span, 0 to -span)) {
                    val dx = bx + sx
                    val dy = by + sy
                    if (!fits(dx, dy) || max(abs(dx), abs(dy)) < extent) continue
                    val c = cost(dx, dy)
                    if (c < bc) { bx = dx; by = dy; bc = c; moved = true }
                }
            }
            span /= 2
        }
        return bx.toFloat() / width to by.toFloat() / height
    }

    private fun applyOne(image: PixelBuffer, retouch: Retouch) {
        val (rasterWidth, rasterHeight) = brushRasterSize(image.width.toFloat() / image.height)
        val zone = Zone.of(retouch.strokes, rasterWidth, rasterHeight) ?: return
        val opacity = (retouch.opacity / 100f).coerceIn(0f, 1f)
        // The photo's pixels the area covers, with one to spare for the bilinear reach.
        val left = floor(zone.left.toFloat() / rasterWidth * image.width).toInt() - 1
        val top = floor(zone.top.toFloat() / rasterHeight * image.height).toInt() - 1
        val right = ceil(zone.right.toFloat() / rasterWidth * image.width).toInt() + 1
        val bottom = ceil(zone.bottom.toFloat() / rasterHeight * image.height).toInt() + 1
        val x0 = left.coerceAtLeast(0)
        val y0 = top.coerceAtLeast(0)
        val x1 = right.coerceAtMost(image.width)
        val y1 = bottom.coerceAtMost(image.height)
        if (x0 >= x1 || y0 >= y1) return
        val shiftX = retouch.sourceX * image.width
        val shiftY = retouch.sourceY * image.height
        // The source is read from a copy: when it overlaps the area, it must not read what this
        // correction is already writing. The copy reaches as far as the Heal's ring, which is
        // [MARGIN] texels past the area — several pixels each on a photo bigger than the raster.
        val ringX = ceil(MARGIN.toFloat() / rasterWidth * image.width).toInt()
        val ringY = ceil(MARGIN.toFloat() / rasterHeight * image.height).toInt()
        val origin = Snapshot.of(image, left - ringX + shiftX, top - ringY + shiftY, right + ringX + shiftX, bottom + ringY + shiftY)
        val membrane = if (retouch.mode == RetouchMode.HEAL) Membrane.solve(image, origin, zone, shiftX, shiftY) else null

        IntStream.range(y0, y1).parallel().forEach { y ->
            val sample = FloatArray(4)
            val v = (y + 0.5f) / image.height
            for (x in x0 until x1) {
                val u = (x + 0.5f) / image.width
                val weight = zone.raster.sample(u, v) * opacity
                if (weight <= 0f) continue
                origin.bilinear(x + 0.5f + shiftX, y + 0.5f + shiftY, sample)
                if (membrane != null) membrane.add(u, v, sample)
                val index = y * image.width + x
                val dst = image.pixels[index]
                image.pixels[index] = blend(dst, sample, weight)
            }
        }
    }

    private fun blend(dst: Int, src: FloatArray, weight: Float): Int {
        fun channel(shift: Int, value: Float): Int {
            val d = (dst ushr shift) and 0xFF
            return (d + (value - d) * weight).roundToInt().coerceIn(0, 255) shl shift
        }
        return (dst and -0x1000000) or channel(16, src[0]) or channel(8, src[1]) or channel(0, src[2])
    }

    /**
     * An area's weights on the brush raster and the texels they reach. [left] to [right] and [top] to
     * [bottom] bound every texel above zero, exclusive on the right and bottom.
     */
    private class Zone(val raster: BrushRaster, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun inside(x: Int, y: Int): Boolean =
            x in left until right && y in top until bottom && raster.valueAt(y * raster.width + x) > 0f

        /**
         * Chebyshev distance in texels from a texel to the area, up to [limit]; 0 inside, `limit + 1`
         * beyond it. Worked out once for the box around the area.
         */
        fun distanceOutside(limit: Int): (Int, Int) -> Int {
            val boxLeft = left - limit
            val boxTop = top - limit
            val boxWidth = right - left + 2 * limit
            val boxHeight = bottom - top + 2 * limit
            val far = limit + 1
            val distance = IntArray(boxWidth * boxHeight) { far }
            for (y in top until bottom) for (x in left until right) {
                if (inside(x, y)) distance[(y - boxTop) * boxWidth + (x - boxLeft)] = 0
            }
            // Dilation one ring at a time: cheap for the few texels a band is wide.
            for (ring in 1..limit) {
                for (y in 0 until boxHeight) for (x in 0 until boxWidth) {
                    if (distance[y * boxWidth + x] != far) continue
                    var near = false
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until boxWidth && ny in 0 until boxHeight && distance[ny * boxWidth + nx] == ring - 1) near = true
                    }
                    if (near) distance[y * boxWidth + x] = ring
                }
            }
            return { x, y ->
                val bx = x - boxLeft
                val by = y - boxTop
                if (bx in 0 until boxWidth && by in 0 until boxHeight) distance[by * boxWidth + bx] else far
            }
        }

        companion object {
            fun of(strokes: List<BrushStroke>, width: Int, height: Int): Zone? {
                val raster = BrushRaster(width, height)
                for (stroke in strokes) {
                    // The tool's area is always laid down whole: flow, density and the eraser belong to
                    // the masks' brush.
                    val solid = stroke.copy(flow = 100f, density = 100f, erase = false, autoMask = false)
                    BrushRasterizer.paint(raster, solid, 0, null)
                }
                val dirty = raster.takeDirty() ?: return null
                var left = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var right = Int.MIN_VALUE
                var bottom = Int.MIN_VALUE
                for (y in dirty[1] until dirty[3]) for (x in dirty[0] until dirty[2]) {
                    if (raster.valueAt(y * width + x) <= 0f) continue
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x + 1)
                    bottom = max(bottom, y + 1)
                }
                if (left >= right) return null
                return Zone(raster, left, top, right, bottom)
            }
        }
    }

    /** A copy of the photo's pixels around the source, read with the photo's own edges. */
    private class Snapshot(
        private val imageWidth: Int,
        private val imageHeight: Int,
        private val left: Int,
        private val top: Int,
        private val width: Int,
        private val pixels: IntArray,
    ) {
        /** RGB at a point in the photo's pixel coordinates, bilinear, clamped to the photo. */
        fun bilinear(px: Float, py: Float, out: FloatArray) {
            val sx = (px - 0.5f).coerceIn(0f, (imageWidth - 1).toFloat())
            val sy = (py - 0.5f).coerceIn(0f, (imageHeight - 1).toFloat())
            val xa = floor(sx).toInt()
            val ya = floor(sy).toInt()
            val xb = min(xa + 1, imageWidth - 1)
            val yb = min(ya + 1, imageHeight - 1)
            val fx = sx - xa
            val fy = sy - ya
            val a = at(xa, ya)
            val b = at(xb, ya)
            val c = at(xa, yb)
            val d = at(xb, yb)
            for ((channel, shift) in SHIFTS.withIndex()) {
                val upper = ((a ushr shift) and 0xFF) + (((b ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)) * fx
                val lower = ((c ushr shift) and 0xFF) + (((d ushr shift) and 0xFF) - ((c ushr shift) and 0xFF)) * fx
                out[channel] = upper + (lower - upper) * fy
            }
        }

        private fun at(x: Int, y: Int): Int = pixels[(y - top) * width + (x - left)]

        companion object {
            private val SHIFTS = intArrayOf(16, 8, 0)

            fun of(image: PixelBuffer, fromX: Float, fromY: Float, untilX: Float, untilY: Float): Snapshot {
                val left = (floor(fromX).toInt() - 1).coerceIn(0, image.width - 1)
                val top = (floor(fromY).toInt() - 1).coerceIn(0, image.height - 1)
                val right = (ceil(untilX).toInt() + 1).coerceIn(left + 1, image.width)
                val bottom = (ceil(untilY).toInt() + 1).coerceIn(top + 1, image.height)
                val width = right - left
                val pixels = IntArray(width * (bottom - top))
                image.getPixels(pixels, 0, width, left, top, width, bottom - top)
                return Snapshot(image.width, image.height, left, top, width, pixels)
            }
        }
    }

    /**
     * The Heal's correction: how much to add to the source so that it meets the destination's
     * surroundings without a seam.
     *
     * Outside the area it is the difference between destination and source; inside, the harmonic
     * function that joins those values — the solution of Laplace's equation, which is the smoothest
     * possible. It lives on the brush raster's grid and is read bilinearly at each pixel: being smooth,
     * it loses nothing to the coarser grid, and it costs the same on a preview and on a 24 Mpx export.
     */
    private class Membrane(
        private val rasterWidth: Int,
        private val rasterHeight: Int,
        private val left: Int,
        private val top: Int,
        private val width: Int,
        private val height: Int,
        private val values: FloatArray,
    ) {
        /** Adds the correction at a point of the image, in normalised coordinates, to [rgb]. */
        fun add(u: Float, v: Float, rgb: FloatArray) {
            val gx = (u * rasterWidth - 0.5f - left).coerceIn(0f, (width - 1).toFloat())
            val gy = (v * rasterHeight - 0.5f - top).coerceIn(0f, (height - 1).toFloat())
            val xa = floor(gx).toInt()
            val ya = floor(gy).toInt()
            val xb = min(xa + 1, width - 1)
            val yb = min(ya + 1, height - 1)
            val fx = gx - xa
            val fy = gy - ya
            for (channel in 0 until 3) {
                val a = values[(ya * width + xa) * 3 + channel]
                val b = values[(ya * width + xb) * 3 + channel]
                val c = values[(yb * width + xa) * 3 + channel]
                val d = values[(yb * width + xb) * 3 + channel]
                val upper = a + (b - a) * fx
                val lower = c + (d - c) * fx
                rgb[channel] += upper + (lower - upper) * fy
            }
        }

        companion object {
            fun solve(image: PixelBuffer, origin: Snapshot, zone: Zone, shiftX: Float, shiftY: Float): Membrane {
                val raster = zone.raster
                val left = max(0, zone.left - MARGIN)
                val top = max(0, zone.top - MARGIN)
                val right = min(raster.width, zone.right + MARGIN)
                val bottom = min(raster.height, zone.bottom + MARGIN)
                val width = right - left
                val height = bottom - top
                val known = BooleanArray(width * height)
                val values = FloatArray(width * height * 3)
                val dst = FloatArray(3)
                val src = FloatArray(3)
                val sample = FloatArray(3)
                for (gy in 0 until height) for (gx in 0 until width) {
                    val tx = gx + left
                    val ty = gy + top
                    if (zone.inside(tx, ty)) continue
                    known[gy * width + gx] = true
                    texelMean(image, origin, tx, ty, raster.width, raster.height, shiftX, shiftY, dst, src, sample)
                    for (channel in 0 until 3) values[(gy * width + gx) * 3 + channel] = dst[channel] - src[channel]
                }
                Laplace.fill(width, height, known, values)
                return Membrane(raster.width, raster.height, left, top, width, height, values)
            }

            /**
             * The mean of the photo's pixels under a texel, at the destination and at the source. A
             * texel covers several pixels of a full-size photo and a fraction of one on a preview; the
             * mean is what keeps the boundary from depending on which.
             */
            private fun texelMean(
                image: PixelBuffer,
                origin: Snapshot,
                tx: Int,
                ty: Int,
                rasterWidth: Int,
                rasterHeight: Int,
                shiftX: Float,
                shiftY: Float,
                dst: FloatArray,
                src: FloatArray,
                sample: FloatArray,
            ) {
                val fromX = ceil(tx.toFloat() / rasterWidth * image.width - 0.5f).toInt()
                val untilX = ceil((tx + 1).toFloat() / rasterWidth * image.width - 0.5f).toInt()
                val fromY = ceil(ty.toFloat() / rasterHeight * image.height - 0.5f).toInt()
                val untilY = ceil((ty + 1).toFloat() / rasterHeight * image.height - 0.5f).toInt()
                dst.fill(0f)
                src.fill(0f)
                if (fromX >= untilX || fromY >= untilY) {
                    // No pixel centre falls in the texel: the photo is smaller than the raster here.
                    val px = (tx + 0.5f) / rasterWidth * image.width
                    val py = (ty + 0.5f) / rasterHeight * image.height
                    pixelBilinear(image, px, py, dst)
                    origin.bilinear(px + shiftX, py + shiftY, src)
                    return
                }
                var count = 0
                for (y in fromY until untilY) for (x in fromX until untilX) {
                    val argb = image.pixels[y.coerceIn(0, image.height - 1) * image.width + x.coerceIn(0, image.width - 1)]
                    dst[0] += ((argb ushr 16) and 0xFF).toFloat()
                    dst[1] += ((argb ushr 8) and 0xFF).toFloat()
                    dst[2] += (argb and 0xFF).toFloat()
                    origin.bilinear(x + 0.5f + shiftX, y + 0.5f + shiftY, sample)
                    for (channel in 0 until 3) src[channel] += sample[channel]
                    count++
                }
                for (channel in 0 until 3) {
                    dst[channel] /= count
                    src[channel] /= count
                }
            }

            private fun pixelBilinear(image: PixelBuffer, px: Float, py: Float, out: FloatArray) {
                val sx = (px - 0.5f).coerceIn(0f, (image.width - 1).toFloat())
                val sy = (py - 0.5f).coerceIn(0f, (image.height - 1).toFloat())
                val xa = floor(sx).toInt()
                val ya = floor(sy).toInt()
                val xb = min(xa + 1, image.width - 1)
                val yb = min(ya + 1, image.height - 1)
                val fx = sx - xa
                val fy = sy - ya
                for ((channel, shift) in intArrayOf(16, 8, 0).withIndex()) {
                    fun at(x: Int, y: Int) = ((image.pixels[y * image.width + x] ushr shift) and 0xFF).toFloat()
                    val upper = at(xa, ya) + (at(xb, ya) - at(xa, ya)) * fx
                    val lower = at(xa, yb) + (at(xb, yb) - at(xa, yb)) * fx
                    out[channel] = upper + (lower - upper) * fy
                }
            }
        }
    }

    /**
     * Laplace's equation on a grid with the [known] cells fixed, solved coarse to fine.
     *
     * Plain relaxation on the full grid needs a number of sweeps that grows with the square of the
     * area's size. Starting each level from the solution of the one above it — where a cell is known
     * when any of its four children is, holding their mean — leaves only the fine detail to each level,
     * and a fixed number of sweeps is enough at any size.
     */
    internal object Laplace {
        fun fill(width: Int, height: Int, known: BooleanArray, values: FloatArray) {
            if (known.none { it }) {
                values.fill(0f)
                return
            }
            if (known.all { it }) return
            if (width > 2 && height > 2) {
                val coarseWidth = (width + 1) / 2
                val coarseHeight = (height + 1) / 2
                val coarseKnown = BooleanArray(coarseWidth * coarseHeight)
                val coarse = FloatArray(coarseWidth * coarseHeight * 3)
                val counts = IntArray(coarseWidth * coarseHeight)
                for (y in 0 until height) for (x in 0 until width) {
                    if (!known[y * width + x]) continue
                    val cell = (y / 2) * coarseWidth + x / 2
                    for (channel in 0 until 3) coarse[cell * 3 + channel] += values[(y * width + x) * 3 + channel]
                    counts[cell]++
                }
                for (cell in counts.indices) {
                    if (counts[cell] == 0) continue
                    coarseKnown[cell] = true
                    for (channel in 0 until 3) coarse[cell * 3 + channel] /= counts[cell]
                }
                fill(coarseWidth, coarseHeight, coarseKnown, coarse)
                for (y in 0 until height) for (x in 0 until width) {
                    if (known[y * width + x]) continue
                    // Bilinear from the coarse cells' centres, which sit at the fine grid's x / 2 - 0.25.
                    val cx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (coarseWidth - 1).toFloat())
                    val cy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (coarseHeight - 1).toFloat())
                    val xa = floor(cx).toInt()
                    val ya = floor(cy).toInt()
                    val xb = min(xa + 1, coarseWidth - 1)
                    val yb = min(ya + 1, coarseHeight - 1)
                    val fx = cx - xa
                    val fy = cy - ya
                    for (channel in 0 until 3) {
                        val a = coarse[(ya * coarseWidth + xa) * 3 + channel]
                        val b = coarse[(ya * coarseWidth + xb) * 3 + channel]
                        val c = coarse[(yb * coarseWidth + xa) * 3 + channel]
                        val d = coarse[(yb * coarseWidth + xb) * 3 + channel]
                        val upper = a + (b - a) * fx
                        val lower = c + (d - c) * fx
                        values[(y * width + x) * 3 + channel] = upper + (lower - upper) * fy
                    }
                }
            } else {
                // The coarsest level: a start from the mean of what is known.
                val mean = FloatArray(3)
                var count = 0
                for (cell in known.indices) if (known[cell]) {
                    for (channel in 0 until 3) mean[channel] += values[cell * 3 + channel]
                    count++
                }
                for (cell in known.indices) if (!known[cell]) {
                    for (channel in 0 until 3) values[cell * 3 + channel] = mean[channel] / count
                }
            }
            relax(width, height, known, values)
        }

        /** Over-relaxed Gauss-Seidel; an edge of the grid is a wall, not a value. */
        private fun relax(width: Int, height: Int, known: BooleanArray, values: FloatArray) {
            repeat(SWEEPS) {
                for (y in 0 until height) for (x in 0 until width) {
                    val cell = y * width + x
                    if (known[cell]) continue
                    for (channel in 0 until 3) {
                        var sum = 0f
                        var neighbours = 0
                        if (x > 0) { sum += values[(cell - 1) * 3 + channel]; neighbours++ }
                        if (x < width - 1) { sum += values[(cell + 1) * 3 + channel]; neighbours++ }
                        if (y > 0) { sum += values[(cell - width) * 3 + channel]; neighbours++ }
                        if (y < height - 1) { sum += values[(cell + width) * 3 + channel]; neighbours++ }
                        if (neighbours == 0) continue
                        val current = values[cell * 3 + channel]
                        values[cell * 3 + channel] = current + OMEGA * (sum / neighbours - current)
                    }
                }
            }
        }
    }
}

