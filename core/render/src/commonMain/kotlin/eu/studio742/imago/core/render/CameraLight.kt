package eu.studio742.imago.core.render

import kotlin.math.abs

/**
 * A RAW's white balance in kelvin, as Lightroom shows it for a RAW: the scene's light, not a shift.
 *
 * The recipe keeps Temperature and Tint as they are for every photo — a light relative to the
 * development, which is balanced for the light the camera recorded ("As Shot") — so the render, the
 * masks and a recipe carried to a JPEG need nothing new. What a RAW adds is the camera's colour: the
 * development's [xyzToDeveloped] matrix says what colour any light takes in it, and through it the
 * sliders and a light in kelvin are two names for the same thing. As Shot is the light that comes out
 * white.
 *
 * The camera's matrix is LibRaw's single one, made for daylight; under tungsten the kelvin it reads is
 * an estimate, as is every converter's that does not interpolate two matrices.
 */
class CameraLight private constructor(private val toDeveloped: DoubleArray, private val fromDeveloped: DoubleArray) {
    /** The light the camera recorded, in kelvin and Tint. */
    val asShot: KelvinTint = kelvinTint(0f, 0f)

    /** The light that Temperature and Tint ([−100, 100]) neutralise, in kelvin and Tint. */
    fun kelvinTint(temperature: Float, tint: Float): KelvinTint {
        val colour = WhiteBalanceModel.lightColour(temperature / 100f, tint / 100f)
        // The colour that light has in the development, back to the scene's XYZ.
        val scene = multiply(fromDeveloped, DoubleArray(3) { colour[it].toDouble() })
        val (kelvin, sceneTint) = WhiteBalanceModel.kelvinAndTint(scene[0], scene[1], scene[2])
        return KelvinTint(kelvin, sceneTint)
    }

    /** The Temperature and Tint ([−100, 100]) that neutralise a light in kelvin and Tint; at the sliders' ends past their reach. */
    fun sliders(kelvin: Float, tint: Float): Pair<Float, Float> {
        val developed = multiply(toDeveloped, WhiteBalanceModel.xyzOf(kelvin, tint))
        val (temperature, sliderTint) = WhiteBalanceModel.slidersFor(developed[0].toFloat(), developed[1].toFloat(), developed[2].toFloat())
        return temperature * 100f to sliderTint * 100f
    }

    companion object {
        /** From [RawImage.xyzToDeveloped]: null when the camera's colour is unknown, and then the sliders stay relative. */
        fun of(xyzToDeveloped: FloatArray?): CameraLight? {
            if (xyzToDeveloped == null || xyzToDeveloped.size != 9 || xyzToDeveloped.any { !it.isFinite() }) return null
            val toDeveloped = DoubleArray(9) { xyzToDeveloped[it].toDouble() }
            val fromDeveloped = invert(toDeveloped) ?: return null
            return CameraLight(toDeveloped, fromDeveloped)
        }

        private fun multiply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { row -> m[row * 3] * v[0] + m[row * 3 + 1] * v[1] + m[row * 3 + 2] * v[2] }

        private fun invert(m: DoubleArray): DoubleArray? {
            val a = m[4] * m[8] - m[5] * m[7]
            val b = m[5] * m[6] - m[3] * m[8]
            val c = m[3] * m[7] - m[4] * m[6]
            val determinant = m[0] * a + m[1] * b + m[2] * c
            if (abs(determinant) < 1e-12) return null
            return doubleArrayOf(
                a, m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
                b, m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
                c, m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
            ).map { it / determinant }.toDoubleArray()
        }
    }
}

/** A light as Lightroom names it for a RAW. */
data class KelvinTint(val kelvin: Float, val tint: Float)
