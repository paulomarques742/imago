package eu.studio742.imago.core.composition

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The model's colours are ARGB packed in a `Long` (`0xAARRGGBB`), and that is how the exporter wants
 * them — `android.graphics.Paint.color` is an `Int` in the same format.
 *
 * Choosing a colour, however, is not done in ARGB: nobody drags "red" and "blue" looking for a gold.
 * It is done in hue/saturation/value, and that is what these conversions are for. They live here,
 * in the model module and not in the interface, for two reasons: they are pure arithmetic, with no
 * Compose or Android in the mix, and therefore testable — and the last time the colour conversion
 * was done by hand inside the canvas it cost an `ArrayIndexOutOfBoundsException` for every shape
 * drawn.
 */
data class Hsva(
    /** In degrees, `0..360`. Red is at both ends. */
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float = 1f,
) {
    fun copyClamped() = Hsva(
        hue = ((hue % 360f) + 360f) % 360f,
        saturation = saturation.coerceIn(0f, 1f),
        value = value.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

private fun Long.channel(shift: Int): Int = ((this ushr shift) and 0xFF).toInt()

/** O canal alfa, `0..1`. */
fun Long.colorAlpha(): Float = channel(24) / 255f

/**
 * Converts ARGB to HSV.
 *
 * A grey has no defined hue — the formula divides by zero. Returning `0f` would make the hue cursor
 * jump to red whenever the value was dragged down to black, so the caller keeps the previous hue;
 * see [Hsva] in the picker.
 */
fun Long.toHsva(): Hsva {
    val r = channel(16) / 255f
    val g = channel(8) / 255f
    val b = channel(0) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta < 1e-6f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Hsva(
        hue = ((hue % 360f) + 360f) % 360f,
        saturation = if (max <= 0f) 0f else delta / max,
        value = max,
        alpha = colorAlpha(),
    ).copyClamped()
}

/** Converts HSV back to the packed ARGB the model stores. */
fun Hsva.toArgb(): Long {
    val (hue, saturation, value, alpha) = copyClamped()
    val chroma = value * saturation
    val secondary = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val match = value - chroma
    val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
        0 -> Triple(chroma, secondary, 0f)
        1 -> Triple(secondary, chroma, 0f)
        2 -> Triple(0f, chroma, secondary)
        3 -> Triple(0f, secondary, chroma)
        4 -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    return packArgb(
        alpha = ((alpha) * 255f).roundToInt(),
        red = ((r + match) * 255f).roundToInt(),
        green = ((g + match) * 255f).roundToInt(),
        blue = ((b + match) * 255f).roundToInt(),
    )
}

/** Builds an ARGB from the four channels in `0..255`, clamping whatever falls outside the range. */
fun packArgb(alpha: Int, red: Int, green: Int, blue: Int): Long =
    (alpha.coerceIn(0, 255).toLong() shl 24) or
        (red.coerceIn(0, 255).toLong() shl 16) or
        (green.coerceIn(0, 255).toLong() shl 8) or
        blue.coerceIn(0, 255).toLong()

/** Replaces only the alpha, keeping the colour. Used by the picker's opacity cursor. */
fun Long.withColorAlpha(alpha: Float): Long =
    packArgb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), channel(16), channel(8), channel(0))

/** `#RRGGBB`, or `#AARRGGBB` when the colour is not opaque — it is what is written and what is read. */
fun Long.toColorHex(): String {
    val rgb = "%02X%02X%02X".format(channel(16), channel(8), channel(0))
    return if (channel(24) == 255) "#$rgb" else "#%02X%s".format(channel(24), rgb)
}

/**
 * Reads `#RGB`, `#RRGGBB` or `#AARRGGBB`, with or without the hash. Returns `null` for what it does
 * not understand, instead of a wrong colour — the caller keeps the previous value while typing.
 */
fun parseColorHex(text: String): Long? {
    val digits = text.trim().removePrefix("#")
    if (digits.any { it.digitToIntOrNull(16) == null }) return null
    val expanded = when (digits.length) {
        3 -> digits.flatMap { listOf(it, it) }.joinToString("")
        6 -> digits
        8 -> return digits.toLongOrNull(16)
        else -> return null
    }
    return expanded.toLongOrNull(16)?.let { 0xFF000000L or it }
}
