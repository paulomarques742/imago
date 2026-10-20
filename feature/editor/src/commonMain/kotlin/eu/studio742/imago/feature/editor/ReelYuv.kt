package eu.studio742.imago.feature.editor

/**
 * ARGB to YUV 4:2:0 with the BT.709 matrix in limited range — what players assume for HD video —
 * into three planes without padding: [y] is width × height, [u] and [v] a quarter of that.
 *
 * The coefficients are in 256ths, and each chroma row sums to zero so that a grey stays grey.
 *
 * The chroma is the average of each 2 × 2 block: taking one pixel of four put coloured fringes on
 * the edges of the sliders' text.
 */
internal fun argbToYuv709(argb: IntArray, width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
    for (index in 0 until width * height) {
        val pixel = argb[index]
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        y[index] = (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).toByte()
    }
    val chromaWidth = width / 2
    for (cy in 0 until height / 2) {
        for (cx in 0 until chromaWidth) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0..1) {
                val row = (cy * 2 + dy) * width + cx * 2
                for (dx in 0..1) {
                    val pixel = argb[row + dx]
                    r += pixel shr 16 and 0xFF
                    g += pixel shr 8 and 0xFF
                    b += pixel and 0xFF
                }
            }
            r /= 4
            g /= 4
            b /= 4
            u[cy * chromaWidth + cx] = (((-26 * r - 86 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            v[cy * chromaWidth + cx] = (((112 * r - 102 * g - 10 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
        }
    }
}
