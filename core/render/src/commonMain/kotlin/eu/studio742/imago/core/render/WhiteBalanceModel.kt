package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.WhiteBalance
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Temperature and Tint as a light, the way Lightroom treats them on a raw file.
 *
 * The Temperature slider chooses a light on the Planckian locus — 2000 K at −100, D65 at 0, 25 000 K at
 * +100, linear in mireds on each side, since equal steps in mireds are roughly equal steps of colour —
 * and the Tint slider moves it off the locus, along the locus' normal in CIE 1960 uv, by up to the
 * 0.05 that a Tint of ±150 is in the DNG SDK. A Bradford adaptation then takes that light's white to
 * D65, the white of sRGB, as one 3×3 matrix in linear sRGB. At 0, 0 the light is D65 itself and the
 * matrix the identity.
 *
 * The cold side reaches further than the warm one: below D65 there are 346 mireds to 2000 K, above it
 * only 114 to 25 000 K, and a tungsten cast — 2850 K, 197 mireds away — has to be within reach. The
 * scale of a JPEG's sliders in Lightroom is not published; these ends are the physical ones, kept here
 * so that measured renders can replace them.
 *
 * The matrix keeps grey's luminance: changing the light is not changing the exposure.
 */
object WhiteBalanceModel {
    /** The 3×3 matrix, row-major, from linear sRGB to linear sRGB, for the sliders over 100 (−1..1). */
    fun matrix(temperature: Float, tint: Float): FloatArray {
        val (x, y) = lightXy(temperature.toDouble().coerceIn(-1.0, 1.0), tint.toDouble().coerceIn(-1.0, 1.0))
        val light = lms(x / y, 1.0, (1.0 - x - y) / y)
        val gains = DoubleArray(3) { D65_LMS[it] / light[it] }
        // RGB → XYZ → LMS, scaled by the gains, and back.
        val adapt = multiply(XYZ_TO_RGB, multiply(BRADFORD_INVERSE, multiply(diagonal(gains), multiply(BRADFORD, RGB_TO_XYZ))))
        val grey = (0 until 3).sumOf { row -> LUMINANCE[row] * (adapt[row * 3] + adapt[row * 3 + 1] + adapt[row * 3 + 2]) }
        return FloatArray(9) { (adapt[it] / grey).toFloat() }
    }

    /**
     * The sliders (−1..1) whose light is the colour of [red], [green], [blue] — linear sRGB —, which is
     * what makes that colour grey. Beyond the sliders' reach they stop at their ends.
     */
    fun slidersFor(red: Float, green: Float, blue: Float): Pair<Float, Float> {
        val xyzX = RGB_TO_XYZ[0] * red + RGB_TO_XYZ[1] * green + RGB_TO_XYZ[2] * blue
        val xyzY = RGB_TO_XYZ[3] * red + RGB_TO_XYZ[4] * green + RGB_TO_XYZ[5] * blue
        val xyzZ = RGB_TO_XYZ[6] * red + RGB_TO_XYZ[7] * green + RGB_TO_XYZ[8] * blue
        val sum = xyzX + xyzY + xyzZ
        if (sum <= 0.0) return 0f to 0f
        val target = uv(xyzX / sum, xyzY / sum).let { (u, v) -> doubleArrayOf(u - D65_OFFSET[0], v - D65_OFFSET[1]) }
        // The mired whose normal passes through the colour: the offset from the locus has no
        // component along it. Bisection, since the locus bends but never doubles back.
        fun along(mired: Double): Double {
            val point = planckUv(mired)
            val tangent = tangent(mired)
            return (target[0] - point[0]) * tangent[0] + (target[1] - point[1]) * tangent[1]
        }
        var low = WARM_MIRED
        var high = COLD_MIRED
        val mired = when {
            along(low) <= 0.0 -> low
            along(high) >= 0.0 -> high
            else -> {
                repeat(60) {
                    val middle = (low + high) / 2.0
                    if (along(middle) > 0.0) low = middle else high = middle
                }
                (low + high) / 2.0
            }
        }
        val point = planckUv(mired)
        val normal = normal(mired)
        val offset = (target[0] - point[0]) * normal[0] + (target[1] - point[1]) * normal[1]
        val temperature = if (mired >= D65_MIRED) {
            -(mired - D65_MIRED) / (COLD_MIRED - D65_MIRED)
        } else {
            (D65_MIRED - mired) / (D65_MIRED - WARM_MIRED)
        }
        return temperature.coerceIn(-1.0, 1.0).toFloat() to (offset / TINT_REACH).coerceIn(-1.0, 1.0).toFloat()
    }

