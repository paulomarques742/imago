package eu.studio742.imago.core.render

import eu.studio742.imago.core.raw.decodeHalf
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Noise reduction for a RAW, from process 24: on the linear development, before the tone, so that the
 * exposure and the shadows that come after lift a photo already clean, as a raw developer does.
 *
 * Every channel goes through a square root, which evens out a sensor's noise between shadows and lights.
 * The noise is then measured on the photo itself ([NoiseProfile]) and every threshold is a multiple of
 * it: a slider means the same at ISO 100 and at ISO 12800, and on a clean photo it touches little.
 *
 * **Luminance** is split into four bands, between Gaussians of 1, 2, 4 and 8 pixels of the original
 * ([SIGMAS], times [FloatPixelBuffer.sourceScale] on a preview). Each band is shrunk against its own noise
 * at the pixel's brightness, `band · max(0, 1 − t² / E)`, where `E` is the band's energy over a 3×3
 * neighbourhood spaced by [spacing]: a lone spike of noise in a flat place has little energy around it
 * and goes, an edge has a lot and stays. Detail protects the finest band, Contrast the two coarsest.
 *
 * **Impulses**, with the luminance on: a pixel beyond the second-lowest or second-highest of its eight
 * neighbours by three times the noise, where those neighbours agree within four times it, is held there.
 * A demosaiced sensor at high ISO leaves isolated pixels far off that the bands would keep.
 *
 * **Colour** goes towards a wide average ([chromaLarge], measured at a reduced resolution, [reduction]) in
 * linear light minus luminance, which keeps the mean colour. A colour edge is kept where the colour lightly
 * smoothed (the 2 px Gaussian) differs from the wide average by more than its noise could explain, and the
 * luminance has an edge there too: a blotch of false colour has none. Colour Detail lowers that bar.
 *
 * There is no shader for this: `PhotoRenderer` runs this same code over the preview's development, off
 * the GL thread, and uploads the result in its place, so a preview cannot differ from an export.
 */
internal object RawNoiseReduction {
    const val BINS = 12
    val SIGMAS = floatArrayOf(1f, 2f, 4f, 8f)
    /** The band whose coarser Gaussian is the level of brightness and the lightly smoothed colour: 2 px. */
    const val LEVEL_BAND = 1
    const val OFFSET = 1e-4f
    val ROOT_OFFSET = sqrt(OFFSET)
    /** Output rows per strip of the export: each strip is developed with its margin and written back in place. */
    internal var stripRows = 256

    fun root(value: Float): Float = sqrt(max(value + OFFSET, 0f)) - ROOT_OFFSET

    fun unroot(value: Float): Float {
        val q = max(value + ROOT_OFFSET, 0f)
        return q * q - OFFSET
    }

    /** The wide colour average, in pixels of the original: 6 to 24, from Smoothness. */
    fun chromaLarge(smoothness: Float): Float = 6f + 18f * (smoothness / 100f).coerceIn(0f, 1f)

    /** How much smaller the image the wide average is taken on: the power of two that leaves its sigma above 1.5 px. */
    fun reduction(sigma: Float): Int {
        var factor = 1
        while (factor < 8 && sigma / (factor * 2) >= 1.5f) factor *= 2
        return factor
    }

    /** The spacing of band [band]'s 3×3 energy neighbourhood, in pixels of this buffer. */
    fun spacing(band: Int, scale: Float): Int = max(1, (0.7f * SIGMAS[band] * scale).roundToInt())

    fun lambda(amount: Float): Float = 3f * (amount / 100f).coerceIn(0f, 1f)

    /** The share of the luminance threshold band [band] gets: Detail spares the finest, Contrast the coarse ones. */
    fun bandFactor(band: Int, detail: Float, contrast: Float): Float = when (band) {
        0 -> 1f - 0.67f * (detail / 100f).coerceIn(0f, 1f)
        1 -> 1f
        else -> 1f - 0.6f * (contrast / 100f).coerceIn(0f, 1f)
    }

    fun colourAmount(amount: Float): Float = min(1f, (amount / 100f).coerceIn(0f, 1f) * 1.6f)

    /** How many times the expected spread a colour or light edge needs: 6 at Detail 0, 2 at 100. */
    fun knee(detail: Float): Float = 6f - 4f * (detail / 100f).coerceIn(0f, 1f)

    /** What a pixel's noise leaves in the 2 px Gaussian, with room for the correlation demosaicing gives it. */
    fun spread(scale: Float): Float = 3f / (2f * sqrt(Math.PI.toFloat()) * max(SIGMAS[LEVEL_BAND] * scale, 0.5f))

