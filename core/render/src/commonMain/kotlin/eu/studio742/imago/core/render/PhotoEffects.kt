package eu.studio742.imago.core.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import eu.studio742.imago.core.model.MaskOp

/**
 * The mathematical core of steps 9 (clarity, texture, dehaze) and 13 (vignette, grain) of the
 * normative pipeline. Every function here has a literal twin in the GLSL of [PhotoShaders]; this file
 * defines the behaviour and the shader only transcribes it, so that the preview and the
 * full-resolution export do not diverge.
 *
 * Every function works in perceptual space (sRGB with the transfer applied), which is where the
 * pipeline places these steps.
 */
object PhotoEffects {
    /**
     * Standard deviations of the blurs, in pixels of the full-resolution image. The fine texture blur
     * does not appear here because it is the 3×3 *tent* (σ = 0.707) computed on the spot for each
     * pixel; the others are built in a pyramid and resampled.
     */
    const val TEXTURE_BAND_SIGMA = 2.6f
    const val CLARITY_SIGMA = 24f
    const val DEHAZE_REFINE_SIGMA = 16f

    /**
     * Blur of the local adaptation's base layer.
     *
     * It is larger than clarity's on purpose: this is not a contrast mask, it is the separation
     * between what is a region of the photo and what is detail within it. A short radius would make
     * the mask follow the detail itself and the adaptation would stop having any effect.
     */
    const val LOCAL_TONE_SIGMA = 48f

    private const val DEHAZE_OMEGA = 0.95f
    private const val DEHAZE_MIN_TRANSMISSION = 0.1f

    /**
     * Soft amplitude limiter. It keeps the signal linear near zero and saturates at [limit], which is
     * what prevents the white halos typical of a naive contrast mask.
     */
    fun softLimit(value: Float, limit: Float): Float = limit * tanh(value / limit)

    /**
     * Clarity's midtone weight. Adobe deliberately biases local contrast towards the greys: a parabola
     * worth 1 at middle grey and 0 at black and white preserves the extremes and avoids closing the
     * shadows.
     */
    fun midtoneWeight(luminance: Float): Float {
        val value = luminance.coerceIn(0f, 1f)
        return 4f * value * (1f - value)
    }

    /**
     * Clarity: wide-radius local contrast, biased towards the midtones.
     *
     * @param luminance the pixel's perceptual luminance
     * @param coarse the same luminance blurred with [CLARITY_SIGMA]
     * @param amount -1..1
     */
    fun clarityDelta(luminance: Float, coarse: Float, amount: Float): Float {
        if (amount == 0f) return 0f
        val detail = softLimit(luminance - coarse, 0.32f)
        return amount.coerceIn(-1f, 1f) * 1.15f * detail * midtoneWeight(luminance)
    }

    /**
     * Texture: mid-frequency contrast.
     *
     * The signal is a band — the difference between a very short blur and a short blur — and not a
     * high-pass mask. That band is what separates texture from noise: the fine blur has already
     * erased pixel-level detail, so noise never gets into the calculation. It is also what sets this
     * tool apart from sharpening.
     *
     * @param fine luminance smoothed by the 3×3 *tent*
     * @param band luminance blurred with [TEXTURE_BAND_SIGMA]
     * @param coarse luminance blurred with [CLARITY_SIGMA], used only to protect edges
     */
    fun textureDelta(fine: Float, band: Float, coarse: Float, amount: Float): Float {
        if (amount == 0f) return 0f
        val detail = softLimit(fine - band, 0.18f)
        return amount.coerceIn(-1f, 1f) * 1.6f * detail * edgeMask(band, coarse)
    }

    /**
     * Step 9's target luminance. The two contributions are added from the same starting luminance —
     * chaining them would make texture feed clarity and the result would stop being independent of
     * the order of the sliders.
     */
    fun detailLuminance(
        luminance: Float,
        fine: Float,
        band: Float,
        coarse: Float,
        texture: Float,
        clarity: Float,
    ): Float {
        val delta = textureDelta(fine, band, coarse, texture) + clarityDelta(luminance, coarse, clarity)
        return (luminance + delta).coerceIn(0f, 1f)
    }

