package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.MIN_UPRIGHT_GUIDES
import eu.studio742.imago.core.model.UprightGuide
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** What an Upright mode found: its own part of the camera's angles, in degrees, under the sliders. */
data class UprightAngles(val roll: Float = 0f, val pitch: Float = 0f, val yaw: Float = 0f)

/**
 * The guided mode: the camera angles that make every guide vertical or level.
 *
 * Only the camera's rotation decides whether a line is upright — the recentring, the aspect, the
 * offset and the scale that [frameTransform] adds after it are axis-aligned and keep it so. The
 * solver therefore looks for the roll, tilt and turn alone, by least squares on the sine of each
 * guide's angle away from its axis, which weighs a long guide and a short one the same: the person
 * drew both on lines they want straight.
 *
 * It searches the **total** angles, from zero, and returns them minus what the straighten and the
 * sliders already turn. The same guides therefore give the same photo whatever the sliders hold, and
 * the sliders keep adding on top, as in Lightroom.
 *
 * Two verticals fix the roll and the tilt and say nothing about the turn; a small pull towards zero
 * picks, among the answers that straighten the guides equally, the one that turns least — instead of
 * whichever the iteration happened to stop at.
 */
fun solveGuidedUpright(geometry: FrameGeometry, guides: List<UprightGuide>): UprightAngles {
    if (guides.size < MIN_UPRIGHT_GUIDES) return UprightAngles()
    val point = FloatArray(2)
    val segments = guides.map { guide ->
        geometry.orientedFromImage(guide.x1, guide.y1, point)
        val x1 = point[0].toDouble()
        val y1 = point[1].toDouble()
        geometry.orientedFromImage(guide.x2, guide.y2, point)
        val x2 = point[0].toDouble()
        val y2 = point[1].toDouble()
        UprightSegment(x1, y1, x2, y2, vertical = abs(y2 - y1) > abs(x2 - x1), weight = 1.0)
    }
    return solveUpright(geometry, segments, UprightFreedom.ALL, pull = GUIDED_PULL, robust = false)
}

/** A line in the oriented photo, centred and in photo heights, that should be [vertical] or level. */
internal class UprightSegment(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val vertical: Boolean,
    val weight: Double,
)

/** Which of the camera's angles a mode may move; the others keep what the straighten and sliders give. */
internal enum class UprightFreedom(val roll: Boolean, val pitch: Boolean, val yaw: Boolean) {
    ROLL(true, false, false),
    ROLL_AND_PITCH(true, true, false),
    ROLL_AND_YAW(true, false, true),
    ALL(true, true, true),
}

/**
 * The camera angles that make [segments] vertical or level, as the Upright part on top of the
 * straighten and the sliders.
 *
 * Levenberg–Marquardt over the total angles, with the free ones starting at zero and the others held
 * at what the straighten and the sliders give. [pull] draws the free angles towards zero: a little
 * only breaks ties, more makes a correction that the lines support weakly smaller. With [robust] the
 * fit is repeated with each line's weight shrunk by how far it still disagrees (a Cauchy weight):
 * a roof or a branch that happened to be nearly vertical stops pulling once the real verticals agree.
 */