    /** The Gaussian of band [band]'s coarser side, in this buffer's pixels. */
    fun sigma(band: Int, scale: Float): Float = SIGMAS[band] * scale

    private val profiles = WeakHashMap<FloatPixelBuffer, NoiseProfile>()

    /** [source]'s noise, measured once per development. */
    fun profile(source: FloatPixelBuffer): NoiseProfile = synchronized(profiles) { profiles[source] } ?: NoiseProfile.measure(source).also {
        synchronized(profiles) { profiles[source] = it }
    }

    fun isActive(parameters: RenderParameters): Boolean =
        parameters.noiseModel >= RAW_NOISE_MODEL && (parameters.noiseLuminance > 0f || parameters.noiseColor > 0f)

    private class Kept(val key: List<Any>, val result: FloatPixelBuffer)
    private val kept = WeakHashMap<FloatPixelBuffer, Kept>()

    /**
     * [source] with the noise taken out, for [parameters]. A preview — which does not consume its source —
     * keeps its last result, since the noise reduction does not depend on anything after it and a slider
     * dragged elsewhere would otherwise develop it again for every frame.
     */
    fun apply(source: FloatPixelBuffer, parameters: RenderParameters, consume: Boolean, profileOf: FloatPixelBuffer = source): FloatPixelBuffer {
        val profile = profile(profileOf)
        if (consume) return source.also { applyInPlace(it, parameters, profile) }
        val key = listOf(
            parameters.noiseLuminance, parameters.noiseLuminanceDetail, parameters.noiseContrast,
            parameters.noiseColor, parameters.noiseColorDetail, parameters.noiseColorSmoothness, System.identityHashCode(source),
        )
        synchronized(kept) { kept[profileOf]?.takeIf { it.key == key }?.let { return it.result } }
        val result = source.copy()
        applyInPlace(result, parameters, profile)
        synchronized(kept) { kept[profileOf] = Kept(key, result) }
        return result
    }

