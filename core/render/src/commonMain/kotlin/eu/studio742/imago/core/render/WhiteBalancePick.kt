package eu.studio742.imago.core.render

import kotlin.math.max

/** Temperature and Tint, in the sliders' units (−100..100), that make the picked colour grey. */
data class NeutralWhiteBalance(val temperature: Float, val tint: Float)

/**
 * The white balance that turns the colour of [pixels] — ARGB, as the photo is before any adjustment —
 * neutral, as Lightroom's White Balance Selector does: the area's colour, averaged in linear light, is
 * taken as the light, and [WhiteBalanceModel.slidersFor] finds the Temperature and Tint of that light.
 * Exposure and the Whites/Blacks lift come after and treat the three channels alike, so they leave a
 * grey grey.
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
    val (temperature, tint) = WhiteBalanceModel.slidersFor(red, green, blue)
    return NeutralWhiteBalance(temperature = temperature * 100f, tint = tint * 100f)
}

/** An 8-bit channel at or above this has clipped somewhere in the area. */
private const val BURNT_OUT = 250

/** Linear light under which the brightest channel is mostly noise. */
private const val TOO_DARK = 0.004f
