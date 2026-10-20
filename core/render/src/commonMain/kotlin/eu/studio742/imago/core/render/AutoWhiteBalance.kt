package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightroom's Auto white balance for a photo: the light that lit it, estimated from the photo itself,
 * and the Temperature and Tint that take it away.
 *
 * Lightroom does not publish how it estimates the light, and this was not measured against it. It is
 * the grey-pixel idea (Huo et al., 2006) — the light is the colour of the pixels that look grey under
 * it — started from the lights that exist. Every pixel is a colour in log-chromaticity, where a light
 * moves all of them by the same step; each light along the Planckian locus, from 2000 K to 25 000 K,
 * weighs the pixels it would leave grey, and the heaviest is where the search starts. Then a few rounds
 * average the pixels near grey under the estimate, with the tolerance narrowing, which also lets it
 * leave the locus for a green or magenta light.
 *
 * A pixel weighs by the texture around it, as in the grey-edge methods (van de Weijer et al., 2007):
 * the stones, walls and fabrics of a scene are what carry the light's colour, and a clear sky is the
 * colour of a light of 15 000 K without being grey — counted by area, it turned every landscape warm.
 * Starting from the average of the photo instead let a lawn or a red wall pass for the light; a lawn
 * leaves no grey under any real light.
 *
 * It reads the photo before any adjustment, in linear light, as the White Balance Selector does, and
 * leaves out what says nothing about the light: burnt-out pixels, whose colour is lost, and those too
 * dark to have one. The Tint is held within [AUTO_TINT_LIMIT]: real lights sit near the locus.
 *
 * Null when nothing in the photo can be read.
 */
fun autoWhiteBalanceOf(photo: PixelBuffer): NeutralWhiteBalance? {
    val reduced = RecipePixels.reduce(photo, AUTO_SIDE)
    val width = reduced.width
    val height = reduced.height
    val logLuminance = FloatArray(width * height) { index ->
        val argb = reduced.pixels[index]
        val luminance = 0.2126f * BitmapPhotoProcessor.srgbToLinear((argb ushr 16 and 0xFF) / 255f) +
            0.7152f * BitmapPhotoProcessor.srgbToLinear((argb ushr 8 and 0xFF) / 255f) +
            0.0722f * BitmapPhotoProcessor.srgbToLinear((argb and 0xFF) / 255f)
        ln(luminance + LUMINANCE_FLOOR)
    }
    // Per readable pixel: log(red / green), log(blue / green) and its weight.
    val chroma = FloatArray(width * height * 3)
    var count = 0
    var total = 0.0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val argb = reduced.pixels[index]
            val r = argb ushr 16 and 0xFF
            val g = argb ushr 8 and 0xFF
            val b = argb and 0xFF
            if (max(r, max(g, b)) >= BURNT_OUT) continue
            val red = BitmapPhotoProcessor.srgbToLinear(r / 255f)
            val green = BitmapPhotoProcessor.srgbToLinear(g / 255f)
            val blue = BitmapPhotoProcessor.srgbToLinear(b / 255f)
            if (max(red, max(green, blue)) < TOO_DARK || red <= 0f || green <= 0f || blue <= 0f) continue
            val across = if (x + 1 < width) abs(logLuminance[index + 1] - logLuminance[index]) else 0f
            val down = if (y + 1 < height) abs(logLuminance[index + width] - logLuminance[index]) else 0f
            // Brighter pixels count for more as well: a white cloud or a wall in the sun is the surest
            // grey, and its colour the least noisy.
            val weight = (across + down) * sqrt(0.2126f * red + 0.7152f * green + 0.0722f * blue)
            if (weight <= 0f) continue
            chroma[count * 3] = ln(red / green)
            chroma[count * 3 + 1] = ln(blue / green)
            chroma[count * 3 + 2] = weight
            total += weight
            count++
        }
    }
    if (count == 0) return null

    fun distance(index: Int, logRed: Float, logBlue: Float): Float {
        val towardsRed = chroma[index * 3] - logRed
        val towardsBlue = chroma[index * 3 + 1] - logBlue
        return sqrt(towardsRed * towardsRed + towardsBlue * towardsBlue)
    }

    var logRed = 0f
    var logBlue = 0f
    var bestSupport = -1.0
    var bestStep = 0
    for (step in -LOCUS_STEPS..LOCUS_STEPS) {
        val light = WhiteBalanceModel.lightColour(step / LOCUS_STEPS.toFloat(), 0f)
        val candidateRed = ln(light[0])
        val candidateBlue = ln(light[2])
        var support = 0.0
        for (index in 0 until count) {
            if (distance(index, candidateRed, candidateBlue) < VOTE_TOLERANCE) support += chroma[index * 3 + 2]
        }
        support *= coldPrior(step / LOCUS_STEPS.toFloat())
        // On a tie the light nearer daylight wins: it is the one that corrects least.
        if (support > bestSupport || (support == bestSupport && abs(step) < abs(bestStep))) {
            bestSupport = support
            bestStep = step
            logRed = candidateRed
            logBlue = candidateBlue
        }
    }
    // Nothing in the photo is grey under any real light — a sunset, a lawn: Auto leaves it as shot.
    if (bestSupport < total * MIN_SUPPORT) return NeutralWhiteBalance(0f, 0f)

    for (tolerance in TOLERANCES) {
        var red = 0.0
        var blue = 0.0
        var weight = 0.0
        for (index in 0 until count) {
            if (distance(index, logRed, logBlue) >= tolerance) continue
            val pixelWeight = chroma[index * 3 + 2].toDouble()
            red += chroma[index * 3] * pixelWeight
            blue += chroma[index * 3 + 1] * pixelWeight
            weight += pixelWeight
        }
        // Too little left to stand for the light: the previous estimate is as far as the photo goes.
        if (weight < total * MIN_SUPPORT || weight <= 0.0) break
        logRed = (red / weight).toFloat()
        logBlue = (blue / weight).toFloat()
    }

    val (temperature, tint) = WhiteBalanceModel.slidersFor(exp(logRed), 1f, exp(logBlue))
    return NeutralWhiteBalance(
        temperature = temperature * 100f,
        tint = (tint * 100f).coerceIn(-AUTO_TINT_LIMIT, AUTO_TINT_LIMIT),
    )
}

