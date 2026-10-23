package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.RemoveFill
import java.util.SplittableRandom
import kotlin.math.max
import kotlin.math.min

/**
 * The synthesis behind the Remove mode: image completion by PatchMatch (Barnes et al., 2009) with
 * voting, coarse to fine (Wexler, Shechtman and Irani, 2007).
 *
 * Every small square of the area — a patch — is matched to the most similar patch of the photo around
 * it, and every texel of the area takes the mean of what the patches covering it propose. Repeating the
 * two steps pulls the area towards content that, patch by patch, exists elsewhere in the photo. At the
 * coarse levels the area is small and the structure settles; the finer levels only add detail.
 *
 * It runs once, when the stroke ends, on the brush raster's colours ([BrushReference]). What is kept is
 * not the result but where it came from — a [RemoveFill] — which any resolution of the photo can read.
 */
internal object ContentFill {
    /** Half a patch's side: 7 × 7 texels. */
    private const val RADIUS = 3
    private const val SIDE = 2 * RADIUS + 1

    /** The side of a stored cell, in texels of the level the fill is solved on. */
    private const val CELL = 4

    /**
     * The most area texels the finest solved level may have. A larger area is solved on a coarser level:
     * the render still reads the full photo's pixels, so the texture stays sharp, and only the seams
     * between regions copied from different places get coarser.
     */
    private const val MAX_AREA_TEXELS = 60_000

    private const val COARSEST_ITERATIONS = 10
    private const val ITERATIONS = 4

    fun solve(reference: BrushReference, zone: Retouching.Zone, seed: Long): RemoveFill? {
        val extent = max(zone.right - zone.left, zone.bottom - zone.top)
        val pad = max(2 * extent, 32) + RADIUS
        val windowLeft = max(0, zone.left - pad)
        val windowTop = max(0, zone.top - pad)
        val windowRight = min(reference.width, zone.right + pad)
        val windowBottom = min(reference.height, zone.bottom + pad)
        val levels = ArrayList<Level>()
        levels += Level.crop(reference, zone, windowLeft, windowTop, windowRight - windowLeft, windowBottom - windowTop)
        while (true) {
            val last = levels.last()
            if (min(last.width, last.height) / 2 < 4 * SIDE || last.holeExtent() <= SIDE) break
            levels += last.half()
        }
        var finest = 0
        while (finest < levels.lastIndex && levels[finest].holeCount() > MAX_AREA_TEXELS) finest++
        if (levels.subList(finest, levels.size).any { it.sources.isEmpty() }) return null

        val random = SplittableRandom(seed)
        val coarsest = levels.last()
        coarsest.growFromBoundary(random)
        coarsest.iterate(COARSEST_ITERATIONS, random)
        for (index in levels.lastIndex - 1 downTo finest) {
            val level = levels[index]
            level.matchesFrom(levels[index + 1], random)
            level.vote()
            level.iterate(ITERATIONS, random)
        }
        return levels[finest].fill(windowLeft, windowTop, 1 shl finest)
    }

    /** One level of the pyramid: the window's colours, the area to fill and the current matches. */
    private class Level(val width: Int, val height: Int, val rgb: FloatArray, val hole: BooleanArray) {
        /** Texels whose patch holds part of the area: the ones that look for a match and vote. */
        private val targets: IntArray
        /** Centres of patches wholly inside the window and wholly outside the area: what may be copied. */
        val sources: IntArray
        private val valid: BooleanArray
        private val match = IntArray(width * height) { -1 }
        private val distance = FloatArray(width * height)
        /** What the patches propose for each texel of the area, summed: red, green, blue and how many. */
        private val votes = FloatArray(width * height * 4)

        init {
            // Prefix sums of the area, so "does this patch touch it" is four reads.
            val sums = IntArray((width + 1) * (height + 1))
            for (y in 0 until height) for (x in 0 until width) {
                sums[(y + 1) * (width + 1) + x + 1] = (if (hole[y * width + x]) 1 else 0) +
                    sums[y * (width + 1) + x + 1] + sums[(y + 1) * (width + 1) + x] - sums[y * (width + 1) + x]
            }
            fun holeIn(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                val a = max(0, x0); val b = max(0, y0); val c = min(width, x1); val d = min(height, y1)
                if (a >= c || b >= d) return 0
                return sums[d * (width + 1) + c] - sums[b * (width + 1) + c] - sums[d * (width + 1) + a] + sums[b * (width + 1) + a]
            }
            val targetList = ArrayList<Int>()
            val sourceList = ArrayList<Int>()
            valid = BooleanArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val touches = holeIn(x - RADIUS, y - RADIUS, x + RADIUS + 1, y + RADIUS + 1)
                if (touches > 0) targetList += y * width + x
                val inside = x >= RADIUS && y >= RADIUS && x < width - RADIUS && y < height - RADIUS
                if (inside && touches == 0) {
                    valid[y * width + x] = true
                    sourceList += y * width + x
                }
            }
            targets = targetList.toIntArray()
            sources = sourceList.toIntArray()
        }

