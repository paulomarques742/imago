package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightroom's Distortion slider: the barrel or pincushion of a lens, taken away.
 *
 * It is radial about the photo's centre, so no 3×3 matrix holds it, and it comes before the virtual
 * camera: the lens bent the lines before anyone turned anything. It works in the oriented photo,
 * centred and in photo heights — where [frameTransform] starts — with the radius measured against
 * the half-diagonal, which a quarter turn or a mirror leaves the same.
 *
 * The model is the first term of Brown's: a corrected point `p` at radius `r` comes from the point of
 * the photo `p · (1 + k·r²)`. Sampling goes that way, with no inverse, which is all the shader and
 * the CPU need. The other way — a point of the photo to where it lands, for masks and guides — is a
 * cubic in the radius, solved by Newton.
 *
 * Lightroom's slider takes barrel out on the positive side, so positive is a negative `k`: the edges
 * are sampled closer to the centre and spread out. At ±100, `k` is ∓[MAX_COEFFICIENT]; Lightroom's
 * scale is not published, and this one was chosen to reach the barrel of a phone's ultra-wide.
 *
 * With a negative `k` the radius the photo is sampled at stops growing at `r² = 1 / (−3k)`, past the
 * corners even at the slider's end; beyond it the map would fold back over the photo, and those
 * points are taken as outside.
 */
object LensDistortion {
    /** The `k` of [slider] (−100..100). */
    fun coefficient(slider: Float): Double = -slider.coerceIn(-100f, 100f) / 100.0 * MAX_COEFFICIENT

    /** The half-diagonal squared, in photo heights, of a photo [aspect] wide. */
    fun radiusSquared(aspect: Double): Double = (aspect * aspect + 1.0) / 4.0

    /**
     * Where in the photo the corrected point ([x], [y]) samples, written into [out]. False — and
     * nothing meaningful written — where the map has folded back: no point of the photo lands there.
     */
    fun sample(x: Double, y: Double, k: Double, radiusSquared: Double, out: DoubleArray): Boolean {
        val r2 = (x * x + y * y) / radiusSquared
        if (k < 0.0 && -3.0 * k * r2 >= 1.0) return false
        val factor = 1.0 + k * r2
        out[0] = x * factor
        out[1] = y * factor
        return true
    }

    /**
     * Where the point ([x], [y]) of the photo lands once corrected: the inverse of [sample]. False when
     * no corrected point samples it — beyond the fold, with a negative `k`.
     */
    fun correct(x: Double, y: Double, k: Double, radiusSquared: Double, out: DoubleArray): Boolean {
        val target = sqrt((x * x + y * y) / radiusSquared)
        if (k == 0.0 || target == 0.0) {
            out[0] = x
            out[1] = y
            return true
        }
        // ρ + k·ρ³ = target, in units of the half-diagonal. With k > 0 the root lies below the target;
        // with k < 0 above it, and only up to where the curve turns.
        val turn = if (k < 0.0) sqrt(1.0 / (-3.0 * k)) else Double.MAX_VALUE
        if (k < 0.0 && target > turn * (1.0 + k * turn * turn)) return false
        var low = if (k > 0.0) 0.0 else target
        var high = if (k > 0.0) target else turn
        var rho = target
        repeat(NEWTON_STEPS) {
            val value = rho + k * rho * rho * rho - target
            if (abs(value) < 1e-12) return@repeat
            if (value > 0.0) high = rho else low = rho
            val slope = 1.0 + 3.0 * k * rho * rho
            var next = rho - value / slope
            // Newton keeps to the bracket; where it would leave it, the middle does instead.
            if (!(next > low && next < high)) next = (low + high) / 2.0
            rho = next
        }
        val scale = rho / target
        out[0] = x * scale
        out[1] = y * scale
        return true
    }

    /**
     * [correct] near ([x], [y]) as an affine map, row-major 3×3: what a canvas needs to draw a small
     * piece of the photo — a loupe — where the stage puts it. Null where [correct] has no answer.
     */
    fun localCorrection(x: Double, y: Double, k: Double, radiusSquared: Double): Homography? {
        val corrected = DoubleArray(2)
        if (!correct(x, y, k, radiusSquared, corrected)) return null
        val cx = corrected[0]
        val cy = corrected[1]
        // The Jacobian of [sample] at the corrected point, inverted.
        val f = 1.0 + k * (cx * cx + cy * cy) / radiusSquared
        val g = 2.0 * k / radiusSquared
        val a = f + g * cx * cx
        val b = g * cx * cy
        val d = f + g * cy * cy
        val determinant = a * d - b * b
        if (abs(determinant) < 1e-12) return null
        val ia = d / determinant
        val ib = -b / determinant
        val id = a / determinant
        return Homography.of(
            ia, ib, cx - (ia * x + ib * y),
            ib, id, cy - (ib * x + id * y),
            0.0, 0.0, 1.0,
        )
    }

    /** `k` at the slider's ends. */
    const val MAX_COEFFICIENT = 0.25

    private const val NEWTON_STEPS = 40
}