    /**
     * Attenuates texture over the edges of large objects. A sharp difference between the short and
     * the wide blur means we are on a contrast boundary, and that is exactly where reinforcing detail
     * produces a halo.
     */
    fun edgeMask(band: Float, coarse: Float): Float =
        1f - 0.85f * smoothstep(0.05f, 0.24f, abs(band - coarse))

    /**
     * Transmission of the haze model, from the dark channel (*dark channel prior*).
     *
     * `t = 1 - ω · dark/A` is the estimate by He, Sun & Tang. The original paper's *soft matting*
     * refinement is replaced by a Gaussian blur of the dark channel, which is the usual real-time
     * compromise: adherence to fine edges is lost, behaviour in depth is kept.
     */
    fun transmission(darkChannel: Float, airlight: Float): Float {
        val safeAirlight = max(airlight, 0.05f)
        return (1f - DEHAZE_OMEGA * (darkChannel / safeAirlight)).coerceIn(0f, 1f)
    }

    /**
     * Scene recovery: `J = (I - A)/max(t, t0) + A` for positive dehaze.
     *
     * For negative values the direct model applies — the colour is mixed with the atmospheric light
     * in proportion to the opacity `1 - t`, that is, more haze is added where the scene is already
     * further away.
     */
    fun dehazeChannel(value: Float, airlight: Float, transmission: Float, amount: Float): Float {
        if (amount == 0f) return value
        return if (amount > 0f) {
            val strength = amount.coerceAtMost(1f)
            val effective = 1f - (1f - max(transmission, DEHAZE_MIN_TRANSMISSION)) * strength
            ((value - airlight) / effective + airlight).coerceIn(0f, 1f)
        } else {
            val haze = (-amount).coerceAtMost(1f) * (1f - transmission)
            (value * (1f - haze) + airlight * haze).coerceIn(0f, 1f)
        }
    }

    /**
     * Half the width of each tonal weight of step 12, in units of luminance.
     *
     * This is what blending controls. At 0 each weight covers 0.40 and the three colours stay almost
     * contained in their zone; at 100 it covers 1.0 and any pixel receives all three. It never goes
     * below 0.40 on purpose: the three centres are 0, the pivot and 1, and with a smaller width there
     * would be luminances with no weight at all — bands of the photo the grading did not touch, with
     * a visible step at the boundary.
     */
    fun colorGradeWidth(blending: Float): Float = 0.40f + 0.60f * (blending / 100f).coerceIn(0f, 1f)

    /**
     * The centre of the midtone weight, which is what balance shifts.
     *
     * Positive brings the pivot closer to the shadows, and that is what gives the highlights more
     * range: the light weight comes to dominate a larger slice of the scale. The shift is limited to
     * 0.15 so the pivot never gets close enough to one of the extremes to leave a gap with no weight.
     */
    fun colorGradeMidtone(balance: Float): Float = 0.5f - 0.15f * (balance / 100f).coerceIn(-1f, 1f)

    /**
     * Amplitude of the chroma shift with saturation at maximum.
     *
     * At 0.30 a wheel at 100 shifts the dominant channel by about 0.24 — a strong tint, the kind a
     * cinematic grade asks for, without reaching the point where the photo stops having its own
     * colours.
     */
    const val COLOR_GRADE_CHROMA = 0.30f

    /** Half, as in HSL: the whole slider is worth half a scale of luminance. */
    const val COLOR_GRADE_LUMINANCE = 0.5f

    /**
     * The tint of a hue: its RGB at maximum saturation and value.
     *
     * It runs once per render, in [colorGradeRangeOf], and never per pixel.
     */
    fun hueTint(hue: Float): FloatArray {
        val position = (hue / 60f) - floor(hue / 360f) * 6f
        val sector = floor(position).toInt().coerceIn(0, 5)
        val fraction = position - floor(position)
        return when (sector) {
            0 -> floatArrayOf(1f, fraction, 0f)
            1 -> floatArrayOf(1f - fraction, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, fraction)
            3 -> floatArrayOf(0f, 1f - fraction, 1f)
            4 -> floatArrayOf(fraction, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 1f - fraction)
        }
    }