        fun holeCount(): Int = hole.count { it }

        fun holeExtent(): Int {
            var left = width; var top = height; var right = -1; var bottom = -1
            for (y in 0 until height) for (x in 0 until width) if (hole[y * width + x]) {
                left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
            }
            return if (right < 0) 0 else max(right - left, bottom - top) + 1
        }

        /** The next level up: half the size, a texel in the area when any of its four is. */
        fun half(): Level {
            val w = (width + 1) / 2
            val h = (height + 1) / 2
            val colours = FloatArray(w * h * 3)
            val area = BooleanArray(w * h)
            val counts = IntArray(w * h)
            for (y in 0 until height) for (x in 0 until width) {
                val cell = (y / 2) * w + x / 2
                if (hole[y * width + x]) { area[cell] = true; continue }
                for (c in 0 until 3) colours[cell * 3 + c] += rgb[(y * width + x) * 3 + c]
                counts[cell]++
            }
            for (cell in counts.indices) if (counts[cell] > 0) for (c in 0 until 3) colours[cell * 3 + c] /= counts[cell]
            return Level(w, h, colours, area)
        }

        /**
         * A first guess at the coarsest level, grown from the edge inwards: each texel of the area, nearest
         * the known photo first, takes the centre of the patch that best matches what is already known or
         * already grown around it. A smooth fill would be the easier start, but it wipes out the edges that
         * cross the area — a horizon, a wall — and voting never brings back what the start did not have.
         * The level is a few dozen texels at most, so every source is tried.
         */
        fun growFromBoundary(random: SplittableRandom) {
            val confident = BooleanArray(width * height) { !hole[it] }
            // Rings of the area by distance to the known photo, in a fixed order.
            val order = ArrayList<Int>()
            val queued = BooleanArray(width * height)
            var ring = ArrayList<Int>()
            for (t in 0 until width * height) {
                if (!hole[t]) continue
                val edge = NEIGHBOURS.any { (dx, dy) ->
                    val x = t % width + dx
                    val y = t / width + dy
                    x in 0 until width && y in 0 until height && !hole[y * width + x]
                }
                if (edge) { ring += t; queued[t] = true }
            }
            while (ring.isNotEmpty()) {
                order += ring
                val next = ArrayList<Int>()
                for (t in ring) for ((dx, dy) in NEIGHBOURS) {
                    val x = t % width + dx
                    val y = t / width + dy
                    if (x !in 0 until width || y !in 0 until height) continue
                    val n = y * width + x
                    if (hole[n] && !queued[n]) { queued[n] = true; next += n }
                }
                ring = next
            }
            for (t in order) {
                var best = sources[0]
                var bestDistance = Float.MAX_VALUE
                for (s in sources) {
                    val d = knownDistance(t, s, confident)
                    if (d < bestDistance) { best = s; bestDistance = d }
                }
                match[t] = best
                for (c in 0 until 3) rgb[t * 3 + c] = rgb[best * 3 + c]
                confident[t] = true
            }
            for (t in targets) if (match[t] < 0) match[t] = sources[random.nextInt(sources.size)]
        }

        /** [patchDistance] over the texels of the target patch already [known], as a mean. */
        private fun knownDistance(t: Int, s: Int, known: BooleanArray): Float {
            val tx = t % width
            val ty = t / width
            val sx = s % width
            val sy = s / width
            var sum = 0f
            var count = 0
            for (ky in -RADIUS..RADIUS) {
                val y = ty + ky
                if (y !in 0 until height) continue
                for (kx in -RADIUS..RADIUS) {
                    val x = tx + kx
                    if (x !in 0 until width || !known[y * width + x]) continue
                    val a = (y * width + x) * 3
                    val b = ((sy + ky) * width + sx + kx) * 3
                    val dr = rgb[a] - rgb[b]
                    val dg = rgb[a + 1] - rgb[b + 1]
                    val db = rgb[a + 2] - rgb[b + 2]
                    sum += dr * dr + dg * dg + db * db
                    count++
                }
            }
            return if (count == 0) 0f else sum / count
        }

