package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.ColorLut

/**
 * Step 12b: a 3D colour lookup table read at one pixel, on the CPU.
 *
 * It is the transcription of what the GPU does — a trilinear read of a 3D texture whose first and last
 * texels sit on 0 and 1 — and not a better interpolation, so that the preview and the export give the
 * same pixel. The shader's `applyLut` and this class are the one definition; `PhotoShaderParityTest`
 * guards the shader's side of it.
 *
 * [samples] is [size]³ points of three channels, red changing fastest, as in [ColorLut].
 */
class Lut3D(val size: Int, val samples: FloatArray, val strength: Float) {
    init {
        require(samples.size == size * size * size * 3)
    }

    /** Replaces [rgb] (three values in 0..1, in place) with the look, [strength] of the way. */
    fun apply(rgb: FloatArray) {
        val scale = (size - 1).toFloat()
        val r = rgb[0].coerceIn(0f, 1f) * scale
        val g = rgb[1].coerceIn(0f, 1f) * scale
        val b = rgb[2].coerceIn(0f, 1f) * scale
        val r0 = minOf(r.toInt(), size - 2)
        val g0 = minOf(g.toInt(), size - 2)
        val b0 = minOf(b.toInt(), size - 2)
        val fr = r - r0
        val fg = g - g0
        val fb = b - b0
        val rowStep = size * 3
        val sliceStep = size * size * 3
        val base = b0 * sliceStep + g0 * rowStep + r0 * 3
        for (channel in 0..2) {
            val at = base + channel
            val c00 = samples[at] + (samples[at + 3] - samples[at]) * fr
            val c10 = samples[at + rowStep] + (samples[at + rowStep + 3] - samples[at + rowStep]) * fr
            val c01 = samples[at + sliceStep] + (samples[at + sliceStep + 3] - samples[at + sliceStep]) * fr
            val c11 = samples[at + sliceStep + rowStep] +
                (samples[at + sliceStep + rowStep + 3] - samples[at + sliceStep + rowStep]) * fr
            val c0 = c00 + (c10 - c00) * fg
            val c1 = c01 + (c11 - c01) * fg
            val looked = c0 + (c1 - c0) * fb
            val before = rgb[channel]
            rgb[channel] = (before + (looked - before) * strength).coerceIn(0f, 1f)
        }
    }

    companion object {
        private var last: Pair<ColorLut, FloatArray?>? = null

        /**
         * The table of [lut], or null if it has no amount or its table cannot be read: nothing to draw.
         *
         * The decoded grid of the last one is kept, whatever its amount: a slider drag builds new parameters
         * at every frame, and decoding 100 000 floats (and uploading them to the GPU) each time would be
         * work to learn what did not change.
         */
        fun of(lut: ColorLut?): Lut3D? {
            if (lut == null || lut.strength == 0f) return null
            val key = lut.copy(amount = 100f)
            val samples = synchronized(this) {
                val kept = last
                if (kept != null && kept.first == key) {
                    kept.second
                } else {
                    lut.samples().also { last = key to it }
                }
            } ?: return null
            return Lut3D(lut.size, samples, lut.strength)
        }
    }
}