    /**
     * What the shader needs to build the matrix per pixel where a mask moves the sliders, made here so
     * that the two copies share every constant: row-major, from LMS to linear sRGB and back.
     */
    internal val fromLms: FloatArray get() = multiply(XYZ_TO_RGB, BRADFORD_INVERSE).toFloats()
    internal val toLms: FloatArray get() = multiply(BRADFORD, RGB_TO_XYZ).toFloats()
    internal val bradford: FloatArray get() = BRADFORD.toFloats()
    internal val d65Lms: FloatArray get() = D65_LMS.toFloats()
    internal val d65Offset: FloatArray get() = D65_OFFSET.toFloats()

    /** The ends of the Temperature slider and D65, in mireds, and the Tint's reach in uv: the shader's literals. */
    internal const val COLD_END_MIRED = 500.0
    internal const val WARM_END_MIRED = 40.0
    internal const val NEUTRAL_MIRED = 153.75153752
    internal const val TINT_REACH_UV = 0.05

    private fun DoubleArray.toFloats() = FloatArray(size) { this[it].toFloat() }

    /**
     * The sliders (−1..1) whose matrix turns grey into the colour [red], [green], [blue] (linear sRGB).
     * Grey is D65 in LMS, and the adaptation scales LMS by D65 over the light; for grey to come out as
     * `g`, the light has to be D65² over `g`, channel by channel — and [slidersFor] finds that light.
     */
    fun slidersGiving(red: Float, green: Float, blue: Float): Pair<Float, Float> {
        val toLms = multiply(BRADFORD, RGB_TO_XYZ)
        val target = DoubleArray(3) { row -> toLms[row * 3] * red + toLms[row * 3 + 1] * green + toLms[row * 3 + 2] * blue }
        val light = DoubleArray(3) { D65_LMS[it] * D65_LMS[it] / target[it] }
        val fromLms = multiply(XYZ_TO_RGB, BRADFORD_INVERSE)
        val rgb = DoubleArray(3) { row -> fromLms[row * 3] * light[0] + fromLms[row * 3 + 1] * light[1] + fromLms[row * 3 + 2] * light[2] }
        return slidersFor(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat())
    }

    /** The light the sliders describe, as CIE xy. */
    private fun lightXy(temperature: Double, tint: Double): Pair<Double, Double> {
        val mired = if (temperature < 0.0) {
            D65_MIRED + (COLD_MIRED - D65_MIRED) * -temperature
        } else {
            D65_MIRED - (D65_MIRED - WARM_MIRED) * temperature
        }
        val point = planckUv(mired)
        val normal = normal(mired)
        val u = point[0] + normal[0] * tint * TINT_REACH + D65_OFFSET[0]
        val v = point[1] + normal[1] * tint * TINT_REACH + D65_OFFSET[1]
        val denominator = 2.0 * u - 8.0 * v + 4.0
        return 3.0 * u / denominator to 2.0 * v / denominator
    }

    /** Kim et al.'s cubic spline of the Planckian locus, valid from 1667 K to 25 000 K, as CIE 1960 uv. */
    private fun planckUv(mired: Double): DoubleArray {
        val kelvin = (1.0e6 / mired).coerceIn(1667.0, 25000.0)
        val t = 1.0e3 / kelvin
        val x = if (kelvin <= 4000.0) {
            -0.2661239 * t * t * t - 0.2343589 * t * t + 0.8776956 * t + 0.179910
        } else {
            -3.0258469 * t * t * t + 2.1070379 * t * t + 0.2226347 * t + 0.240390
        }
        val y = when {
            kelvin <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            kelvin <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return uv(x, y).let { (u, v) -> doubleArrayOf(u, v) }
    }

    /** Along the locus, towards warmer light. */
    private fun tangent(mired: Double): DoubleArray {
        val warmer = planckUv(mired + 0.5)
        val cooler = planckUv(mired - 0.5)
        val du = warmer[0] - cooler[0]
        val dv = warmer[1] - cooler[1]
        val length = sqrt(du * du + dv * dv)
        return doubleArrayOf(du / length, dv / length)
    }

    /** Across the locus, towards green: a greener light is what a positive, magenta Tint corrects. */
    private fun normal(mired: Double): DoubleArray {
        val tangent = tangent(mired)
        return doubleArrayOf(-tangent[1], tangent[0])
    }

    private fun uv(x: Double, y: Double): Pair<Double, Double> {
        val denominator = -2.0 * x + 12.0 * y + 3.0
        return 4.0 * x / denominator to 6.0 * y / denominator
    }

    private fun lms(x: Double, y: Double, z: Double) = DoubleArray(3) { row ->
        BRADFORD[row * 3] * x + BRADFORD[row * 3 + 1] * y + BRADFORD[row * 3 + 2] * z
    }

    private fun diagonal(values: DoubleArray) = DoubleArray(9) { if (it % 4 == 0) values[it / 4] else 0.0 }

    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { index ->
        val row = index / 3
        val column = index % 3
        (0 until 3).sumOf { a[row * 3 + it] * b[it * 3 + column] }
    }

    private fun inverse(m: DoubleArray): DoubleArray {
        val a = m[4] * m[8] - m[5] * m[7]
        val b = m[5] * m[6] - m[3] * m[8]
        val c = m[3] * m[7] - m[4] * m[6]
        val determinant = m[0] * a + m[1] * b + m[2] * c
        check(abs(determinant) > 1e-12)
        return doubleArrayOf(
            a, m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            b, m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            c, m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        ).map { it / determinant }.toDoubleArray()
    }

    /** 2000 K. */
    private const val COLD_MIRED = COLD_END_MIRED
    /** 25 000 K. */
    private const val WARM_MIRED = WARM_END_MIRED
    /** D65's correlated colour temperature, 6504 K. */
    private const val D65_MIRED = NEUTRAL_MIRED
    private const val TINT_REACH = TINT_REACH_UV

    /** Linear sRGB to XYZ, D65 white. */
    private val RGB_TO_XYZ = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )
    private val XYZ_TO_RGB = inverse(RGB_TO_XYZ)
    private val LUMINANCE = doubleArrayOf(RGB_TO_XYZ[3], RGB_TO_XYZ[4], RGB_TO_XYZ[5])
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val BRADFORD_INVERSE = inverse(BRADFORD)