        /** The matches of the level above, doubled, as the start for this one. */
        fun matchesFrom(coarse: Level, random: SplittableRandom) {
            for (t in targets) {
                val x = t % width
                val y = t / width
                val px = min(x / 2, coarse.width - 1)
                val py = min(y / 2, coarse.height - 1)
                val parent = coarse.match[py * coarse.width + px]
                var chosen = -1
                if (parent >= 0) {
                    val sx = (parent % coarse.width) * 2 + (x - px * 2)
                    val sy = (parent / coarse.width) * 2 + (y - py * 2)
                    if (sx in 0 until width && sy in 0 until height && valid[sy * width + sx]) chosen = sy * width + sx
                }
                match[t] = if (chosen >= 0) chosen else sources[random.nextInt(sources.size)]
            }
        }

        fun iterate(times: Int, random: SplittableRandom) {
            repeat(times) { pass ->
                search(forward = pass % 2 == 0, random = random)
                vote()
            }
        }

        /** One PatchMatch sweep: take a neighbour's match shifted by one, then look around at random. */
        private fun search(forward: Boolean, random: SplittableRandom) {
            for (t in targets) distance[t] = patchDistance(t, match[t], Float.MAX_VALUE)
            val step = if (forward) 1 else -1
            val order = if (forward) targets.indices else targets.indices.reversed()
            for (i in order) {
                val t = targets[i]
                val x = t % width
                val y = t / width
                var best = match[t]
                var bestDistance = distance[t]
                fun consider(candidate: Int) {
                    if (candidate == best) return
                    val d = patchDistance(t, candidate, bestDistance)
                    if (d < bestDistance) { best = candidate; bestDistance = d }
                }
                val nx = x - step
                if (nx in 0 until width) {
                    val neighbour = match[y * width + nx]
                    if (neighbour >= 0) {
                        val sx = neighbour % width + step
                        val sy = neighbour / width
                        if (sx in 0 until width && valid[sy * width + sx]) consider(sy * width + sx)
                    }
                }
                val ny = y - step
                if (ny in 0 until height) {
                    val neighbour = match[ny * width + x]
                    if (neighbour >= 0) {
                        val sx = neighbour % width
                        val sy = neighbour / width + step
                        if (sy in 0 until height && valid[sy * width + sx]) consider(sy * width + sx)
                    }
                }
                var radius = max(width, height)
                while (radius >= 1) {
                    val sx = best % width + random.nextInt(2 * radius + 1) - radius
                    val sy = best / width + random.nextInt(2 * radius + 1) - radius
                    if (sx in 0 until width && sy in 0 until height && valid[sy * width + sx]) consider(sy * width + sx)
                    radius /= 2
                }
                match[t] = best
                distance[t] = bestDistance
            }
        }

        /** Squared colour distance between the patches at [t] and [s], stopping once past [limit]. */
        private fun patchDistance(t: Int, s: Int, limit: Float): Float {
            val tx = t % width
            val ty = t / width
            val sx = s % width
            val sy = s / width
            var sum = 0f
            for (ky in -RADIUS..RADIUS) {
                val y = ty + ky
                if (y !in 0 until height) continue
                for (kx in -RADIUS..RADIUS) {
                    val x = tx + kx
                    if (x !in 0 until width) continue
                    val a = (y * width + x) * 3
                    val b = ((sy + ky) * width + sx + kx) * 3
                    val dr = rgb[a] - rgb[b]
                    val dg = rgb[a + 1] - rgb[b + 1]
                    val db = rgb[a + 2] - rgb[b + 2]
                    sum += dr * dr + dg * dg + db * db
                }
                if (sum > limit) return sum
            }
            return sum
        }

