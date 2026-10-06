package eu.studio742.imago.core.render

import kotlin.math.max
import kotlin.math.min

/**
 * CPU replica of the blur pyramid [PhotoRenderer] builds in FBOs.
 *
 * The intermediate levels keep luminance and not RGB. Blur and reduction are linear operations and
 * so is luminance, so `luminance(blur(RGB))` and `blur(luminance(RGB))` give the same number — the
 * difference is keeping a third of the memory, which on a 24 MP export decides whether the process
 * survives.
 *
 * The exception is dehaze: the dark channel is a minimum, not linear, and requires keeping RGB. That
 * level is only built when dehaze is active.
 */
internal class DetailPyramid private constructor(
    private val band: FloatArray,
    private val bandWidth: Int,
    private val bandHeight: Int,
    private val coarse: FloatArray,
    private val coarseWidth: Int,
    private val coarseHeight: Int,
    private val dark: FloatArray?,
    val airlight: Float,
) {
    /** Luminance blurred with [PhotoEffects.TEXTURE_BAND_SIGMA], sampled as in GL. */
    fun bandAt(u: Float, v: Float): Float = sampleBilinear(band, bandWidth, bandHeight, u, v)

    /** Luminance blurred with [PhotoEffects.CLARITY_SIGMA]. */
    fun coarseAt(u: Float, v: Float): Float = sampleBilinear(coarse, coarseWidth, coarseHeight, u, v)

    /** Refined dark channel, or zero when dehaze is off. */
    fun darkAt(u: Float, v: Float): Float =
        dark?.let { sampleBilinear(it, coarseWidth, coarseHeight, u, v) } ?: 0f

    companion object {
        fun build(bitmap: PixelSurface, withDehaze: Boolean): DetailPyramid {
            val width = bitmap.width
            val height = bitmap.height
            val halfWidth = max(width / 2, 1)
            val halfHeight = max(height / 2, 1)

            val half = FloatArray(halfWidth * halfHeight)
            val pixels = IntArray(width)
            val topLuma = FloatArray(width)
            val bottomLuma = FloatArray(width)
            for (targetRow in 0 until halfHeight) {
                val top = min(targetRow * 2, height - 1)
                val bottom = min(top + 1, height - 1)
                bitmap.getPixels(pixels, 0, width, 0, top, width, 1)
                for (x in 0 until width) topLuma[x] = pixelLuminance(pixels[x])
                bitmap.getPixels(pixels, 0, width, 0, bottom, width, 1)
                for (x in 0 until width) bottomLuma[x] = pixelLuminance(pixels[x])
                accumulateHalfRow(half, halfWidth, targetRow, topLuma, bottomLuma, width)
            }

            val quarterWidth = max(halfWidth / 2, 1)
            val quarterHeight = max(halfHeight / 2, 1)
            val quarter = reduce(half, halfWidth, halfHeight, quarterWidth, quarterHeight)
            val coarseWidth = max(quarterWidth / 2, 1)
            val coarseHeight = max(quarterHeight / 2, 1)
            val coarse = reduce(quarter, quarterWidth, quarterHeight, coarseWidth, coarseHeight)

            blurInPlace(half, halfWidth, halfHeight, PhotoEffects.TEXTURE_BAND_SIGMA / 2f)
            blurInPlace(coarse, coarseWidth, coarseHeight, PhotoEffects.CLARITY_SIGMA / 8f)

            var dark: FloatArray? = null
            var airlight = 1f
            if (withDehaze) {
                val stats = DehazeStats.build(bitmap, quarterWidth, quarterHeight, coarseWidth, coarseHeight)
                blurInPlace(stats.dark, coarseWidth, coarseHeight, PhotoEffects.DEHAZE_REFINE_SIGMA / 8f)
                dark = stats.dark
                airlight = stats.airlight
            }

            return DetailPyramid(
                band = half,
                bandWidth = halfWidth,
                bandHeight = halfHeight,
                coarse = coarse,
                coarseWidth = coarseWidth,
                coarseHeight = coarseHeight,
                dark = dark,
                airlight = airlight,
            )
        }

        private fun accumulateHalfRow(
            target: FloatArray,
            targetWidth: Int,
            targetRow: Int,
            top: FloatArray,
            bottom: FloatArray,
            width: Int,
        ) {
            val base = targetRow * targetWidth
            for (x in 0 until targetWidth) {
                val left = min(x * 2, width - 1)
                val right = min(left + 1, width - 1)
                target[base + x] = (top[left] + top[right] + bottom[left] + bottom[right]) * 0.25f
            }
        }

        /** 2×2 box, the same exact reduction the *downsample* shader does. */
        fun reduce(
            source: FloatArray,
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): FloatArray {
            val target = FloatArray(targetWidth * targetHeight)
            for (y in 0 until targetHeight) {
                val top = min(y * 2, sourceHeight - 1)
                val bottom = min(top + 1, sourceHeight - 1)
                for (x in 0 until targetWidth) {
                    val left = min(x * 2, sourceWidth - 1)
                    val right = min(left + 1, sourceWidth - 1)
                    target[y * targetWidth + x] = 0.25f * (
                        source[top * sourceWidth + left] + source[top * sourceWidth + right] +
                            source[bottom * sourceWidth + left] + source[bottom * sourceWidth + right]
                        )
                }
            }
            return target
        }

        /**
         * Separable Gaussian, in place.
         *
         * The vertical pass advances with a ring of the original rows instead of copying the whole
         * plane: blur is the part of the export where it is easiest to run out of memory, and a ring of
         * `2·radius+1` rows costs a few kilobytes.
         */
        fun blurInPlace(data: FloatArray, width: Int, height: Int, sigma: Float) {
            val weights = PhotoEffects.halfGaussianKernel(sigma)
            val radius = weights.lastIndex
            if (radius < 1 || width < 1 || height < 1) return

            val line = FloatArray(width)
            for (y in 0 until height) {
                val base = y * width
                System.arraycopy(data, base, line, 0, width)
                for (x in 0 until width) {
                    var total = line[x] * weights[0]
                    for (offset in 1..radius) {
                        total += (line[max(x - offset, 0)] + line[min(x + offset, width - 1)]) * weights[offset]
                    }
                    data[base + x] = total
                }
            }

            // The last row is kept because, from a certain point, the window only wants the replicated
            // margin and by then the original row has already been replaced by the result.
            val edge = FloatArray(width)
            System.arraycopy(data, (height - 1) * width, edge, 0, width)

            val ringSize = radius * 2 + 1
            val ring = FloatArray(ringSize * width)
            for (index in -radius..radius) {
                val sourceRow = index.coerceIn(0, height - 1)
                System.arraycopy(data, sourceRow * width, ring, ringSlot(index, ringSize) * width, width)
            }

            val output = FloatArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var total = ring[ringSlot(y, ringSize) * width + x] * weights[0]
                    for (offset in 1..radius) {
                        val above = ring[ringSlot(y - offset, ringSize) * width + x]
                        val below = ring[ringSlot(y + offset, ringSize) * width + x]
                        total += (above + below) * weights[offset]
                    }
                    output[x] = total
                }
                System.arraycopy(output, 0, data, y * width, width)
                // The slot of `y - radius` is no longer needed and now hosts `y + radius + 1`, which is
                // still to be written and so is still the original row.
                val incoming = y + radius + 1
                val slot = ringSlot(incoming, ringSize) * width
                if (incoming < height) {
                    System.arraycopy(data, incoming * width, ring, slot, width)
                } else {
                    System.arraycopy(edge, 0, ring, slot, width)
                }
            }
        }

        private fun ringSlot(row: Int, ringSize: Int): Int = ((row % ringSize) + ringSize) % ringSize

        private fun pixelLuminance(argb: Int): Float = PhotoEffects.luminance(
            (argb ushr 16 and 0xFF) / 255f,
            (argb ushr 8 and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )

        /** Bilinear sampling clamped at the edges, the same as the GL sampler's. */
        fun sampleBilinear(data: FloatArray, width: Int, height: Int, u: Float, v: Float): Float {
            val x = u * width - 0.5f
            val y = v * height - 0.5f
            val left = kotlin.math.floor(x).toInt()
            val top = kotlin.math.floor(y).toInt()
            val fractionX = x - left
            val fractionY = y - top
            val x0 = left.coerceIn(0, width - 1)
            val x1 = (left + 1).coerceIn(0, width - 1)
            val y0 = top.coerceIn(0, height - 1)
            val y1 = (top + 1).coerceIn(0, height - 1)
            val topRow = data[y0 * width + x0] + (data[y0 * width + x1] - data[y0 * width + x0]) * fractionX
            val bottomRow = data[y1 * width + x0] + (data[y1 * width + x1] - data[y1 * width + x0]) * fractionX
            return topRow + (bottomRow - topRow) * fractionY
        }
    }
}