    fun applyInPlace(image: FloatPixelBuffer, parameters: RenderParameters, profile: NoiseProfile) {
        val height = image.height
        val scale = image.sourceScale
        val lambda = lambda(parameters.noiseLuminance)
        val amount = colourAmount(parameters.noiseColor)
        val wide = if (amount > 0f) WideColour.build(image, chromaLarge(parameters.noiseColorSmoothness) * scale) else null
        // What a strip's rows read around them: the widest Gaussian in use and, with the bands, their energy.
        val reach = if (lambda > 0f) {
            PhotoEffects.halfGaussianKernel(sigma(SIGMAS.lastIndex, scale)).lastIndex + spacing(SIGMAS.lastIndex, scale) + 2
        } else {
            PhotoEffects.halfGaussianKernel(sigma(LEVEL_BAND, scale)).lastIndex
        }
        val margin = aligned(reach + 2 * ALIGN)
        val step = aligned(stripRows)
        val strip = Strip(image.width, min(step, aligned(height)) + 2 * margin, lambda > 0f, amount > 0f)
        // A strip's rows go back into the image only once no later strip reads them as its margin.
        val pending = ArrayDeque<Triple<Int, Int, FloatArray>>()
        val free = ArrayList<FloatArray>()
        var from = 0
        while (from < height) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val until = min(height, from + step)
            while (pending.isNotEmpty() && pending.first().second <= from - margin) {
                pending.removeFirst().let { writeRows(image, it.first, it.second, it.third); free += it.third }
            }
            strip.read(image, from - margin, until + margin)
            val out = free.removeLastOrNull() ?: FloatArray(min(step, height) * image.width * 3)
            strip.develop(parameters, profile, wide, scale, from - strip.top, until - strip.top, out)
            pending.addLast(Triple(from, until, out))
            from = until
        }
        while (pending.isNotEmpty()) pending.removeFirst().let { writeRows(image, it.first, it.second, it.third) }
    }

    /**
     * Strips start on a multiple of this, so that the smaller images the coarse Gaussians are taken on
     * fall on the same grid in every strip and no seam shows between them.
     */
    private const val ALIGN = 8

    private fun aligned(rows: Int) = (rows + ALIGN - 1) / ALIGN * ALIGN

    /** How much smaller the image Gaussian [band] is taken on: a power of two that leaves it above 1.5 px. */
    fun decimation(band: Int, scale: Float): Int = min(reduction(sigma(band, scale)), ALIGN)

    /**
     * A Gaussian of [sigma] pixels of [source] into [out]. With a [factor] above 1 it is taken on an image
     * reduced by a box average, with its sigma less what the box and the bilinear way back add, and read
     * back bilinearly: a fraction of the work, for what is smooth anyway.
     */
    private fun smoothed(source: FloatArray, width: Int, height: Int, sigma: Float, factor: Int, out: FloatArray, scratch: FloatArray) {
        if (factor == 1) return blurPlane(source, width, height, sigma, out, scratch)
        val w = (width + factor - 1) / factor
        val h = (height + factor - 1) / factor
        val small = FloatArray(w * h)
        IntStream.range(0, h).parallel().forEach { cy ->
            for (cx in 0 until w) {
                var total = 0f
                var count = 0
                for (dy in 0 until factor) {
                    val row = cy * factor + dy
                    if (row >= height) break
                    for (dx in 0 until factor) {
                        val column = cx * factor + dx
                        if (column >= width) break
                        total += source[row * width + column]
                        count++
                    }
                }
                small[cy * w + cx] = total / count
            }
        }
        val added = (factor * factor - 1) / 12f + factor * factor / 6f
        val reduced = sqrt(max(sigma * sigma - added, factor * factor / 4f)) / factor
        val blurred = FloatArray(w * h)
        blurPlane(small, w, h, reduced, blurred, FloatArray(w * h))
        IntStream.range(0, height).parallel().forEach { row ->
            val sy = ((row + 0.5f) / factor - 0.5f).coerceIn(0f, h - 1f)
            val y0 = sy.toInt(); val y1 = min(y0 + 1, h - 1); val fy = sy - y0
            for (x in 0 until width) {
                val sx = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, w - 1f)
                val x0 = sx.toInt(); val x1 = min(x0 + 1, w - 1); val fx = sx - x0
                val top = blurred[y0 * w + x0] * (1f - fx) + blurred[y0 * w + x1] * fx
                val bottom = blurred[y1 * w + x0] * (1f - fx) + blurred[y1 * w + x1] * fx
                out[row * width + x] = top * (1f - fy) + bottom * fy
            }
        }
    }

    /** The luminance's Gaussians for the four bands, at full size. */
    fun gaussians(y: FloatArray, width: Int, height: Int, scale: Float, scratch: FloatArray): Array<FloatArray> = Array(SIGMAS.size) { k ->
        FloatArray(width * height).also { smoothed(y, width, height, sigma(k, scale), decimation(k, scale), it, scratch) }
    }

    /** How much smaller the lightly smoothed colour is taken: colour has no fine detail to lose. */
    fun colourDecimation(scale: Float): Int = if (sigma(LEVEL_BAND, scale) >= 2f) 2 else 1

    private fun writeRows(image: FloatPixelBuffer, from: Int, until: Int, values: FloatArray) {
        val width = image.width
        IntStream.range(from, until).parallel().forEach { row ->
            val base = row * width * 3
            val source = (row - from) * width * 3
            for (i in 0 until width * 3) image.rgb[base + i] = values[source + i]
        }
    }

    /**
     * Rows of the image and all that is worked out from them, kept from one strip to the next: on a 26 Mpx
     * export a dozen planes for every strip are gigabytes for the collector to clear. What a recipe does
     * not use — the bands, with the luminance at 0 — is neither held nor computed.
     */
    private class Strip(val width: Int, capacity: Int, val luminance: Boolean, val colour: Boolean) {
        val n0 = width * capacity
        var rows = 0
        var top = 0
        val r = FloatArray(n0); val g = FloatArray(n0); val b = FloatArray(n0)
        private val y = FloatArray(n0)
        private val scratch = FloatArray(n0)
        /** The four Gaussians with the luminance on; with it off, only the level. */
        private val blurred = Array(SIGMAS.size) { k -> if (luminance || k == LEVEL_BAND) FloatArray(n0) else EMPTY }
        private val linearR = FloatArray(n0); private val linearB = FloatArray(n0)
        private val plane = if (colour) FloatArray(n0) else EMPTY
        private val smallC1 = if (colour) FloatArray(n0) else EMPTY
        private val smallC2 = if (colour) FloatArray(n0) else EMPTY
        private val smallR = if (colour) FloatArray(n0) else EMPTY
        private val smallB = if (colour) FloatArray(n0) else EMPTY
        val copies = if (luminance) Array(3) { FloatArray(n0) } else emptyArray()
        val light = if (luminance) FloatArray(n0) else EMPTY

        fun read(image: FloatPixelBuffer, from: Int, until: Int) {
            top = from
            rows = until - from
            val values = image.rgb.values
            val roots = ROOTS
            IntStream.range(0, rows).parallel().forEach { row ->
                val source = (from + row).coerceIn(0, image.height - 1) * width * 3
                for (x in 0 until width) {
                    val i = row * width + x
                    r[i] = roots[values[source + x * 3].toInt() and 0xFFFF]
                    g[i] = roots[values[source + x * 3 + 1].toInt() and 0xFFFF]
                    b[i] = roots[values[source + x * 3 + 2].toInt() and 0xFFFF]
                }
            }
        }

        fun develop(parameters: RenderParameters, profile: NoiseProfile, wide: WideColour?, scale: Float, first: Int, last: Int, out: FloatArray) {
            val rows = rows
            val lambda = lambda(parameters.noiseLuminance)
            if (lambda > 0f) holdImpulses(this, profile)
            IntStream.range(0, rows).parallel().forEach { row ->
                for (x in 0 until width) {
                    val i = row * width + x
                    val red = r[i]; val green = g[i]; val blue = b[i]
                    y[i] = PhotoEffects.luminance(red, green, blue)
                    val linearRed = unroot(red); val linearGreen = unroot(green); val linearBlue = unroot(blue)
                    val linear = PhotoEffects.luminance(linearRed, linearGreen, linearBlue)
                    linearR[i] = linearRed - linear
                    linearB[i] = linearBlue - linear
                }
            }
            if (lambda > 0f) {
                for (k in SIGMAS.indices) smoothed(y, width, rows, sigma(k, scale), decimation(k, scale), blurred[k], scratch)
            } else {
                // The level only picks a noise out of a table and says where the light has an edge.
                smoothed(y, width, rows, sigma(LEVEL_BAND, scale), colourDecimation(scale), blurred[LEVEL_BAND], scratch)
            }
            val level = blurred[LEVEL_BAND]
            val amount = colourAmount(parameters.noiseColor)
            if (amount > 0f) {
                val small = sigma(LEVEL_BAND, scale)
                val factor = colourDecimation(scale)
                IntStream.range(0, rows).parallel().forEach { row -> for (i in row * width until (row + 1) * width) plane[i] = r[i] - y[i] }
                smoothed(plane, width, rows, small, factor, smallC1, scratch)
                IntStream.range(0, rows).parallel().forEach { row -> for (i in row * width until (row + 1) * width) plane[i] = b[i] - y[i] }
                smoothed(plane, width, rows, small, factor, smallC2, scratch)
                smoothed(linearR, width, rows, small, factor, smallR, scratch)
                smoothed(linearB, width, rows, small, factor, smallB, scratch)
            }
            val factors = FloatArray(SIGMAS.size) { lambda * bandFactor(it, parameters.noiseLuminanceDetail, parameters.noiseContrast) }
            val spacings = IntArray(SIGMAS.size) { spacing(it, scale) }
            val knee = knee(parameters.noiseColorDetail)
            val spread = spread(scale)
            IntStream.range(first, last).parallel().forEach { row ->
                for (x in 0 until width) {
                    val i = row * width + x
                    val levelHere = level[i]
                    var lit: Float
                    if (lambda > 0f) {
                        var kept = blurred[SIGMAS.lastIndex][i]
                        for (k in SIGMAS.indices) {
                            val finer = if (k == 0) y else blurred[k - 1]
                            val coarser = blurred[k]
                            val d = spacings[k]
                            var energy = 0f
                            for (dy in -1..1) {
                                val rr = (row + dy * d).coerceIn(0, rows - 1)
                                for (dx in -1..1) {
                                    val j = rr * width + (x + dx * d).coerceIn(0, width - 1)
                                    val band = finer[j] - coarser[j]
                                    energy += band * band
                                }
                            }
                            energy /= 9f
                            val t = factors[k] * profile.band(k, levelHere)
                            kept += (finer[i] - coarser[i]) * max(0f, 1f - t * t / (energy + 1e-12f))
                        }
                        // The light after its reduction, in linear: every root moved by the same amount
                        // keeps the colour the pixel had.
                        val shift = kept - y[i]
                        lit = PhotoEffects.luminance(unroot(r[i] + shift), unroot(g[i] + shift), unroot(b[i] + shift))
                    } else {
                        lit = PhotoEffects.luminance(unroot(r[i]), unroot(g[i]), unroot(b[i]))
                    }
                    var chromaR = linearR[i]
                    var chromaB = linearB[i]
                    if (amount > 0f && wide != null) {
                        val at = wide.locate(x, top + row)
                        val wideC1 = wide.sample(0, at)
                        val wideC2 = wide.sample(1, at)
                        val wideY = wide.sample(2, at)
                        val chromaKnee = knee * spread * profile.chroma(levelHere) + 1e-6f
                        val lightKnee = knee * spread * profile.band(0, levelHere) + 1e-6f
                        val colourEdge = min(1f, max(abs(smallC1[i] - wideC1), abs(smallC2[i] - wideC2)) / chromaKnee)
                        val lightEdge = min(1f, abs(levelHere - wideY) / lightKnee)
                        val edge = min(colourEdge, lightEdge).let { it * it }
                        val wideR = wide.sample(3, at)
                        val wideB = wide.sample(4, at)
                        chromaR += (wideR + (smallR[i] - wideR) * edge - chromaR) * amount
                        chromaB += (wideB + (smallB[i] - wideB) * edge - chromaB) * amount
                    }
                    val red = lit + chromaR
                    val blue = lit + chromaB
                    val o = ((row - first) * width + x) * 3
                    out[o] = red
                    out[o + 1] = (lit - 0.2126f * red - 0.0722f * blue) / 0.7152f
                    out[o + 2] = blue
                }
            }
        }
    }

    private val EMPTY = FloatArray(0)

    /** [root] of every binary16 value: 78 million of them on a 26 Mpx photo, of 65 536 possible. */
    private val ROOTS: FloatArray by lazy { FloatArray(65536) { root(decodeHalf(it.toShort())) } }

    /**
     * The impulse hold, on the roots, in place. The level that picks the noise is the mean of the 3×3
     * luminance.
     */
    private fun holdImpulses(strip: Strip, profile: NoiseProfile) {
        val width = strip.width
        val rows = strip.rows
        val planes = arrayOf(strip.r, strip.g, strip.b)
        val copies = strip.copies
        val light = strip.light
        IntStream.range(0, rows).parallel().forEach { row ->
            val start = row * width
            for (c in 0..2) System.arraycopy(planes[c], start, copies[c], start, width)
            for (i in start until start + width) light[i] = PhotoEffects.luminance(copies[0][i], copies[1][i], copies[2][i])
        }
        IntStream.range(0, rows).parallel().forEach { row ->
            val around = FloatArray(8)
            val up = max(row - 1, 0) * width
            val here = row * width
            val down = min(row + 1, rows - 1) * width
            for (x in 0 until width) {
                val left = max(x - 1, 0)
                val right = min(x + 1, width - 1)
                val mean = (light[up + left] + light[up + x] + light[up + right] +
                    light[here + left] + light[here + x] + light[here + right] +
                    light[down + left] + light[down + x] + light[down + right]) / 9f
                for (c in 0..2) {
                    val copy = copies[c]
                    around[0] = copy[up + left]; around[1] = copy[up + x]; around[2] = copy[up + right]
                    around[3] = copy[here + left]; around[4] = copy[here + right]
                    around[5] = copy[down + left]; around[6] = copy[down + x]; around[7] = copy[down + right]
                    planes[c][here + x] = hold(copy[here + x], around, profile.channel(c, mean))
                }
            }
        }
    }

    /** [value] held between the second-lowest and second-highest of [around] when it is an impulse. */
    fun hold(value: Float, around: FloatArray, sigma: Float): Float {
        var low1 = Float.MAX_VALUE; var low2 = Float.MAX_VALUE
        var high1 = -Float.MAX_VALUE; var high2 = -Float.MAX_VALUE
        for (a in around) {
            if (a < low1) { low2 = low1; low1 = a } else if (a < low2) low2 = a
            if (a > high1) { high2 = high1; high1 = a } else if (a > high2) high2 = a
        }
        if (high2 - low2 > 4f * sigma) return value
        return when {
            value > high2 + 3f * sigma -> high2
            value < low2 - 3f * sigma -> low2
            else -> value
        }
    }

    /**
     * The wide colour average of the whole image, at a reduced size: the roots' chroma and luminance and
     * the linear chroma. Impulses are left in: a lone pixel weighs nothing among the hundreds a cell and
     * its blur average.
     */
    class WideColour private constructor(
        val width: Int,
        val height: Int,
        private val imageWidth: Int,
        private val imageHeight: Int,
        private val planes: Array<FloatArray>,
    ) {
        /**
         * Where pixel ([x], [y]) of the image falls on the small one, as two indices and two weights packed
         * for [sample]: worked out once for the five planes. The same as [DetailPyramid.sampleBilinear].
         */
        fun locate(x: Int, y: Int): Long {
            val sx = (x + 0.5f) / imageWidth * width - 0.5f
            val sy = (y + 0.5f) / imageHeight * height - 0.5f
            val left = kotlin.math.floor(sx).toInt()
            val top = kotlin.math.floor(sy).toInt()
            val fx = ((sx - left) * 255f + 0.5f).toInt()
            val fy = ((sy - top) * 255f + 0.5f).toInt()
            val cx = left.coerceIn(-1, width - 1) + 1
            val cy = top.coerceIn(-1, height - 1) + 1
            return (cx.toLong() shl 40) or (cy.toLong() shl 16) or (fx.toLong() shl 8) or fy.toLong()
        }

        fun sample(plane: Int, at: Long): Float {
            val data = planes[plane]
            val left = ((at ushr 40) and 0xFFFFFF).toInt() - 1
            val top = ((at ushr 16) and 0xFFFFFF).toInt() - 1
            val fx = ((at ushr 8) and 0xFF).toInt() / 255f
            val fy = (at and 0xFF).toInt() / 255f
            val x0 = left.coerceIn(0, width - 1); val x1 = (left + 1).coerceIn(0, width - 1)
            val y0 = top.coerceIn(0, height - 1); val y1 = (top + 1).coerceIn(0, height - 1)
            val upper = data[y0 * width + x0] + (data[y0 * width + x1] - data[y0 * width + x0]) * fx
            val lower = data[y1 * width + x0] + (data[y1 * width + x1] - data[y1 * width + x0]) * fx
            return upper + (lower - upper) * fy
        }

        companion object {
            fun build(image: FloatPixelBuffer, sigma: Float): WideColour {
                val factor = reduction(sigma)
                val width = max(1, image.width / factor)
                val height = max(1, image.height / factor)
                val planes = Array(5) { FloatArray(width * height) }
                val counts = IntArray(width * height)
                // A cell of 4×4 pixels or more is an average the blur averages again: every other pixel will do.
                val stride = if (factor >= 4) 2 else 1
                // Strips of whole cells, so that each row of cells is summed by one task.
                val step = aligned(stripRows) / factor * factor
                val strip = Strip(image.width, step + 2, luminance = false, colour = false)
                var from = 0
                while (from < image.height) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                    val until = min(image.height, from + step)
                    strip.read(image, from - 1, until + 1)
                    val firstCell = from / factor
                    val lastCell = min(height - 1, (until - 1) / factor)
                    IntStream.rangeClosed(firstCell, lastCell).parallel().forEach { cy ->
                        val rowFrom = max(from, cy * factor)
                        val rowUntil = if (cy == height - 1) until else min(until, (cy + 1) * factor)
                        for (imageRow in rowFrom until rowUntil step stride) {
                            val row = imageRow - from + 1
                            for (x in 0 until image.width step stride) {
                                val i = row * strip.width + x
                                val cell = cy * width + (x / factor).coerceAtMost(width - 1)
                                val light = PhotoEffects.luminance(strip.r[i], strip.g[i], strip.b[i])
                                val red = unroot(strip.r[i]); val green = unroot(strip.g[i]); val blue = unroot(strip.b[i])
                                val linear = PhotoEffects.luminance(red, green, blue)
                                planes[0][cell] += strip.r[i] - light
                                planes[1][cell] += strip.b[i] - light
                                planes[2][cell] += light
                                planes[3][cell] += red - linear
                                planes[4][cell] += blue - linear
                                counts[cell]++
                            }
                        }
                    }
                    from = until
                }
                val scratch = FloatArray(width * height)
                for (plane in planes) {
                    for (i in plane.indices) plane[i] /= counts[i].coerceAtLeast(1)
                    val blurred = FloatArray(width * height)
                    blurPlane(plane, width, height, sigma / factor, blurred, scratch)
                    blurred.copyInto(plane)
                }
                return WideColour(width, height, image.width, image.height, planes)
            }
        }
    }

    /** A separable Gaussian with [PhotoEffects.halfGaussianKernel], edges replicated: the GPU's blur pass. */
    fun blurPlane(source: FloatArray, width: Int, height: Int, sigma: Float, out: FloatArray, scratch: FloatArray) {
        val weights = PhotoEffects.halfGaussianKernel(sigma)
        val radius = weights.lastIndex
        IntStream.range(0, height).parallel().forEach { y ->
            val line = FloatArray(width)
            System.arraycopy(source, y * width, line, 0, width)
            DetailPyramid.blurLine(line, scratch, y * width, width, weights, radius)
        }
        // Whole rows at a time: the taps go down the image, the memory is read in order.
        IntStream.range(0, height).parallel().forEach { y ->
            val base = y * width
            val w0 = weights[0]
            for (x in 0 until width) out[base + x] = scratch[base + x] * w0
            for (o in 1..radius) {
                val above = max(y - o, 0) * width
                val below = min(y + o, height - 1) * width
                val w = weights[o]
                for (x in 0 until width) out[base + x] += (scratch[above + x] + scratch[below + x]) * w
            }
        }
    }

    /**
     * The spread white noise of unit spread keeps between Gaussians of [finer] and [coarser] (0 for none):
     * Σ(A − B)² of the 2D kernels, which separate.
     */
    fun whiteBand(finer: Float, coarser: Float): Float {
        val a = if (finer <= 0f) floatArrayOf(1f) else PhotoEffects.halfGaussianKernel(finer)
        val b = PhotoEffects.halfGaussianKernel(coarser)
        fun dot(p: FloatArray, q: FloatArray): Float {
            var total = p[0] * q[0]
            for (i in 1..min(p.lastIndex, q.lastIndex)) total += 2f * p[i] * q[i]
            return total
        }
        val aa = dot(a, a); val ab = dot(a, b); val bb = dot(b, b)
        return sqrt(max(aa * aa - 2f * ab * ab + bb * bb, 0f))
    }

    const val RAW_NOISE_MODEL = 2
}

