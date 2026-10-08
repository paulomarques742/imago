package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS

/**
 * The CPU counterpart of the preview shader, used to produce the export bitmap.
 *
 * The work is split into two passes because the pipeline is too: the first applies steps 1 to 8,
 * which are purely per pixel; the second applies steps 9 and 13, which need to see the
 * neighbourhood and so can only run after the whole image has been toned.
 */
object BitmapPhotoProcessor {
    /** Opaque white, as `PhotoShaders.COMPOSITE` writes it. */
    private const val OUTSIDE_COLOR = -0x1

    /**
     * @param geometry the bridge between this bitmap and the original image, needed because the
     *   masks live in image coordinates and this bitmap already comes cropped and rotated. The caller
     *   has to build it **before** applying the geometry, which is when the original dimensions still
     *   exist. Without it, no geometry is assumed, and then the frame is the image and this bitmap's
     *   dimensions are the right ones.
     * @param renderScale how many pixels of this bitmap fit in one image pixel. It is one on export,
     *   which draws the image at its resolution, and less than one in a preview draft. Only grain uses
     *   it, and it uses it because it lives in image pixels: without this, the draft showed coarse the
     *   granularity the file shows fine, and the preview changed look when full resolution replaced it.
     */
    fun <T : PixelSurface> renderInPlace(
        bitmap: T,
        parameters: RenderParameters,
        geometry: FrameGeometry? = null,
        renderScale: Float = 1f,
    ): T {
        if (!parameters.isColorNeutral) process(bitmap, parameters, geometry, renderScale)
        geometry?.takeIf { it.showsOutside }?.let { paintOutside(bitmap, it) }
        return bitmap
    }