/** Dark channel and atmospheric light, the pair the *dark channel prior* needs. */
internal class DehazeStats private constructor(val dark: FloatArray, val airlight: Float) {
    companion object {
        fun build(
            bitmap: PixelSurface,
            quarterWidth: Int,
            quarterHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): DehazeStats {
            val quarterDark = FloatArray(quarterWidth * quarterHeight)
            val quarterBright = FloatArray(quarterWidth * quarterHeight)
            val width = bitmap.width
            val height = bitmap.height
            val row = IntArray(width)
            val redSum = FloatArray(quarterWidth)
            val greenSum = FloatArray(quarterWidth)
            val blueSum = FloatArray(quarterWidth)
            val counts = FloatArray(quarterWidth)

            var target = 0
            var rowsInBlock = 0
            for (y in 0 until height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val column = min(x / 4, quarterWidth - 1)
                    val pixel = row[x]
                    redSum[column] += (pixel ushr 16 and 0xFF) / 255f
                    greenSum[column] += (pixel ushr 8 and 0xFF) / 255f
                    blueSum[column] += (pixel and 0xFF) / 255f
                    counts[column] += 1f
                }
                rowsInBlock++
                if (rowsInBlock == 4 || y == height - 1) {
                    if (target < quarterHeight) {
                        val base = target * quarterWidth
                        for (column in 0 until quarterWidth) {
                            val total = max(counts[column], 1f)
                            val red = redSum[column] / total
                            val green = greenSum[column] / total
                            val blue = blueSum[column] / total
                            quarterDark[base + column] = PhotoEffects.darkChannel(red, green, blue)
                            quarterBright[base + column] = max(red, max(green, blue))
                        }
                    }
                    java.util.Arrays.fill(redSum, 0f)
                    java.util.Arrays.fill(greenSum, 0f)
                    java.util.Arrays.fill(blueSum, 0f)
                    java.util.Arrays.fill(counts, 0f)
                    rowsInBlock = 0
                    target++
                }
            }

            // Minimum and maximum over the 3×3 window, as in the statistics shader.
            val dark = FloatArray(targetWidth * targetHeight)
            val bright = FloatArray(targetWidth * targetHeight)
            for (y in 0 until targetHeight) {
                for (x in 0 until targetWidth) {
                    var minimum = 1f
                    var maximum = 0f
                    for (offsetY in -1..1) {
                        val sampleY = (y * 2 + offsetY).coerceIn(0, quarterHeight - 1)
                        for (offsetX in -1..1) {
                            val sampleX = (x * 2 + offsetX).coerceIn(0, quarterWidth - 1)
                            minimum = min(minimum, quarterDark[sampleY * quarterWidth + sampleX])
                            maximum = max(maximum, quarterBright[sampleY * quarterWidth + sampleX])
                        }
                    }
                    dark[y * targetWidth + x] = minimum
                    bright[y * targetWidth + x] = maximum
                }
            }

            return DehazeStats(dark, airlightOf(bright, targetWidth, targetHeight))
        }

        /** Per-block average on a 4×4 grid and the maximum of those averages — the same the mipmap gives. */
        private fun airlightOf(bright: FloatArray, width: Int, height: Int): Float {
            var airlight = 0f
            for (blockY in 0 until 4) {
                for (blockX in 0 until 4) {
                    val fromX = blockX * width / 4
                    val toX = max(((blockX + 1) * width / 4), fromX + 1).coerceAtMost(width)
                    val fromY = blockY * height / 4
                    val toY = max(((blockY + 1) * height / 4), fromY + 1).coerceAtMost(height)
                    var total = 0f
                    var count = 0
                    for (y in fromY until toY) {
                        for (x in fromX until toX) {
                            total += bright[y * width + x]
                            count++
                        }
                    }
                    if (count > 0) airlight = max(airlight, total / count)
                }
            }
            return max(airlight, 0.05f)
        }
    }
}