    /**
     * Step 12 — colour grading, on a perceptual 0..1 triple, in place.
     *
     * The three weights are triangles centred on black, the pivot and white, normalised to add up to
     * one: each pixel receives exactly one dose of tonal colour, shares it or not between the three
     * wheels, and the global wheel adds on top with no weight at all — it has no tonal zone to obey.
     *
     * The tint is **additive and luminance-free**: [ColorGradeRange] stores `tint − grey`, a vector
     * whose dot product with the luminance weights is zero by construction. That is what allows tinting
     * a closed shadow, which multiplying by the colour never could — zero times any colour is still
     * zero — and it is also what keeps the luminance in place without a second normalisation. At the
     * extremes, clipping to 0..1 takes a little from that promise: tinting an almost pure white
     * necessarily darkens it, because the colour has to come from some channel.
     *
     * Each wheel's luminance comes after the tint, and by multiplication as in the tonal steps: it
     * preserves the hue and saturation the tint has just given. The price, the same as steps 4 and 5,
     * is that an absolutely black pixel stays black.
     */
    fun applyColorGrading(rgb: FloatArray, grade: ColorGrade) {
        val value = luminance(rgb[0], rgb[1], rgb[2]).coerceIn(0f, 1f)
        val width = colorGradeWidth(grade.blending)
        val midtone = colorGradeMidtone(grade.balance)
        var shadow = max(1f - abs(value) / width, 0f)
        var middle = max(1f - abs(value - midtone) / width, 0f)
        var highlight = max(1f - abs(value - 1f) / width, 0f)
        val total = shadow + middle + highlight
        if (total > 0f) {
            shadow /= total
            middle /= total
            highlight /= total
        }
        val red = (
            rgb[0] + COLOR_GRADE_CHROMA * (
                shadow * grade.shadows.chromaRed + middle * grade.midtones.chromaRed +
                    highlight * grade.highlights.chromaRed + grade.global.chromaRed
                )
            ).coerceIn(0f, 1f)
        val green = (
            rgb[1] + COLOR_GRADE_CHROMA * (
                shadow * grade.shadows.chromaGreen + middle * grade.midtones.chromaGreen +
                    highlight * grade.highlights.chromaGreen + grade.global.chromaGreen
                )
            ).coerceIn(0f, 1f)
        val blue = (
            rgb[2] + COLOR_GRADE_CHROMA * (
                shadow * grade.shadows.chromaBlue + middle * grade.midtones.chromaBlue +
                    highlight * grade.highlights.chromaBlue + grade.global.chromaBlue
                )
            ).coerceIn(0f, 1f)
        val offset = (
            shadow * grade.shadows.luminance + middle * grade.midtones.luminance +
                highlight * grade.highlights.luminance + grade.global.luminance
            ) / 100f * COLOR_GRADE_LUMINANCE
        if (offset == 0f) {
            rgb[0] = red
            rgb[1] = green
            rgb[2] = blue
            return
        }
        val current = luminance(red, green, blue)
        if (current <= 0.00001f) {
            rgb[0] = red
            rgb[1] = green
            rgb[2] = blue
            return
        }
        val scale = (value + offset).coerceIn(0f, 1f) / current
        rgb[0] = (red * scale).coerceIn(0f, 1f)
        rgb[1] = (green * scale).coerceIn(0f, 1f)
        rgb[2] = (blue * scale).coerceIn(0f, 1f)
    }

