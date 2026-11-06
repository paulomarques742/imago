package eu.studio742.imago.core.raw

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The lens corrections a camera writes into its RAW for its own JPEG: distortion, lateral chromatic
 * aberration and vignetting, as radial curves sampled at [knots] evenly spaced radii.
 *
 * Sony keeps them in the raw image's IFD as `VignettingCorrParams` (0x7032),
 * `ChromaticAberrationCorrParams` (0x7035) and `DistortionCorrParams` (0x7037); a DNG converted from
 * an ARW may carry the same tags in IFD0. Each is a count followed by signed 16-bit values: [knots]
 * for distortion and vignetting, twice as many for the aberration (red, then blue). Sony applies them
 * to the JPEG only, never to the sensor data, so developing the RAW without them shows the lens as it
 * is. The scales and the radius are those darktable uses for the same tags (its "embedded metadata"
 * lens correction, algorithm version 2); LibRaw does not read these tags at all.
 *
 * The native development applies the aberration to a Bayer sensor's red and blue sites before
 * demosaicing, and the distortion and vignetting after it, in the camera's channels. Measured on an
 * ILCE-6700 ARW against the JPEG it carries, moving the demosaiced channels instead left most of the
 * blue fringe or overcorrected the red one: the interpolation borrows their detail from green.
 */