/**
 * A photo's noise, measured on it: for each luminance band, each root channel and the colour, a spread at
 * each of [RawNoiseReduction.BINS] levels of brightness, from 0 to [top]. The spread is the fifth of the
 * residuals nearest zero over 0.2533, which is a Gaussian's spread when noise is all there is: noise is
 * everywhere, detail in places, and the quietest fifth is the noise's.
 */
internal class NoiseProfile(val bands: Array<FloatArray>, val channels: Array<FloatArray>, val chromaTable: FloatArray, val top: Float) {
    private fun at(table: FloatArray, level: Float): Float {
        val bins = RawNoiseReduction.BINS
        val x = (level / top * bins - 0.5f).coerceIn(0f, bins - 1f)
        val i = x.toInt().coerceAtMost(bins - 2)
        val f = x - i
        return table[i] * (1f - f) + table[i + 1] * f
    }

    fun band(band: Int, level: Float) = at(bands[band], level)
    fun channel(channel: Int, level: Float) = at(channels[channel], level)
    fun chroma(level: Float) = at(chromaTable, level)

    /** The tables in one array for the preview's shader: bands, channels, colour, then [top]. */
    fun packed(): FloatArray {
        val out = ArrayList<Float>()
        bands.forEach { out += it.toList() }
        channels.forEach { out += it.toList() }
        out += chromaTable.toList()
        out += top
        return out.toFloatArray()
    }