    /**
     * Vignette factor: 0 at the centre, 1 at the point where the effect is complete.
     *
     * @param x,y normalised 0..1 coordinates of the already oriented frame
     * @param aspect width/height of the oriented frame
     * @param midpoint 0..100 — how far inwards the effect advances
     * @param roundness -100..100 — negative approaches the rectangle, positive the circle
     * @param feather 0..100 — width of the transition
     */
    fun vignetteFalloff(
        x: Float,
        y: Float,
        aspect: Float,
        midpoint: Float,
        roundness: Float,
        feather: Float,
    ): Float {
        val round = (roundness / 100f).coerceIn(-1f, 1f)
        // At zero, the shape is an ellipse inscribed in the frame; positive approaches the circle by
        // correcting the short axis by the aspect ratio.
        val safeAspect = if (aspect > 0f) aspect else 1f
        val scaleX = 1f + max(round, 0f) * (max(safeAspect, 1f) / safeAspect - 1f)
        val scaleY = 1f + max(round, 0f) * (max(1f / safeAspect, 1f) * safeAspect - 1f)
        val dx = abs((x - 0.5f) * 2f) * scaleX
        val dy = abs((y - 0.5f) * 2f) * scaleY
        // Minkowski norm: exponent 2 gives the ellipse, high exponents approach the rectangle.
        val exponent = 2f + max(-round, 0f) * 6f
        val distance = (dx.pow(exponent) + dy.pow(exponent)).pow(1f / exponent)

        val outer = 0.35f + (midpoint / 100f).coerceIn(0f, 1f) * 1.10f
        val width = 0.03f + (feather / 100f).coerceIn(0f, 1f) * 0.85f
        val inner = max(outer - width, 0f)
        return smoothstep(inner, outer, distance)
    }

    /** Applies the vignette to a channel. Negative darkens towards black, positive opens towards white. */
    fun vignetteChannel(value: Float, amount: Float, falloff: Float): Float {
        if (amount == 0f) return value
        val strength = (amount / 100f).coerceIn(-1f, 1f) * falloff
        val result = if (strength < 0f) value * (1f + strength) else value + (1f - value) * strength
        return result.coerceIn(0f, 1f)
    }

    /**
     * Monochrome grain.
     *
     * The weight follows film physics: there is practically no grain in crushed black, the maximum is
     * in the midtones, and the highlights regain some uniformity.
     */
    fun grainWeight(luminance: Float): Float {
        val value = luminance.coerceIn(0f, 1f)
        return smoothstep(0f, 0.14f, value) * (1f - 0.55f * smoothstep(0.72f, 1f, value))
    }

    /**
     * Side of the grain cell, in pixels of the **image**. Each cell hosts one particle, and the
     * particle is on average 1.2 cells in diameter — from 1.4 px at the slider's minimum to 8.6 px at
     * the maximum, which is the distance between an ISO 100 film and a pushed 3200.
     *
     * The smallest particle does not go below one and a half pixels in diameter, on purpose: a
     * particle finer than the pixel showing it reaches the screen spread out and faint — that is how
     * it has to be — and the bottom of the scale was left with no grain to show at all.
     *
     * ## Why it is in image pixels and not render-target pixels
     *
     * It used to be in target pixels — the cell measured the same in the viewport and in the exported
     * file — on the argument that the grain seen while framing is the grain that comes out. The
     * argument was backwards: what was seen while framing was *never* what came out, because the same
     * five-pixel cell is coarse grain in a thousand-pixel preview and invisible in a six-thousand-pixel
     * file. The slider described nothing stable.
     *
     * Grain belongs to the negative, not to the enlargement. Tied to the image, the export shows the
     * granularity the slider promises, and the preview shows it at the zoom's scale: fine with the
     * photo fitted, true at 100%. That is why grain is judged zoomed in — in Lightroom for the same
     * reason, and with the same consequence.
     */
    fun grainCellSize(size: Float): Float = 1.2f + (size / 100f).coerceIn(0f, 1f) * 5.94f

    /**
     * How much granularity weighs on the amplitude, relative to size 25 at the middle of the scale.
     *
     * Larger particles are fewer in the same area, and the density fluctuation that results grows
     * with the square root of the particle's area — it is Selwyn's law, and it is why a 3200 film is
     * not only grainier than a 100, it also has more contrasty grain. Without this, the size slider
     * changed the granularity and left the photo with the same noise, which is the symptom of grain
     * drawn as an overlaid texture and not as an emulsion.
     */
    fun grainSizeGain(size: Float): Float = sqrt(grainCellSize(size) / grainCellSize(25f))

