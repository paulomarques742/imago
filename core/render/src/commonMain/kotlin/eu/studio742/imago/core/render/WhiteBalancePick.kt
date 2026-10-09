package eu.studio742.imago.core.render

import kotlin.math.max
import kotlin.math.sqrt

/** Temperature and Tint, in the sliders' units (−100..100), that make the picked colour grey. */
data class NeutralWhiteBalance(val temperature: Float, val tint: Float)

/**
 * The white balance that turns the colour of [pixels] — ARGB, as the photo is before any adjustment —
 * neutral, as Lightroom's White Balance Selector does.
 *
 * It inverts the multipliers of [BitmapPhotoProcessor.applyBaseTone] (and of the shader's copy), in
 * linear light, where they act:
 *
 *     red   × (1 + 0.20 T)(1 + 0.08 τ)
 *     green × (1 + 0.05 τ)(1 − 0.10 τ)
 *     blue  × (1 − 0.20 T)(1 + 0.08 τ)
 *
 * Red and blue meet through the temperature alone, and then the tint brings green to them: one linear
 * equation and one quadratic. Exposure and the Whites/Blacks lift come after and treat the three
 * channels alike, so they leave a grey grey.
 *
 * Null when the area says nothing about the light: too dark to tell a colour, or a channel burnt out,
 * where the true colour is lost.
 */
fun neutralWhiteBalanceOf(pixels: IntArray): NeutralWhiteBalance? {
    if (pixels.isEmpty()) return null
    var red = 0f
    var green = 0f
    var blue = 0f
    var brightest = 0
    for (argb in pixels) {
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        brightest = max(brightest, max(r, max(g, b)))
        red += BitmapPhotoProcessor.srgbToLinear(r / 255f)
        green += BitmapPhotoProcessor.srgbToLinear(g / 255f)
        blue += BitmapPhotoProcessor.srgbToLinear(b / 255f)
    }
    red /= pixels.size
    green /= pixels.size
    blue /= pixels.size
    if (brightest >= BURNT_OUT || max(red, max(green, blue)) < TOO_DARK) return null

    val temperature = ((blue - red) / (TEMPERATURE_GAIN * (blue + red))).coerceIn(-1f, 1f)
    // Where red and blue meet; with the temperature at an end of its range they no longer do, and the
    // tint aims between them.
    val meeting = (red * (1f + TEMPERATURE_GAIN * temperature) + blue * (1f - TEMPERATURE_GAIN * temperature)) / 2f
    // green (1 + 0.05 τ)(1 − 0.10 τ) = meeting (1 + 0.08 τ), as a τ² + b τ + c = 0.
    val a = -0.005f * green
    val b = -(0.05f * green + 0.08f * meeting)
    val c = green - meeting
    val discriminant = (b * b - 4f * a * c).coerceAtLeast(0f)
    // The root near zero, written so that a tiny `a` does not cancel it away.
    val tint = (2f * c / (-b + sqrt(discriminant))).coerceIn(-1f, 1f)
    return NeutralWhiteBalance(temperature = temperature * 100f, tint = tint * 100f)
}

private const val TEMPERATURE_GAIN = 0.20f

/** An 8-bit channel at or above this has clipped somewhere in the area. */
private const val BURNT_OUT = 250

/** Linear light under which the brightest channel is mostly noise. */
private const val TOO_DARK = 0.004f