internal fun solveUpright(
    geometry: FrameGeometry,
    segments: List<UprightSegment>,
    freedom: UprightFreedom,
    pull: Double,
    robust: Boolean,
    limits: Triple<Double, Double, Double> = Triple(MAX_ANGLE, MAX_ANGLE, MAX_ANGLE),
): UprightAngles {
    val focal = virtualFocal(geometry.orientedAspect.toDouble())
    val manual = cameraAngles(
        geometry.straighten,
        geometry.perspective.copy(uprightRoll = 0f, uprightPitch = 0f, uprightYaw = 0f),
    )
    val free = booleanArrayOf(freedom.roll, freedom.pitch, freedom.yaw)
    val held = doubleArrayOf(manual.first, manual.second, manual.third)
    val limit = doubleArrayOf(limits.first, limits.second, limits.third)
    val weights = DoubleArray(segments.size) { segments[it].weight }

    fun leans(angles: DoubleArray): DoubleArray {
        val camera = cameraMatrix(focal, angles[0], angles[1], angles[2])
        return DoubleArray(segments.size) { index ->
            val segment = segments[index]
            if (camera.w(segment.x1, segment.y1) <= 0 || camera.w(segment.x2, segment.y2) <= 0) {
                1.0
            } else {
                val start = camera.mapDouble(segment.x1, segment.y1)
                val end = camera.mapDouble(segment.x2, segment.y2)
                val dx = end[0] - start[0]
                val dy = end[1] - start[1]
                val length = hypot(dx, dy).coerceAtLeast(1e-12)
                if (segment.vertical) dx / length else dy / length
            }
        }
    }

    fun residuals(angles: DoubleArray): DoubleArray {
        val lean = leans(angles)
        val values = DoubleArray(segments.size + 3)
        for (index in segments.indices) values[index] = sqrt(weights[index]) * lean[index]
        for (axis in 0..2) values[segments.size + axis] = if (free[axis]) pull * angles[axis] else 0.0
        return values
    }

    var angles = DoubleArray(3) { if (free[it]) 0.0 else held[it] }
    repeat(if (robust) ROBUST_ROUNDS else 1) { round ->
        if (round > 0) {
            val lean = leans(angles)
            for (index in segments.indices) {
                val ratio = lean[index] / ROBUST_SCALE
                weights[index] = segments[index].weight / (1 + ratio * ratio)
            }
        }
        angles = levenbergMarquardt(angles, free, limit, ::residuals)
    }
    return UprightAngles(
        roll = if (free[0]) Math.toDegrees(angles[0] - held[0]).toFloat() else 0f,
        pitch = if (free[1]) Math.toDegrees(angles[1] - held[1]).toFloat() else 0f,
        yaw = if (free[2]) Math.toDegrees(angles[2] - held[2]).toFloat() else 0f,
    )
}

private fun levenbergMarquardt(
    start: DoubleArray,
    free: BooleanArray,
    limit: DoubleArray,
    residuals: (DoubleArray) -> DoubleArray,
): DoubleArray {
    fun cost(values: DoubleArray) = values.sumOf { it * it }
    var angles = start
    var current = residuals(angles)
    var damping = 1e-3
    repeat(MAX_ITERATIONS) {
        val jacobian = Array(3) { axis ->
            if (!free[axis]) return@Array DoubleArray(current.size)
            val nudged = angles.copyOf().also { it[axis] += STEP }
            val shifted = residuals(nudged)
            DoubleArray(current.size) { (shifted[it] - current[it]) / STEP }
        }
        val normal = Array(3) { row -> DoubleArray(3) { column -> dot(jacobian[row], jacobian[column]) } }
        val gradient = DoubleArray(3) { dot(jacobian[it], current) }
        var improved = false
        var settled = false
        while (!improved && damping < 1e12) {
            // A held angle gets an identity row: its step is zero and the system stays solvable.
            val damped = Array(3) { row ->
                DoubleArray(3) { column ->
                    when {
                        !free[row] || !free[column] -> if (row == column) 1.0 else 0.0
                        row == column -> normal[row][column] + damping * (1 + normal[row][row])
                        else -> normal[row][column]
                    }
                }
            }
            val step = solve3(damped, DoubleArray(3) { if (free[it]) -gradient[it] else 0.0 }) ?: break
            val candidate = DoubleArray(3) { (angles[it] + step[it]).coerceIn(-limit[it], limit[it]) }
            val trial = residuals(candidate)
            if (cost(trial) < cost(current)) {
                settled = sqrt(step.sumOf { it * it }) < 1e-10
                angles = candidate
                current = trial
                damping /= 3
                improved = true
            } else {
                damping *= 4
            }
        }
        if (!improved || settled) return angles
    }
    return angles
}

private fun dot(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    for (index in a.indices) sum += a[index] * b[index]
    return sum
}

/** Cramer's rule: three unknowns do not justify more. Null when the system is singular. */
private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    fun det(a: Array<DoubleArray>) =
        a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
    val determinant = det(m)
    if (abs(determinant) < 1e-300) return null
    return DoubleArray(3) { column ->
        det(Array(3) { row -> DoubleArray(3) { if (it == column) b[row] else m[row][it] } }) / determinant
    }
}

private const val MAX_ITERATIONS = 200
private const val STEP = 1e-7

/** Small enough to move a determined answer by less than a hundredth of a degree. */
private const val GUIDED_PULL = 1e-3
internal val MAX_ANGLE = Math.toRadians(45.0)

/** A line still leaning by about two degrees counts half: past that it is more likely not a vertical. */
private val ROBUST_SCALE = kotlin.math.sin(Math.toRadians(2.0))
private const val ROBUST_ROUNDS = 6