    companion object {
        private const val TILE = 256
        private const val GRID_X = 4
        private const val GRID_Y = 3

        /** Measured on up to twelve tiles spread over the photo, with their margins. */
        fun measure(image: FloatPixelBuffer): NoiseProfile {
            val scale = image.sourceScale
            val bins = RawNoiseReduction.BINS
            val sigmas = RawNoiseReduction.SIGMAS
            val margin = PhotoEffects.halfGaussianKernel(RawNoiseReduction.sigma(sigmas.lastIndex, scale)).lastIndex + 10
            val levels = FloatList()
            val samples = Array(sigmas.size + 3 + 1) { FloatList() }
            val tile = min(TILE, min(image.width, image.height))
            // Each tile on a core, into lists of its own, put together in the order of the grid.
            val tiles = Array(GRID_X * GRID_Y) { FloatList() to Array(sigmas.size + 3 + 1) { FloatList() } }
            IntStream.range(0, tiles.size).parallel().forEach { index ->
                val gx = index % GRID_X
                val gy = index / GRID_X
                val x0 = ((image.width - tile) * (gx + 0.5f) / GRID_X).toInt().coerceIn(0, image.width - tile)
                val y0 = ((image.height - tile) * (gy + 0.5f) / GRID_Y).toInt().coerceIn(0, image.height - tile)
                measureTile(image, x0, y0, tile, margin, scale, tiles[index].first, tiles[index].second)
            }
            for ((tileLevels, tileSamples) in tiles) {
                levels.addAll(tileLevels)
                for (i in samples.indices) samples[i].addAll(tileSamples[i])
            }
            val sorted = levels.toArray().also { it.sort() }
            val top = (if (sorted.isEmpty()) 1f else sorted[(sorted.size * 0.995f).toInt().coerceAtMost(sorted.size - 1)]).coerceAtLeast(1e-3f)
            val binOf = IntArray(levels.size) { (levels[it] / top * bins).toInt().coerceIn(0, bins - 1) }
            val tables = samples.map { list ->
                val buckets = Array(bins) { FloatList() }
                for (i in 0 until list.size) buckets[binOf[i]].add(list[i])
                val values = FloatArray(bins) { k ->
                    val bucket = buckets[k].toArray()
                    if (bucket.size < 200) Float.NaN else { bucket.sort(); bucket[bucket.size / 5] / 0.2533f }
                }
                // A level the photo hardly has takes its nearest neighbour's noise.
                for (k in 0 until bins) if (values[k].isNaN()) {
                    values[k] = (1 until bins).firstNotNullOfOrNull { d ->
                        listOf(k - d, k + d).firstOrNull { it in 0 until bins && !values[it].isNaN() }?.let { values[it] }
                    } ?: 0f
                }
                // Noise changes slowly with brightness: a level many times noisier than the others is texture
                // that even its quietest fifth could not get away from.
                val median = values.sortedArray()[bins / 2]
                for (k in 0 until bins) values[k] = min(values[k], 2f * median)
                values
            }
            val bands = Array(sigmas.size) { tables[it] }
            // The coarser bands' noise follows from the finest one's: at most three times what white noise
            // would keep in them. Measured on its own, a coarse band of a clean photo is its texture.
            var finer = 0f
            val white = FloatArray(sigmas.size) { k -> RawNoiseReduction.whiteBand(finer, RawNoiseReduction.sigma(k, scale)).also { finer = RawNoiseReduction.sigma(k, scale) } }
            for (k in 1 until sigmas.size) for (bin in 0 until bins) {
                bands[k][bin] = min(bands[k][bin], bands[0][bin] * 3f * white[k] / white[0].coerceAtLeast(1e-6f))
            }
            return NoiseProfile(bands, Array(3) { tables[sigmas.size + it] }, tables[sigmas.size + 3], top)
        }

        private fun measureTile(
            image: FloatPixelBuffer,
            x0: Int,
            y0: Int,
            tile: Int,
            margin: Int,
            scale: Float,
            levels: FloatList,
            samples: Array<FloatList>,
        ) {
            val left = max(0, x0 - margin)
            val top = max(0, y0 - margin)
            val right = min(image.width, x0 + tile + margin)
            val bottom = min(image.height, y0 + tile + margin)
            val w = right - left
            val h = bottom - top
            val n = w * h
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val y = FloatArray(n)
            for (row in 0 until h) for (x in 0 until w) {
                val s = ((top + row) * image.width + left + x) * 3
                val i = row * w + x
                r[i] = RawNoiseReduction.root(image.rgb[s])
                g[i] = RawNoiseReduction.root(image.rgb[s + 1])
                b[i] = RawNoiseReduction.root(image.rgb[s + 2])
                y[i] = PhotoEffects.luminance(r[i], g[i], b[i])
            }
            val scratch = FloatArray(n)
            val sigmas = RawNoiseReduction.SIGMAS
            val blurred = RawNoiseReduction.gaussians(y, w, h, scale, scratch)
            val level = blurred[RawNoiseReduction.LEVEL_BAND]
            val c1 = FloatArray(n) { r[it] - y[it] }
            val c2 = FloatArray(n) { b[it] - y[it] }
            val small1 = FloatArray(n).also { RawNoiseReduction.blurPlane(c1, w, h, RawNoiseReduction.sigma(RawNoiseReduction.LEVEL_BAND, scale), it, scratch) }
            val small2 = FloatArray(n).also { RawNoiseReduction.blurPlane(c2, w, h, RawNoiseReduction.sigma(RawNoiseReduction.LEVEL_BAND, scale), it, scratch) }
            val channels = arrayOf(r, g, b)
            for (row in (y0 - top) until (y0 - top + tile) step 2) for (x in (x0 - left) until (x0 - left + tile) step 2) {
                if (row < 1 || row >= h - 1 || x < 1 || x >= w - 1) continue
                val i = row * w + x
                levels.add(level[i])
                for (k in sigmas.indices) samples[k].add(abs((if (k == 0) y[i] else blurred[k - 1][i]) - blurred[k][i]))
                for (c in 0..2) {
                    val plane = channels[c]
                    var mean = 0f
                    for (dy in -1..1) for (dx in -1..1) mean += plane[i + dy * w + dx]
                    samples[sigmas.size + c].add(abs(plane[i] - mean / 9f))
                }
                samples[sigmas.size + 3].add(max(abs(c1[i] - small1[i]), abs(c2[i] - small2[i])))
            }
        }
    }
}

/** A growing list of floats without a box for each. */
internal class FloatList {
    private var values = FloatArray(1024)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun addAll(other: FloatList) {
        for (i in 0 until other.size) add(other[i])
    }

    operator fun get(index: Int): Float = values[index]

    fun toArray(): FloatArray = values.copyOf(size)
}