class EmbeddedLensCorrection(
    val distortion: IntArray,
    val chromaRed: IntArray,
    val chromaBlue: IntArray,
    val vignetting: IntArray,
) {
    init {
        require(distortion.size in 2..MAX_KNOTS)
        require(chromaRed.size == distortion.size && chromaBlue.size == distortion.size && vignetting.size == distortion.size)
    }

    val knots: Int get() = distortion.size

    /** The radius of knot [index], as a fraction of the half-diagonal. */
    fun knot(index: Int): Float = (index + 0.5f) / (knots - 1)

    /**
     * How far from the centre, relative to the corrected point's own radius, channel [channel] (0 red,
     * 1 green, 2 blue) is sampled at knot [index], before [scale]. Below 1 the lens squeezed the edges
     * in (barrel), and correcting it spreads them out.
     */
    fun factor(channel: Int, index: Int): Float {
        val green = 1f + distortion[index] * DISTORTION_UNIT
        return when (channel) {
            0 -> green * (1f + chromaRed[index] * CHROMA_UNIT)
            2 -> green * (1f + chromaBlue[index] * CHROMA_UNIT)
            else -> green
        }
    }

    /** The light the lens kept at knot [index]'s radius of the original, 1 at the centre: dividing by it lifts the corners. */
    fun gain(index: Int): Float = 2f.pow(0.5f - 2f.pow(vignetting[index] * VIGNETTING_UNIT - 1f))

    /**
     * What the native development reads: the knot count, the knots, each channel's factors and the gains.
     * The native side scales the distortion for the image it develops ([scale]), which only it knows.
     */
    fun table(): FloatArray {
        val n = knots
        val out = FloatArray(1 + n * 5)
        out[0] = n.toFloat()
        for (i in 0 until n) {
            out[1 + i] = knot(i)
            for (channel in 0..2) out[1 + n * (1 + channel) + i] = factor(channel, i)
            out[1 + n * 4 + i] = gain(i)
        }
        return out
    }

    /**
     * The zoom that keeps the corrected frame filled: the largest factor between the middle of the short
     * side and the corner, over all three channels, so that no edge point samples outside the original.
     * The native side computes the same, with the same [SCALE_SAMPLES].
     */
    fun scale(width: Int, height: Int): Float {
        val shortSide = min(width, height) / hypot(width.toFloat(), height.toFloat())
        var scale = 0f
        for (i in 0 until SCALE_SAMPLES) {
            val radius = shortSide + (1f - shortSide) * i / (SCALE_SAMPLES - 1f)
            for (channel in 0..2) scale = max(scale, interpolate(radius) { factor(channel, it) })
        }
        return scale
    }

    /**
     * Where, relative to the corrected point's radius, channel [channel] is sampled, for a point at
     * [radius] (a fraction of the half-diagonal) of a frame zoomed by [scale]. The knots move with the
     * zoom and the factors shrink by it, as darktable does. Green's is the distortion of every channel
     * of a Bayer sensor, whose aberration was taken out of the sites; red's and blue's are what the other
     * sensors are sampled with.
     */
    fun sampledFactor(channel: Int, radius: Float, scale: Float): Float =
        interpolate(radius, knotScale = scale) { factor(channel, it) } / scale

    /** The vignetting gain at [radius] of the original, unaffected by the zoom. */
    fun gainAt(radius: Float): Float = interpolate(radius) { gain(it) }

    private inline fun interpolate(radius: Float, knotScale: Float = 1f, value: (Int) -> Float): Float {
        if (radius < knot(0) * knotScale) return value(0)
        for (i in 1 until knots) {
            val low = knot(i - 1) * knotScale
            val high = knot(i) * knotScale
            if (radius <= high) return value(i - 1) + (radius - low) * (value(i) - value(i - 1)) / (high - low)
        }
        return value(knots - 1)
    }

    companion object {
        const val MAX_KNOTS = 16
        const val SCALE_SAMPLES = 200
        private val DISTORTION_UNIT = 2f.pow(-14)
        private val CHROMA_UNIT = 2f.pow(-21)
        private val VIGNETTING_UNIT = 2f.pow(-13)

        private const val VIGNETTING_TAG = 0x7032
        private const val CHROMA_TAG = 0x7035
        private const val DISTORTION_TAG = 0x7037
        private const val SUB_IFDS_TAG = 0x014A

        /**
         * The corrections [file] carries, or null when it has none, they are inconsistent, or the file
         * is not TIFF-based. Reads only the directories, never the image data.
         */
        fun read(file: File): EmbeddedLensCorrection? = try {
            RandomAccessFile(file, "r").use { TiffDirectories(it).find() }
        } catch (_: java.io.IOException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }

        /** From the three tags' values, each with its leading count; null when they do not agree. */
        internal fun fromTags(distortion: IntArray?, chroma: IntArray?, vignetting: IntArray?): EmbeddedLensCorrection? {
            if (distortion == null || chroma == null || vignetting == null) return null
            val n = distortion.firstOrNull() ?: return null
            if (n !in 2..MAX_KNOTS || chroma.firstOrNull() != 2 * n || vignetting.firstOrNull() != n) return null
            if (distortion.size < n + 1 || chroma.size < 2 * n + 1 || vignetting.size < n + 1) return null
            return EmbeddedLensCorrection(
                distortion = IntArray(n) { distortion[1 + it] },
                chromaRed = IntArray(n) { chroma[1 + it] },
                chromaBlue = IntArray(n) { chroma[1 + n + it] },
                vignetting = IntArray(n) { vignetting[1 + it] },
            )
        }

        /** The directories of a TIFF-based file: IFD0 and the sub-IFDs it points to, the raw image's among them. */
        private class TiffDirectories(private val input: RandomAccessFile) {
            private var littleEndian = true

            fun find(): EmbeddedLensCorrection? {
                if (input.length() < 8) return null
                val order = ByteArray(2).also { input.readFully(it) }
                littleEndian = when {
                    order[0] == 'I'.code.toByte() && order[1] == 'I'.code.toByte() -> true
                    order[0] == 'M'.code.toByte() && order[1] == 'M'.code.toByte() -> false
                    else -> return null
                }
                if (u16(2) != 42) return null
                val first = u32(4)
                val directory = entries(first) ?: return null
                corrections(directory)?.let { return it }
                val subIfds = directory[SUB_IFDS_TAG]?.let { values(it, unsigned = true) } ?: return null
                for (offset in subIfds.take(MAX_SUB_IFDS)) {
                    val sub = entries(offset.toLong() and 0xFFFFFFFFL) ?: continue
                    corrections(sub)?.let { return it }
                }
                return null
            }

            private fun corrections(directory: Map<Int, Entry>): EmbeddedLensCorrection? = fromTags(
                directory[DISTORTION_TAG]?.let { values(it, unsigned = false) },
                directory[CHROMA_TAG]?.let { values(it, unsigned = false) },
                directory[VIGNETTING_TAG]?.let { values(it, unsigned = false) },
            )

            private class Entry(val type: Int, val count: Long, val at: Long)

            private fun entries(offset: Long): Map<Int, Entry>? {
                if (offset < 8 || offset + 2 > input.length()) return null
                val count = u16(offset)
                if (count == 0 || offset + 2 + count * 12L > input.length()) return null
                return (0 until count).associate { i ->
                    val at = offset + 2 + i * 12L
                    u16(at) to Entry(type = u16(at + 2), count = u32(at + 4), at = at + 8)
                }
            }

            /**
             * Short or long integers, at most [MAX_VALUES]. Sony writes the corrections as SSHORT; a SHORT
             * is read as signed too, since a negative distortion cannot be stored any other way.
             */
            private fun values(entry: Entry, unsigned: Boolean): IntArray? {
                val size = when (entry.type) {
                    3, 8 -> 2
                    4, 9, 13 -> 4
                    else -> return null
                }
                if (entry.count !in 1..MAX_VALUES.toLong()) return null
                val bytes = entry.count * size
                val start = if (bytes <= 4) entry.at else u32(entry.at)
                if (start < 0 || start + bytes > input.length()) return null
                return IntArray(entry.count.toInt()) { i ->
                    val at = start + i * size
                    when {
                        size == 2 && unsigned && entry.type == 3 -> u16(at)
                        size == 2 -> u16(at).toShort().toInt()
                        else -> u32(at).toInt()
                    }
                }
            }

            private fun u16(at: Long): Int {
                input.seek(at)
                val a = input.read()
                val b = input.read()
                if (a < 0 || b < 0) throw java.io.EOFException()
                return if (littleEndian) a or (b shl 8) else (a shl 8) or b
            }

            private fun u32(at: Long): Long {
                val low = u16(if (littleEndian) at else at + 2).toLong()
                val high = u16(if (littleEndian) at + 2 else at).toLong()
                return low or (high shl 16)
            }
        }

        private const val MAX_SUB_IFDS = 8
        private const val MAX_VALUES = 64
    }
}