/**
 * How much less a light colder than daylight is believed. Water, sky and their edges with clouds are
 * blue the way a cold light makes a grey blue, and a photo lit by a light far colder than shade is
 * rare: without this, every seascape came out orange. Warm lights — tungsten, candles, sunsets — are
 * common, and keep their full weight.
 */
private fun coldPrior(temperature: Float): Double {
    if (temperature <= 0f) return 1.0
    val reach = temperature / COLD_PRIOR_REACH
    return exp(-(reach * reach).toDouble())
}

/**
 * This recipe on a photo whose Auto white balance is [found]: an Auto white balance takes the values
 * found there, and a recipe with any other leaves them as they were. Nothing found leaves As Shot's.
 */
fun EditRecipe.withAutoWhiteBalance(found: NeutralWhiteBalance?): EditRecipe {
    if (!whiteBalance.auto) return this
    return copy(whiteBalance = whiteBalance.copy(temp = found?.temperature ?: 0f, tint = found?.tint ?: 0f))
}

/** The photo is read at this size: the light is the same in every pixel, and the method is a few passes. */
private const val AUTO_SIDE = 400

/** The lights along the locus that vote: this many on each side of daylight, one per slider step of 2. */
private const val LOCUS_STEPS = 50

/** At this Temperature of the light's correction (+25, about 8000 K, shade) a cold light counts a third. */
private const val COLD_PRIOR_REACH = 0.25f

/** In log-chromaticity, where 0.1 is about 10 % between two channels. */
private const val VOTE_TOLERANCE = 0.08f
private val TOLERANCES = floatArrayOf(0.12f, 0.08f, 0.06f, 0.06f)

/** The share of the photo's texture the light needs behind it. */
private const val MIN_SUPPORT = 0.02

/** Keeps the log of a black pixel finite, and the noise of the shadows from passing for texture. */
private const val LUMINANCE_FLOOR = 0.01f

/** The selector's limits: a channel at or above this has clipped, and below this linear light is noise. */
private const val BURNT_OUT = 250
private const val TOO_DARK = 0.004f

/** In the slider's units, out of ±100. */
private const val AUTO_TINT_LIMIT = 30f