        /** Every texel of the area becomes the mean of what the patches covering it propose. */
        fun vote() {
            votes.fill(0f)
            for (t in targets) {
                val s = match[t]
                if (s < 0) continue
                val tx = t % width
                val ty = t / width
                val sx = s % width
                val sy = s / width
                for (ky in -RADIUS..RADIUS) {
                    val y = ty + ky
                    if (y !in 0 until height) continue
                    for (kx in -RADIUS..RADIUS) {
                        val x = tx + kx
                        if (x !in 0 until width || !hole[y * width + x]) continue
                        val a = (y * width + x) * 4
                        val b = ((sy + ky) * width + sx + kx) * 3
                        votes[a] += rgb[b]; votes[a + 1] += rgb[b + 1]; votes[a + 2] += rgb[b + 2]; votes[a + 3] += 1f
                    }
                }
            }
            for (texel in 0 until width * height) {
                val count = votes[texel * 4 + 3]
                if (count == 0f) continue
                rgb[texel * 3] = votes[texel * 4] / count
                rgb[texel * 3 + 1] = votes[texel * 4 + 1] / count
                rgb[texel * 3 + 2] = votes[texel * 4 + 2] / count
            }
        }

        /**
         * The matches as a [RemoveFill] in raster texels. Each cell takes the offset most of its area
         * texels agree on; a cell with none takes its nearest solved neighbour's, so the render can blend
         * across the area's edge without reading the content being removed.
         */
        fun fill(windowLeft: Int, windowTop: Int, scale: Int): RemoveFill? {
            var left = width; var top = height; var right = -1; var bottom = -1
            for (y in 0 until height) for (x in 0 until width) if (hole[y * width + x]) {
                left = min(left, x); top = min(top, y); right = max(right, x + 1); bottom = max(bottom, y + 1)
            }
            if (right < 0) return null
            val gridLeft = left - CELL
            val gridTop = top - CELL
            val columns = (right - gridLeft + CELL + CELL - 1) / CELL
            val rows = (bottom - gridTop + CELL + CELL - 1) / CELL
            val offsets = IntArray(columns * rows * 2)
            val solved = BooleanArray(columns * rows)
            val votes = HashMap<Long, Int>()
            for (row in 0 until rows) for (column in 0 until columns) {
                votes.clear()
                var bestKey = 0L
                var bestCount = 0
                for (y in gridTop + row * CELL until gridTop + (row + 1) * CELL) {
                    if (y !in 0 until height) continue
                    for (x in gridLeft + column * CELL until gridLeft + (column + 1) * CELL) {
                        if (x !in 0 until width) continue
                        val t = y * width + x
                        if (!hole[t] || match[t] < 0) continue
                        val dx = match[t] % width - x
                        val dy = match[t] / width - y
                        val key = (dx.toLong() shl 32) or (dy.toLong() and 0xFFFFFFFFL)
                        val count = (votes[key] ?: 0) + 1
                        votes[key] = count
                        // Ties keep the first offset met, so the same matches always give the same fill.
                        if (count > bestCount) { bestCount = count; bestKey = key }
                    }
                }
                if (bestCount == 0) continue
                val cell = row * columns + column
                solved[cell] = true
                offsets[cell * 2] = (bestKey shr 32).toInt() * scale
                offsets[cell * 2 + 1] = bestKey.toInt() * scale
            }
            // Breadth first from the solved cells, in a fixed order.
            val queue = ArrayDeque<Int>()
            for (cell in solved.indices) if (solved[cell]) queue += cell
            if (queue.isEmpty()) return null
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                val column = cell % columns
                val row = cell / columns
                for ((dc, dr) in NEIGHBOURS) {
                    val c = column + dc
                    val r = row + dr
                    if (c !in 0 until columns || r !in 0 until rows) continue
                    val next = r * columns + c
                    if (solved[next]) continue
                    solved[next] = true
                    offsets[next * 2] = offsets[cell * 2]
                    offsets[next * 2 + 1] = offsets[cell * 2 + 1]
                    queue += next
                }
            }
            return RemoveFill.of(
                cell = CELL * scale,
                left = windowLeft + gridLeft * scale,
                top = windowTop + gridTop * scale,
                columns = columns,
                rows = rows,
                offsets = offsets,
            )
        }

        companion object {
            private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

            fun crop(reference: BrushReference, zone: Retouching.Zone, left: Int, top: Int, width: Int, height: Int): Level {
                val rgb = FloatArray(width * height * 3)
                val hole = BooleanArray(width * height)
                val colour = FloatArray(3)
                for (y in 0 until height) for (x in 0 until width) {
                    val t = y * width + x
                    if (zone.inside(x + left, y + top)) {
                        hole[t] = true
                    } else {
                        reference.colour((y + top) * reference.width + x + left, colour)
                        colour.copyInto(rgb, t * 3)
                    }
                }
                return Level(width, height, rgb, hole)
            }
        }
    }
}