    /** Mean particle radius, in cells. */
    private const val GRAIN_RADIUS = 0.60f

    /**
     * Ceiling of the radius, in cells.
     *
     * It is not taste: the sum only visits the 3×3 neighbourhood, and a particle two cells away can
     * have its centre one cell from the sampled point. A larger radius than that — plus the edge —
     * would leave out particles that still touched the pixel, and the seam would show at the cell
     * boundaries, aligned with the axes, which is exactly the artefact this grain exists not to have.
     */
    private const val GRAIN_RADIUS_MAX = 0.80f

    /** Spread of the radii: half the relative width of the distribution, without and with roughness. */
    private const val GRAIN_SPREAD = 0.30f
    private const val GRAIN_SPREAD_ROUGH = 0.25f

    /** Offset of the centre within the cell, in cells, without and with roughness. */
    private const val GRAIN_JITTER = 0.80f
    private const val GRAIN_JITTER_ROUGH = 0.20f

    /** The slow envelope that groups the grain into clumps — Adobe's roughness. */
    private const val GRAIN_CLUMP_SCALE = 0.22f
    private const val GRAIN_CLUMP_DEPTH = 1.6f

    /** Intrinsic softness of the particle's edge, as a fraction of the radius. */
    private const val GRAIN_EDGE_FRACTION = 0.20f

    /**
     * How much of a cell one target pixel measures, for the edge, and that edge's ceiling.
     *
     * The edge is never finer than the pixel that will show it: a particle smaller than the pixel
     * has to reach the screen spread out and faint, not cut straight, which is what would make the
     * fine grain of a fitted preview flicker. The ceiling is where the 3×3 neighbourhood stops
     * reaching; beyond it the amplitude keeps falling in [grainField], but the blur no longer grows.
     */
    private const val GRAIN_FOOTPRINT = 0.75f
    private const val GRAIN_EDGE_MAX = 1f

    /** Standard deviation the field is normalised to, that of uniform noise in −0.5..0.5. */
    private const val GRAIN_DEVIATION = 0.289f

    /**
     * How much of the variance predicted by the closed form is actually realised, measured on the field.
     *
     * The prediction treats particles as discs that do not overlap; they do, and the overlap of
     * opposite signs eats nine per cent of the variance. It is here as a measured constant, and not
     * hidden inside [GRAIN_DEVIATION], so that it is clear the normalisation is analytical and only
     * the correction is empirical.
     */
    private const val GRAIN_PACKING = 0.90f