    /**
     * What the perspective leaves uncovered, white — after the processing, so that exposure or a
     * vignette never turn it grey. The shader does the same by returning white before any of it.
     */
    private fun paintOutside(bitmap: PixelSurface, geometry: FrameGeometry) {
        val width = bitmap.width
        val row = IntArray(width)
        val point = FloatArray(2)
        for (y in 0 until bitmap.height) {
            var touched = false
            for (x in 0 until width) {
                geometry.imageFromFramed((x + .5f) / width, (y + .5f) / bitmap.height, point)
                if (!geometry.isOutside(point)) continue
                if (!touched) {
                    bitmap.getPixels(row, 0, width, 0, y, width, 1)
                    touched = true
                }
                row[x] = OUTSIDE_COLOR
            }
            if (touched) bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private fun <T : PixelSurface> process(
        bitmap: T,
        parameters: RenderParameters,
        geometry: FrameGeometry?,
        renderScale: Float,
    ) {
        val field = if (parameters.needsMaskField) {
            LocalMaskField.build(
                framedWidth = bitmap.width,
                framedHeight = bitmap.height,
                parameters = parameters,
                geometry = geometry ?: FrameGeometry(bitmap.width, bitmap.height),
            )
        } else {
            null
        }
        applyTonePass(bitmap, parameters, field)
        if (parameters.needsDetailStage || parameters.needsColorGradingStage || parameters.needsEffectsStage) {
            val pyramid = if (parameters.needsDetailStage) {
                DetailPyramid.build(bitmap, withDehaze = parameters.needsDehazeStats)
            } else {
                null
            }
            applyFinishPass(bitmap, parameters, pyramid, field, renderScale)
        }
    }

    /**
     * Steps 1 to 8.
     *
     * The local adaptation mask has to be built before this pass, and not during it: it describes the
     * image as it enters step 8, and the pass writes over the bitmap as it goes. Building it halfway
     * would mean the last rows read an already toned neighbourhood and the first ones did not.
     */
    private fun applyTonePass(
        bitmap: PixelSurface,
        parameters: RenderParameters,
        field: LocalMaskField?,
    ) {
        val mask = if (parameters.needsLocalToneMask) {
            LocalToneMask.build(bitmap, parameters, field)
        } else {
            null
        }
        val width = bitmap.width
        val height = bitmap.height
        // Each pixel only depends on itself: the strips do not see each other. The buffers and the
        // effective tone, which the masks rewrite for every pixel, belong to each strip.
        forEachRowBand(bitmap) { fromRow, untilRow ->
            val row = IntArray(width)
            val weights = FloatArray(MAX_LOCAL_MASKS)
            val tone = parameters.effectiveTone()
            for (y in fromRow until untilRow) {
                val v = (y + 0.5f) / height
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val u = (x + 0.5f) / width
                    if (field != null) field.resolve(parameters, u, v, weights, tone)
                    val base = mask?.at(u, v) ?: NO_LOCAL_BASE
                    row[x] = processPixel(row[x], parameters, tone, base)
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
        }
    }

    /**
     * Steps 9, 12 and 13.
     *
     * Texture's fine term is a 3×3 *tent* and requires three rows of the bitmap at hand at the same
     * time; the other inputs come from the pyramid, sampled bilinearly as the GL sampler would.
     */
    private fun applyFinishPass(
        bitmap: PixelSurface,
        parameters: RenderParameters,
        pyramid: DetailPyramid?,
        field: LocalMaskField?,
        renderScale: Float,
    ) {
        val width = bitmap.width
        val height = bitmap.height
        // The grain cell is measured in image pixels; what matters here is how much it measures in
        // pixels of this bitmap, which decides both the position and whether the particle still resolves.
        val grainStep = PhotoEffects.grainCellSize(parameters.grainSize) * max(renderScale, 0.0001f)
        val grainGain = GRAIN_AMPLITUDE * PhotoEffects.grainSizeGain(parameters.grainSize)
        val grade = parameters.colorGrade
        val aspect = width.toFloat() / max(height, 1)
        val airlight = pyramid?.airlight ?: 1f
        // The tent reads the row above and the row below as they came out of the first pass. On a
        // single strip, the row below has not been written yet when it is read; in parallel, the
        // neighbour may already have written it — so there the reads come from a copy, and the result
        // is the same pixel for pixel.
        val input: PixelSurface = if (rowsRunInParallel(bitmap)) (bitmap as PixelBuffer).copy() else bitmap

        forEachRowBand(bitmap) { fromRow, untilRow ->
            val above = IntArray(width)
            val current = IntArray(width)
            val below = IntArray(width)
            val output = IntArray(width)
            // Step 12 works on the whole triple and not channel by channel — a wheel's chroma has
            // components of different signs. The buffer belongs to the strip, not to the pixel.
            val graded = FloatArray(3)

            val weights = FloatArray(MAX_LOCAL_MASKS)
            val tone = parameters.effectiveTone()
            var texture = parameters.texture / 100f
            var clarity = parameters.clarity / 100f
            var dehaze = parameters.dehaze / 100f

            input.getPixels(current, 0, width, 0, fromRow, width, 1)
            if (fromRow > 0) input.getPixels(above, 0, width, 0, fromRow - 1, width, 1) else System.arraycopy(current, 0, above, 0, width)
            if (fromRow + 1 < height) input.getPixels(below, 0, width, 0, fromRow + 1, width, 1) else System.arraycopy(current, 0, below, 0, width)

            for (y in fromRow until untilRow) {
                val v = (y + 0.5f) / height
                for (x in 0 until width) {
                    val pixel = current[x]
                    var red = (pixel ushr 16 and 0xFF) / 255f
                    var green = (pixel ushr 8 and 0xFF) / 255f
                    var blue = (pixel and 0xFF) / 255f
                    val u = (x + 0.5f) / width

                    // Texture, clarity and dehaze are local; the rest of step 13 is not. The
                    // atmospheric light stays global on purpose — it is a statistic of the whole
                    // scene, not a property of this pixel.
                    if (field != null) {
                        field.resolve(parameters, u, v, weights, tone)
                        texture = tone.texture / 100f
                        clarity = tone.clarity / 100f
                        dehaze = tone.dehaze / 100f
                    }

                    if (pyramid != null) {
                        val value = PhotoEffects.luminance(red, green, blue)
                        val band = pyramid.bandAt(u, v)
                        val coarse = pyramid.coarseAt(u, v)
                        val fine = if (texture == 0f) value else tentLuminance(above, current, below, x, width)
                        val target = PhotoEffects.detailLuminance(value, fine, band, coarse, texture, clarity)
                        if (value > 0.00001f) {
                            val scale = target / value
                            red = (red * scale).coerceIn(0f, 1f)
                            green = (green * scale).coerceIn(0f, 1f)
                            blue = (blue * scale).coerceIn(0f, 1f)
                        }
                        if (dehaze != 0f) {
                            val transmission = PhotoEffects.transmission(pyramid.darkAt(u, v), airlight)
                            red = PhotoEffects.dehazeChannel(red, airlight, transmission, dehaze)
                            green = PhotoEffects.dehazeChannel(green, airlight, transmission, dehaze)
                            blue = PhotoEffects.dehazeChannel(blue, airlight, transmission, dehaze)
                        }
                    }

                    if (grade.isActive) {
                        graded[0] = red
                        graded[1] = green
                        graded[2] = blue
                        PhotoEffects.applyColorGrading(graded, grade)
                        red = graded[0]
                        green = graded[1]
                        blue = graded[2]
                    }

                    if (parameters.vignetteAmount != 0f) {
                        val falloff = PhotoEffects.vignetteFalloff(
                            x = u,
                            y = v,
                            aspect = aspect,
                            midpoint = parameters.vignetteMidpoint,
                            roundness = parameters.vignetteRoundness,
                            feather = parameters.vignetteFeather,
                        )
                        red = PhotoEffects.vignetteChannel(red, parameters.vignetteAmount, falloff)
                        green = PhotoEffects.vignetteChannel(green, parameters.vignetteAmount, falloff)
                        blue = PhotoEffects.vignetteChannel(blue, parameters.vignetteAmount, falloff)
                    }

                    if (parameters.grainAmount != 0f) {
                        val noise = PhotoEffects.grainField(
                            x = u * width / grainStep,
                            y = v * height / grainStep,
                            roughness = parameters.grainRoughness,
                            pixelInCells = 1f / grainStep,
                        )
                        val strength = (parameters.grainAmount / 100f).coerceIn(0f, 1f) * grainGain *
                            PhotoEffects.grainWeight(PhotoEffects.luminance(red, green, blue))
                        red = (red + noise * strength).coerceIn(0f, 1f)
                        green = (green + noise * strength).coerceIn(0f, 1f)
                        blue = (blue + noise * strength).coerceIn(0f, 1f)
                    }

                    output[x] = (pixel and 0xFF000000.toInt()) or
                        ((red * 255f + 0.5f).toInt() shl 16) or
                        ((green * 255f + 0.5f).toInt() shl 8) or
                        (blue * 255f + 0.5f).toInt()
                }
                bitmap.setPixels(output, 0, width, 0, y, width, 1)

                System.arraycopy(current, 0, above, 0, width)
                System.arraycopy(below, 0, current, 0, width)
                val nextRow = y + 2
                if (nextRow < height) {
                    input.getPixels(below, 0, width, 0, nextRow, width, 1)
                }
            }
        }
    }

    /** 3×3 tent [1 2 1]⊗[1 2 1]/16 over the luminance, the fine term of the texture band. */
    private fun tentLuminance(
        above: IntArray,
        current: IntArray,
        below: IntArray,
        x: Int,
        width: Int,
    ): Float {
        val left = max(x - 1, 0)
        val right = min(x + 1, width - 1)
        return (
            pixelLuminance(above[left]) + 2f * pixelLuminance(above[x]) + pixelLuminance(above[right]) +
                2f * pixelLuminance(current[left]) + 4f * pixelLuminance(current[x]) +
                2f * pixelLuminance(current[right]) +
                pixelLuminance(below[left]) + 2f * pixelLuminance(below[x]) + pixelLuminance(below[right])
            ) / 16f
    }

    private fun pixelLuminance(argb: Int): Float = PhotoEffects.luminance(
        (argb ushr 16 and 0xFF) / 255f,
        (argb ushr 8 and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )

    /**
     * Steps 1 to 7 — white balance, exposure, whites and blacks — on an sRGB 0..1 triple, in place.
     *
     * It lives outside [processPixel] because the local adaptation mask needs exactly these steps and
     * no others: it is the image as it enters step 8 that decides what is a highlight and what is a
     * shadow. Two copies of this arithmetic would be two definitions of "light".
     */
    internal fun applyBaseTone(rgb: FloatArray, tone: EffectiveTone) {
        var red = srgbToLinear(rgb[0])
        var green = srgbToLinear(rgb[1])
        var blue = srgbToLinear(rgb[2])

        val temperature = tone.temperature / 100f
        val tint = tone.tint / 100f
        red *= (1f + 0.20f * temperature) * (1f + 0.08f * tint)
        green *= (1f + 0.05f * tint) * (1f - 0.10f * tint)
        blue *= (1f - 0.20f * temperature) * (1f + 0.08f * tint)
        val exposure = 2f.pow(tone.exposure)
        red *= exposure
        green *= exposure
        blue *= exposure

        val linearLuminance = luminance(red, green, blue)
        val lift = tone.blacks / 100f * (1f - smoothstep(0f, 0.35f, linearLuminance)) * 0.18f +
            tone.whites / 100f * smoothstep(0.55f, 1f, linearLuminance) * 0.22f
        red += lift
        green += lift
        blue += lift

        rgb[0] = linearToSrgb(red)
        rgb[1] = linearToSrgb(green)
        rgb[2] = linearToSrgb(blue)
    }

    /**
     * @param localBase blurred luminance of the neighbourhood at the input of step 8, or
     *   [NO_LOCAL_BASE] when there is no mask — and then highlights and shadows fall back to the
     *   global behaviour.
     */
    internal fun processPixel(
        argb: Int,
        parameters: RenderParameters,
        tone: EffectiveTone,
        localBase: Float = NO_LOCAL_BASE,
    ): Int {
        val alpha = argb ushr 24 and 0xFF
        val base = floatArrayOf(
            (argb ushr 16 and 0xFF) / 255f,
            (argb ushr 8 and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )
        applyBaseTone(base, tone)
        var red = base[0]
        var green = base[1]
        var blue = base[2]

        fun remapLuminance(target: Float) {
            val current = luminance(red, green, blue)
            if (current <= 0.00001f) return
            val scale = target / current
            red *= scale
            green *= scale
            blue *= scale
        }

        var tonalLuminance = luminance(red, green, blue).coerceIn(0f, 1f)
        remapLuminance(locallyAdaptedShadows(tonalLuminance, localBase, tone.shadows / 100f))
        tonalLuminance = luminance(red, green, blue).coerceIn(0f, 1f)
        remapLuminance(locallyAdaptedHighlights(tonalLuminance, localBase, tone.highlights / 100f))
        tonalLuminance = luminance(red, green, blue).coerceIn(0f, 1f)
        remapLuminance(midtoneContrastLuminance(tonalLuminance, tone.contrast / 100f))

        red = curveSample(parameters.toneCurveRgb, red)
        green = curveSample(parameters.toneCurveRgb, green)
        blue = curveSample(parameters.toneCurveRgb, blue)
        val hslColor = applyHslBands(floatArrayOf(red, green, blue), parameters.hslBands)
        red = hslColor[0]
        green = hslColor[1]
        blue = hslColor[2]

        val perceived = luminance(red, green, blue)
        val saturation = 1f + tone.saturation / 100f
        red = mix(perceived, red, saturation)
        green = mix(perceived, green, saturation)
        blue = mix(perceived, blue, saturation)
        val chroma = max(red, max(green, blue)) - min(red, min(green, blue))
        val vibrance = 1f + tone.vibrance / 100f * (1f - chroma.coerceIn(0f, 1f))
        red = mix(perceived, red, vibrance)
        green = mix(perceived, green, vibrance)
        blue = mix(perceived, blue, vibrance)

        val outputRed = (red.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val outputGreen = (green.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val outputBlue = (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return alpha shl 24 or (outputRed shl 16) or (outputGreen shl 8) or outputBlue
    }

    private fun applyHslBands(rgb: FloatArray, bands: List<HslRenderBand>): FloatArray {
        val hsv = rgbToHsv(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f))
        var hue = 0f
        var saturation = 0f
        var luminance = 0f
        var totalWeight = 0f
        HSL_CENTERS.forEachIndexed { index, center ->
            val weight = max(1f - hueDistance(hsv[0], center) / 0.13f, 0f)
            hue += bands[index].hue / 100f * weight
            saturation += bands[index].saturation / 100f * weight
            luminance += bands[index].luminance / 100f * weight
            totalWeight += weight
        }
        if (totalWeight > 0f) {
            hue /= totalWeight
            saturation /= totalWeight
            luminance /= totalWeight
        }
        hsv[0] = positiveFraction(hsv[0] + hue / 12f)
        hsv[1] = (hsv[1] * (1f + saturation)).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + luminance * 0.5f).coerceIn(0f, 1f)
        return hsvToRgb(hsv[0], hsv[1], hsv[2])
    }

    private fun rgbToHsv(red: Float, green: Float, blue: Float): FloatArray {
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum
        val hue = when {
            delta == 0f -> 0f
            maximum == red -> positiveFraction((green - blue) / delta / 6f)
            maximum == green -> ((blue - red) / delta + 2f) / 6f
            else -> ((red - green) / delta + 4f) / 6f
        }
        return floatArrayOf(hue, if (maximum == 0f) 0f else delta / maximum, maximum)
    }

    private fun hsvToRgb(hue: Float, saturation: Float, value: Float): FloatArray {
        val position = positiveFraction(hue) * 6f
        val sector = floor(position).toInt().mod(6)
        val fraction = position - floor(position)
        val p = value * (1f - saturation)
        val q = value * (1f - fraction * saturation)
        val t = value * (1f - (1f - fraction) * saturation)
        return when (sector) {
            0 -> floatArrayOf(value, t, p)
            1 -> floatArrayOf(q, value, p)
            2 -> floatArrayOf(p, value, t)
            3 -> floatArrayOf(p, q, value)
            4 -> floatArrayOf(t, p, value)
            else -> floatArrayOf(value, p, q)
        }
    }

    private fun curveSample(curve: List<Float>, value: Float): Float {
        val position = value.coerceIn(0f, 1f) * 255f
        val left = floor(position).toInt()
        val right = (left + 1).coerceAtMost(255)
        return mix(curve[left], curve[right], position - left)
    }

    private fun srgbToLinear(value: Float): Float =
        if (value < 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(value: Float): Float {
        val positive = max(value, 0f)
        return if (positive < 0.0031308f) positive * 12.92f else 1.055f * positive.pow(1f / 2.4f) - 0.055f
    }

    private fun luminance(red: Float, green: Float, blue: Float) =
        red * 0.2126f + green * 0.7152f + blue * 0.0722f

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val distance = abs(a - b)
        return min(distance, 1f - distance)
    }

    private fun positiveFraction(value: Float): Float = value - floor(value)
    private fun mix(from: Float, to: Float, amount: Float) = from * (1f - amount) + to * amount

    private val HSL_CENTERS = floatArrayOf(0f, 1f / 12f, 1f / 6f, 1f / 3f, 0.5f, 2f / 3f, 0.75f, 5f / 6f)
}

internal fun midtoneContrastLuminance(luminance: Float, amount: Float): Float {
    val value = luminance.coerceIn(0f, 1f)
    val strength = amount.coerceIn(-1f, 1f) * 0.85f
    return (value + strength * value * (1f - value) * (2f * value - 1f)).coerceIn(0f, 1f)
}

/**
 * Grain amplitude at maximum intensity, with the granularity at the middle of the scale.
 *
 * It is calibrated so that the particle field of [PhotoEffects.grainField], which is normalised to a
 * known standard deviation, reaches the photo with the strength the previous grid noise had in the
 * factory presets. Size multiplies it by [PhotoEffects.grainSizeGain], which is where granularity
 * gains the contrast it lacks.
 */
internal const val GRAIN_AMPLITUDE = 0.34f

/**
 * Where the highlights band starts, in screen luminance. Below this the curve is the identity.
 *
 * ## Why it is not mid-scale
 *
 * It used to be 0.5, on the argument that the two tools tiled the scale without overlapping. The
 * argument was tidy and the result was a slider with no reach: the curve's shift is worth
 * `t(1 − t) · local`, and as `local` rises with the same band, the mass of the effect concentrates at
 * its top. With the pivot at the middle, a third of the useful scale was left — below 0.5 nothing,
 * from 0.5 to 0.6 thousandths — and the tool seemed to touch only the most blown highlights.
 *
 * At 0.35 the band covers two thirds of the scale and the peak of the shift falls from 0.83 to 0.73,
 * which is where the highlight a photo complains about lives. The two bands now overlap in the
 * midtones — shadows reach 0.65, highlights start at 0.35 — and that is how commercial raw
 * developers draw them: a light midtone is legitimately the work of both, and whoever edits expects
 * the slider to reach it. What still cannot happen is an end moving, and the anchors take care of
 * that, not the pivot.
 */
internal const val HIGHLIGHT_PIVOT = 0.35f

/**
 * How much of the adaptation is global, that is, how much the pixel itself gets to say about
 * whether it is a highlight, against the neighbourhood's word.
 *
 * At zero — as it was — the decision was the neighbourhood's alone, and that made the tool
 * all-or-nothing: a small reflection on a dark background stayed **exactly** where it was, however
 * far the slider went. It is the useless half of a good idea; the half that is worth it is the other
 * one, that a dark branch against a sky is not darkened with it, and that does not come from here,
 * it comes from the flow's `t` factor.
 *
 * One third gives a gradation instead of a switch: in the sky the neighbourhood still decides almost
 * everything, and the isolated reflection now catches part of the effect instead of none.
 *
 * It goes in as a mix and not as a floor precisely so as not to break the entry into the band: a
 * fixed floor left the derivative at the pivot at `e^(gain · floor)`, a step in slope at the same
 * luminance across the whole image — a Mach band in a smooth sky. Mixed with `t`, which vanishes at
 * the pivot, the entry stays as smooth as it was.
 */
private const val LOCAL_ADAPTATION_GLOBAL_SHARE = 0.35f

/** Value of `localBase` that says there is no mask; the curves then fall back to the global behaviour. */
internal const val NO_LOCAL_BASE = -1f

/**
 * Gain of the highlights curve, asymmetric on purpose. Recovering takes more than brightening
 * because it is the side that is used in earnest — and for the shadows, which are the mirror, it is
 * the side that lifts.
 *
 * These values no longer have a ceiling. While the band was mapped by the linearised form
 * `t + gain · local · t(1 − t)`, a magnitude above one made the derivative negative in the corner
 * where the neighbourhood is white and the pixel is at the pivot, and the curve swapped the order of
 * the tones; that limit, and not taste, held the slider at ±0.85 and left it with no strength at
 * +100. The logistic flow of [locallyAdaptedHighlights] is monotonic for any gain, and so the choice
 * became purely about intensity: 1.8 moves a sky from 0.90 to 0.70 at −100, far more than the old
 * form could, and in the same order of magnitude commercial raw developers reach.
 */
private const val HIGHLIGHT_RECOVERY_GAIN = 1.8f

/** The other side of the slider: brightening highlights, or deepening shadows. See [HIGHLIGHT_RECOVERY_GAIN]. */
private const val HIGHLIGHT_LIFT_GAIN = 1.3f

private fun highlightGain(amount: Float): Float {
    val clamped = amount.coerceIn(-1f, 1f)
    return clamped * if (clamped < 0f) HIGHLIGHT_RECOVERY_GAIN else HIGHLIGHT_LIFT_GAIN
}

/**
 * Highlights with local adaptation: the curve anchored at both ends of the band above
 * [HIGHLIGHT_PIVOT], with the decision on *how much this is a highlight* handed mostly to the
 * neighbourhood.
 *
 * ## Why the curve changed
 *
 * The previous version multiplied the luminance by `exp2(amount * 1.25 * weight)` with a weight that
 * reached one at 0.70 and stayed there until 0.97. That did three bad things at once: at +100 the
 * gain was 2.38× and every value above 0.60 saturated to white — the clip that eats detail —; at
 * −100 the same whole band was multiplied by 0.42, which dragged it down as a block; and the curve
 * stopped being monotonic from 0.479, that is, it swapped the order of the tones.
 *
 * ## The new form
 *
 * The pixel's own normalised band `t` is carried by the logistic flow `dt/ds = local · t(1 − t)`,
 * integrated up to `s = gain`, where `local` is the position of the **neighbourhood** in that same
 * band, mixed with the pixel's own in the proportion [LOCAL_ADAPTATION_GLOBAL_SHARE]. In closed form:
 *
 * ```
 * t' = t·e^a / (1 − t + t·e^a),   a = gain · local
 * ```
 *
 * The three factors are not interchangeable:
 *
 *  - `t(1 − t)` is the flow's field, and always comes from the pixel. It vanishes at both ends, and
 *    so `t = 0` gives the pivot and `t = 1` gives white, for any slider value and any neighbourhood.
 *    **Nothing leaves the band**, so there is no clip to do and no way for the tool to touch a pixel
 *    that is not a highlight.
 *  - `local` is the factor that brings in the surroundings, and it is the local adaptation. It enters
 *    as the flow's *time*: a dark neighbourhood almost stops it, and the pixel stays almost intact.
 *    *Almost*, and not exactly, because a third of the time comes from the pixel itself — see
 *    [LOCAL_ADAPTATION_GLOBAL_SHARE].
 *
 * From this follow, by algebra and not by hand-tuned constants:
 *
 *  - **The entry has no step.** Both factors of the time vanish at the pivot — `t` by construction
 *    and `band` because there the neighbourhood is at the pivot too — and so the shift grows from
 *    zero: at 0.40 it moves five thousandths, and the maximum is near 0.73. This is why the effect
 *    stops looking like a general lift in brightness.
 *  - **Recovering highlights separates what was stuck together.** The derivative at `t = 1` is
 *    `e^(−gain · local)`, which at −100 in a sky is worth about six: the 0.90–1.00 range, where the
 *    almost blown detail lives, comes out stretched instead of crushed.
 *  - **There is no halo.** A dark branch against a blown sky has `t = 0` and stays exactly where it
 *    was, however light the neighbourhood is — and keeps staying after the adaptation gained a
 *    global part, because that part also comes from `t`. It is the difference between this form and
 *    a local tone map written as `curve(neighbourhood) + detail · slope`, which extrapolates the curve
 *    far from the neighbourhood and darkens the branch down to black.
 *  - **Without a mask there is no special case.** With `local == t` the global curve comes out — the
 *    same as saying a pixel's neighbourhood is itself.
 *
 * ## Why the flow and not its linearisation
 *
 * The first version of this curve used `t + gain · local · t(1 − t)`, which is exactly the first term
 * of the flow's series above. The approximation holds while the gain is small; from a magnitude of
 * one the derivative goes negative and the curve inverts the order of the tones. That, and nothing
 * else, forced gains below one — and a gain below one gives at most an eighth of a band of shift,
 * which is the slider at +100 barely being noticed.
 *
 * The integrated flow is a Möbius transformation with determinant `e^a > 0`: strictly increasing in
 * `t` for **any** gain, and still fixing `t = 0` and `t = 1`. Every property above still holds word
 * for word, and the slider's ceiling is gone.
 *
 * @param base blurred luminance of the neighbourhood at the input of step 8, or [NO_LOCAL_BASE].
 */
internal fun locallyAdaptedHighlights(value: Float, base: Float, amount: Float): Float {
    val pixel = value.coerceIn(0f, 1f)
    if (pixel <= HIGHLIGHT_PIVOT) return pixel
    val reference = if (base < 0f) pixel else base.coerceIn(0f, 1f)
    val t = (pixel - HIGHLIGHT_PIVOT) / (1f - HIGHLIGHT_PIVOT)
    val band = ((reference - HIGHLIGHT_PIVOT) / (1f - HIGHLIGHT_PIVOT)).coerceIn(0f, 1f)
    val local = band + LOCAL_ADAPTATION_GLOBAL_SHARE * (t - band)
    val flow = exp(highlightGain(amount) * local)
    return HIGHLIGHT_PIVOT + (1f - HIGHLIGHT_PIVOT) * (t * flow / (1f - t + t * flow))
}

/**
 * Shadows with local adaptation: the exact mirror of the highlights, pixel and neighbourhood mirrored
 * together, with the slider's sign swapped — the shadows band therefore runs from black to
 * `1 −` [HIGHLIGHT_PIVOT].
 *
 * Writing it this way and not as a second curve is not saving lines: it is the guarantee that the
 * two tools cannot diverge. Everything proven for the highlights is proven for the shadows — black
 * stays black just as white stays white, a light point inside a shadow is not lifted with it, and
 * lifting shadows stretches the detail near black with the same slope with which recovering
 * highlights stretches it near white.
 *
 * The sign swaps because the convention of the two sliders is the same for whoever edits — positive
 * brightens — and opposite with respect to the pivot: brightening shadows is, in the mirror,
 * holding back highlights.
 */
internal fun locallyAdaptedShadows(value: Float, base: Float, amount: Float): Float =
    1f - locallyAdaptedHighlights(
        value = 1f - value.coerceIn(0f, 1f),
        base = if (base < 0f) NO_LOCAL_BASE else 1f - base.coerceIn(0f, 1f),
        amount = -amount,
    )

/** Highlights without a mask, which is the same as saying a pixel's neighbourhood is itself. */
internal fun selectiveHighlightLuminance(luminance: Float, amount: Float): Float =
    locallyAdaptedHighlights(luminance, NO_LOCAL_BASE, amount)

/** Shadows without a mask. */
internal fun selectiveShadowLuminance(luminance: Float, amount: Float): Float =
    locallyAdaptedShadows(luminance, NO_LOCAL_BASE, amount)