    /** sRGB's white, from the same matrix, so that the identity at 0, 0 is exact. */
    private val D65_LMS = lms(RGB_TO_XYZ[0] + RGB_TO_XYZ[1] + RGB_TO_XYZ[2], 1.0, RGB_TO_XYZ[6] + RGB_TO_XYZ[7] + RGB_TO_XYZ[8])

    /** How far D65 sits from the locus at its own temperature; carried along so that 0, 0 is D65. */
    private val D65_OFFSET: DoubleArray = run {
        val x = RGB_TO_XYZ[0] + RGB_TO_XYZ[1] + RGB_TO_XYZ[2]
        val z = RGB_TO_XYZ[6] + RGB_TO_XYZ[7] + RGB_TO_XYZ[8]
        val sum = x + 1.0 + z
        val (u, v) = uv(x / sum, 1.0 / sum)
        val locus = planckUv(D65_MIRED)
        doubleArrayOf(u - locus[0], v - locus[1])
    }
}

/**
 * The sliders of the light model that give grey the colour the older three gains gave it. The older
 * gains reach so little that the result is always within the new sliders.
 */
fun lightWhiteBalanceOf(legacy: WhiteBalance): WhiteBalance {
    if (legacy == WhiteBalance()) return legacy
    val temperature = legacy.temp / 100f
    val tint = legacy.tint / 100f
    val red = (1f + 0.20f * temperature) * (1f + 0.08f * tint)
    val green = (1f + 0.05f * tint) * (1f - 0.10f * tint)
    val blue = (1f - 0.20f * temperature) * (1f + 0.08f * tint)
    val (newTemperature, newTint) = WhiteBalanceModel.slidersGiving(red, green, blue)
    return WhiteBalance(temp = newTemperature * 100f, tint = newTint * 100f)
}

/**
 * A recipe from before process 13 with its white balance — global and each mask's — rewritten for the
 * light model, so that stamping it with a newer version does not change how its greys look. A mask's
 * Temperature and Tint add to the global ones, so it is their sum that is converted, and the mask keeps
 * the difference. The process version is left as it was: whoever stamps it decides when.
 */
fun EditRecipe.withLightWhiteBalance(): EditRecipe {
    if (processVersion >= 13) return this
    val global = lightWhiteBalanceOf(whiteBalance)
    return copy(
        whiteBalance = global,
        masks = masks.map { mask ->
            val local = mask.adjustments
            if (local.temp == 0f && local.tint == 0f) return@map mask
            val combined = lightWhiteBalanceOf(WhiteBalance(whiteBalance.temp + local.temp, whiteBalance.tint + local.tint))
            mask.copy(adjustments = local.copy(temp = combined.temp - global.temp, tint = combined.tint - global.tint))
        },
    )
}

/** Whether an older recipe has a white balance the light model would read differently. */
fun EditRecipe.hasWhiteBalance(): Boolean =
    whiteBalance != WhiteBalance() || masks.any { it.adjustments.temp != 0f || it.adjustments.tint != 0f }