    /**
     * Grain field: discrete particles, one per cell, with drawn radius, position and sign.
     *
     * ## Why it is not grid noise
     *
     * It used to be: one octave of *value noise*, a grid of random values interpolated with
     * smoothstep. That is, literally, a low-resolution noise image enlarged — and enlarging was all
     * the size slider did. The clumps came out with blurred edges and aligned with the grid's axes, a
     * high size looked like blur instead of grain, and the amplitude did not move, when on film it is
     * what most gives away the granularity.
     *
     * A particle is not a point of a stretched grid: it has its own radius, falls where it falls, and
     * its edge is the size of the pixel that shows it and not its own size. That is why particles are
     * summed here and not octaves — size comes to choose the granularity, and not the enlargement of a
     * fixed pattern.
     *
     * ## The sum
     *
     * Each cell holds one signed particle, and the field's value at a point is the sum of the
     * particles that touch it. The random sign spares subtracting the mean coverage — the field's
     * mean is zero by construction, and grain with a non-zero mean was a disguised exposure change.
     *
     * The kernel preserves mass and not peak: a particle smaller than the pixel arrives spread over
     * [GRAIN_FOOTPRINT] and proportionally fainter. That, and not a cut, is what gives the
     * *antialiasing* — with the photo fitted the fine grain fades instead of flickering, and comes
     * back when zooming in. The same calculation serves both sides of the pipeline, because the only
     * parameter that changes between them is [pixelInCells].
     *
     * @param x the position in cells, that is, the image pixel divided by [grainCellSize].
     * @param y the same, on the other axis.
     * @param pixelInCells how much one pixel of the target being drawn measures, in cells.
     */
    fun grainField(x: Float, y: Float, roughness: Float, pixelInCells: Float): Float {
        val rough = (roughness / 100f).coerceIn(0f, 1f)
        val spread = GRAIN_SPREAD + GRAIN_SPREAD_ROUGH * rough
        val jitter = GRAIN_JITTER + GRAIN_JITTER_ROUGH * rough
        val footprint = GRAIN_FOOTPRINT * pixelInCells
        val overshoot = max(footprint / GRAIN_EDGE_MAX, 1f)
        val clump = 1f + rough *
            (valueNoise(x * GRAIN_CLUMP_SCALE, y * GRAIN_CLUMP_SCALE, 1) - 0.5f) * GRAIN_CLUMP_DEPTH
        val cellX = floor(x).toInt()
        val cellY = floor(y).toInt()
        var sum = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val hash = grainHash(cellX + dx, cellY + dy)
                val centreX = cellX + dx + 0.5f + ((hash and 0xFF) / 255f - 0.5f) * jitter
                val centreY = cellY + dy + 0.5f + ((hash ushr 8 and 0xFF) / 255f - 0.5f) * jitter
                val radius = min(
                    GRAIN_RADIUS * (1f + ((hash ushr 16 and 0xFF) / 255f - 0.5f) * 2f * spread),
                    GRAIN_RADIUS_MAX,
                )
                val edge = min(max(GRAIN_EDGE_FRACTION * radius, footprint), GRAIN_EDGE_MAX)
                val peak = min(1f, (radius / edge) * (radius / edge))
                val offsetX = x - centreX
                val offsetY = y - centreY
                val distance = sqrt(offsetX * offsetX + offsetY * offsetY)
                val coverage = 1f - smoothstep(max(radius - edge, 0f), radius + edge, distance)
                val sign = if (hash ushr 24 and 1 == 0) -1f else 1f
                sum += sign * peak * coverage
            }
        }
        return sum * clump * grainNormalization(rough) / (overshoot * overshoot)
    }

    /**
     * What turns the sum of particles into a field of known standard deviation.
     *
     * With independent signs, the sum's variance is the density times the integral of the kernel's
     * square, and from there it comes out in closed form: one particle per cell, mean radius
     * [GRAIN_RADIUS] with the spread adding `spread²/3`, and the clumping envelope adding its own.
     * Without this, changing roughness changed the grain's volume besides its character — and
     * roughness is about character.
     *
     * The edge widened by the target pixel is deliberately **left out** of this calculation: it is the
     * *antialiasing*, and normalising it would give sub-pixel grain back the contrast sampling cannot
     * sustain.
     */
    private fun grainNormalization(rough: Float): Float {
        val spread = GRAIN_SPREAD + GRAIN_SPREAD_ROUGH * rough
        val radiusVariance = 1f + spread * spread / 3f
        val clumpVariance = 1f + 0.0914f * rough * rough
        val variance = PI.toFloat() * GRAIN_PACKING * GRAIN_RADIUS * GRAIN_RADIUS * radiusVariance * clumpVariance
        return GRAIN_DEVIATION / sqrt(variance)
    }

    /**
     * Integer hash per cell, with MurmurHash3's finaliser.
     *
     * It returns all 32 bits because the particle needs four draws — offset in x and in y, radius and
     * sign — and taking them all from the same hash costs one hash per cell instead of four. The
     * finaliser is here because of that: without it the low bits stay barely mixed, and the x offset
     * came out correlated with the radius, which in the image shows as large particles always pushed
     * to the same side of the cell.
     *
     * It is an integer hash, and not the usual trigonometric hash in GLSL, because `sin` would differ
     * between the GPU and the JVM and the preview's grain would stop describing the export's. It is
     * transcribed bit for bit in the shader.
     */
    internal fun grainHash(x: Int, y: Int): Int {
        // 0x9E3779B9 takes cell (0,0) off the finaliser's fixed point, which gave it a zero hash — a
        // minimum-size particle, always in the same corner of the cell, at the corner of the frame.
        var hash = x * 374761393 + y * 668265263 + -0x61c88647
        hash = hash xor (hash ushr 15)
        hash *= -0x7a143595
        hash = hash xor (hash ushr 13)
        hash *= -0x3d4d51cb
        return hash xor (hash ushr 16)
    }

    internal fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val cellX = floor(x)
        val cellY = floor(y)
        val fractionX = x - cellX
        val fractionY = y - cellY
        val weightX = fractionX * fractionX * (3f - 2f * fractionX)
        val weightY = fractionY * fractionY * (3f - 2f * fractionY)
        val baseX = cellX.toInt()
        val baseY = cellY.toInt()
        val topLeft = latticeValue(baseX, baseY, seed)
        val topRight = latticeValue(baseX + 1, baseY, seed)
        val bottomLeft = latticeValue(baseX, baseY + 1, seed)
        val bottomRight = latticeValue(baseX + 1, baseY + 1, seed)
        val top = topLeft + (topRight - topLeft) * weightX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * weightX
        return top + (bottom - top) * weightY
    }

    /** Wang's integer hash, transcribed bit for bit in GLSL to give the same pattern on both sides. */
    internal fun latticeValue(x: Int, y: Int, seed: Int): Float {
        var hash = x * 374761393 + y * 668265263 + seed * 2246822519.toInt()
        hash += hash shl 10
        hash = hash xor (hash ushr 6)
        hash += hash shl 3
        hash = hash xor (hash ushr 11)
        hash += hash shl 15
        return (hash ushr 8 and 0xFFFF) / 65536f
    }

    /** Maximum radius supported by the separable blur, on the CPU and in the shader. */
    const val MAX_BLUR_RADIUS = 24

    /**
     * Normalised half Gaussian kernel: index 0 is the centre, index `i` is the weight of each of the
     * two sides. This is the form the shader consumes, and the CPU uses the same so there are not two
     * definitions of the same blur.
     */
    fun halfGaussianKernel(sigma: Float): FloatArray {
        val safeSigma = max(sigma, 0.05f)
        val radius = (safeSigma * 3f).toInt().coerceIn(1, MAX_BLUR_RADIUS)
        val weights = FloatArray(radius + 1)
        var total = 0f
        for (index in 0..radius) {
            val weight = kotlin.math.exp(-(index * index).toFloat() / (2f * safeSigma * safeSigma))
            weights[index] = weight
            total += if (index == 0) weight else 2f * weight
        }
        for (index in weights.indices) weights[index] /= total
        return weights
    }

    internal fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    internal fun luminance(red: Float, green: Float, blue: Float) =
        red * 0.2126f + green * 0.7152f + blue * 0.0722f

    internal fun darkChannel(red: Float, green: Float, blue: Float) = min(red, min(green, blue))

    // --- local masks ---------------------------------------------------------------------------

    /**
     * The mask weight field lives at one eighth, like [LocalToneMask].
     *
     * A mask is smooth by construction: there is no detail full resolution would keep that one
     * eighth would lose. What is gained is decisive — the CPU builds one sixty-fourth of the values
     * and samples them bilinearly, instead of evaluating the shapes pixel by pixel on a 24 Mpx image.
     */
    const val MASK_FIELD_DIVISOR = 8

    /**
     * Minimum side of the field.
     *
     * `RecipePreviewCache` generates preset thumbnails from a 220 px source; at one eighth that would
     * be 27 texels, and the thumbnail would show a mask that is not the one the photo has. Without
     * this floor the preset lies to the user.
     */
    const val MASK_FIELD_MIN = 32

    /**
     * Minimum width of a linear gradient's transition, in units of image height.
     *
     * It is worth about one field texel on a 2048 px proxy. Below this the transition is narrower than
     * the grid sampling it and turns into *aliasing* — steps where there should be a gradient. A truly
     * hard edge is not what a mask does.
     */
    const val MASK_MIN_WIDTH = 0.004f

    /** The same floor for the radial's transition, here as a fraction of the radius. */
    const val MASK_MIN_FEATHER = 0.02f

    /**
     * A point in a component's frame of reference: centred, rotated and aspect-corrected.
     *
     * The aspect correction is what keeps a circular radial circular. The coordinates arrive
     * normalised to 0..1 on both axes, so a step in x is not worth the same as a step in y;
     * multiplying x by the aspect ratio puts both in units of height.
     *
     * The angle comes in already as cosine and sine, computed once per component. It takes the
     * trigonometry out of the loop and — more importantly — guarantees that the CPU and the GPU start
     * from exactly the same two numbers instead of each computing its own.
     */
    private fun maskLocalX(x: Float, y: Float, centreX: Float, centreY: Float, cosAngle: Float, sinAngle: Float, aspect: Float): Float {
        val dx = (x - centreX) * aspect
        val dy = y - centreY
        return dx * cosAngle + dy * sinAngle
    }

    private fun maskLocalY(x: Float, y: Float, centreX: Float, centreY: Float, cosAngle: Float, sinAngle: Float, aspect: Float): Float {
        val dx = (x - centreX) * aspect
        val dy = y - centreY
        return -dx * sinAngle + dy * cosAngle
    }

    /**
     * Weight of a linear gradient: 0 on one side of the line, 1 on the other, with the transition
     * centred on the point the user dragged.
     *
     * With angle zero the gradient runs from top to bottom — 0 at the top, 1 at the bottom.
     *
     * @param aspect width/height of the original image
     * @param width width of the transition, in units of image height
     */
    fun maskLinearWeight(
        x: Float,
        y: Float,
        centreX: Float,
        centreY: Float,
        cosAngle: Float,
        sinAngle: Float,
        width: Float,
        aspect: Float,
    ): Float {
        val along = maskLocalY(x, y, centreX, centreY, cosAngle, sinAngle, aspect)
        val half = max(width, MASK_MIN_WIDTH) * 0.5f
        return smoothstep(-half, half, along)
    }

    /**
     * Weight of a radial mask: 1 inside, 0 outside, with the transition starting at `1 - feather` of
     * the radius.
     *
     * The shape is the same Minkowski norm as [vignetteFalloff], and not for economy: it is the only
     * family that goes from the ellipse to the rounded rectangle without discontinuity and with a
     * single parameter. Here the exponent grows with [roundness] from 0 to 100, because a radial has
     * both radii defined by the user and does not need the short-axis correction the vignette does.
     *
     * @param feather 0..100 — fraction of the radius taken by the transition
     * @param roundness 0..100 — 0 is the ellipse, 100 approaches the rectangle
     */
    fun maskRadialWeight(
        x: Float,
        y: Float,
        centreX: Float,
        centreY: Float,
        cosAngle: Float,
        sinAngle: Float,
        radiusX: Float,
        radiusY: Float,
        feather: Float,
        roundness: Float,
        aspect: Float,
    ): Float {
        val localX = maskLocalX(x, y, centreX, centreY, cosAngle, sinAngle, aspect)
        val localY = maskLocalY(x, y, centreX, centreY, cosAngle, sinAngle, aspect)
        val normalisedX = abs(localX) / max(radiusX, 0.0001f)
        val normalisedY = abs(localY) / max(radiusY, 0.0001f)
        val exponent = 2f + (roundness / 100f).coerceIn(0f, 1f) * 6f
        val distance = (normalisedX.pow(exponent) + normalisedY.pow(exponent)).pow(1f / exponent)
        val inner = 1f - (feather / 100f).coerceIn(MASK_MIN_FEATHER, 1f)
        return 1f - smoothstep(inner, 1f, distance)
    }

    /**
     * Adds a component to what the previous ones have accumulated.
     *
     * `ADD` uses the maximum and not the sum: two overlapping components describe the same region,
     * not a region with twice the weight. The sum is the right operation between **masks**, in
     * parameter space, and it is wrong here.
     */
    fun combineMaskComponent(accumulated: Float, weight: Float, op: MaskOp): Float = when (op) {
        MaskOp.ADD -> max(accumulated, weight)
        MaskOp.SUBTRACT -> accumulated * (1f - weight)
        MaskOp.INTERSECT -> accumulated * weight
    }
}
