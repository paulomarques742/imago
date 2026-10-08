package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.UPRIGHT_AUTO
import eu.studio742.imago.core.model.UPRIGHT_FULL
import eu.studio742.imago.core.model.UPRIGHT_LEVEL
import eu.studio742.imago.core.model.UPRIGHT_VERTICAL
import kotlin.math.abs
import kotlin.math.tan

/**
 * The automatic Upright modes, from the segments [detectLineSegments] found:
 *
 * - **Level** turns only the roll, from the lines that should be level (the verticals stand in when
 *   there are none): the horizon straight, nothing else.
 * - **Vertical** turns the roll and the tilt, from the lines that should be vertical: converging
 *   verticals made parallel.
 * - **Full** turns all three, from both, as far as the lines ask.
 * - **Auto** turns all three too, but balanced: a pull towards no correction shrinks what the lines
 *   support weakly, and the angles stop at 15° of roll and 25° of tilt and turn. It is the one to try
 *   first, as in Lightroom.
 *
 * Each line counts by the square of its length — a building's edges are long, a texture's are short
 * — and the fit discounts the lines that still disagree once most agree.
 *
 * An angle only moves when lines support it: the tilt needs two verticals apart across the photo, the
 * turn two levels apart down it. Two lines side by side say nothing about where they converge, and a
 * fit on them ran to the 45° limit. Full and Auto keep an unsupported angle where the sliders have it.
 * A fit that still reaches the limit is not believed either. Null in both cases, and when the photo
 * does not have the lines the mode needs: then the mode does nothing, and says so.
 */
fun solveAutoUpright(geometry: FrameGeometry, lines: List<LineSegment>, mode: String): UprightAngles? {
    val point = FloatArray(2)
    val verticals = mutableListOf<UprightSegment>()
    val levels = mutableListOf<UprightSegment>()
    for (line in lines) {
        geometry.orientedFromImage(line.x1, line.y1, point)
        val x1 = point[0].toDouble()
        val y1 = point[1].toDouble()
        geometry.orientedFromImage(line.x2, line.y2, point)
        val x2 = point[0].toDouble()
        val y2 = point[1].toDouble()
        val dx = abs(x2 - x1)
        val dy = abs(y2 - y1)
        val weight = line.length.toDouble() * line.length
        when {
            dx <= CLASS_TOLERANCE * dy -> verticals += UprightSegment(x1, y1, x2, y2, vertical = true, weight = weight)
            dy <= CLASS_TOLERANCE * dx -> levels += UprightSegment(x1, y1, x2, y2, vertical = false, weight = weight)
        }
    }

    val tiltSupported = spread(verticals) { (it.x1 + it.x2) / 2 } >= MIN_SPREAD
    val turnSupported = spread(levels) { (it.y1 + it.y2) / 2 } >= MIN_SPREAD
    val (segments, freedom) = when (mode) {
        UPRIGHT_LEVEL -> when {
            levels.isNotEmpty() -> levels to UprightFreedom.ROLL
            verticals.isNotEmpty() -> verticals to UprightFreedom.ROLL
            else -> return null
        }
        UPRIGHT_VERTICAL -> if (tiltSupported) verticals to UprightFreedom.ROLL_AND_PITCH else return null
        UPRIGHT_FULL, UPRIGHT_AUTO -> when {
            tiltSupported && turnSupported -> (verticals + levels) to UprightFreedom.ALL
            tiltSupported -> (verticals + levels) to UprightFreedom.ROLL_AND_PITCH
            turnSupported -> (verticals + levels) to UprightFreedom.ROLL_AND_YAW
            else -> return null
        }
        else -> return null
    }
    val total = segments.sumOf { it.weight }
    val normalised = segments.map { UprightSegment(it.x1, it.y1, it.x2, it.y2, it.vertical, it.weight / total) }
    val auto = mode == UPRIGHT_AUTO
    val limits = if (auto) AUTO_LIMITS else Triple(MAX_ANGLE, MAX_ANGLE, MAX_ANGLE)
    val angles = solveUpright(geometry, normalised, freedom, pull = if (auto) AUTO_PULL else EXACT_PULL, robust = true, limits = limits)
    // Auto stops at its limits by design; the other modes reaching 45° means the lines led nowhere.
    if (!auto && maxOf(abs(angles.roll), abs(angles.pitch), abs(angles.yaw)) >= UNBELIEVABLE_DEGREES) return null
    return angles
}

/** How far apart the lines lie, across [position], in photo heights. */
private fun spread(lines: List<UprightSegment>, position: (UprightSegment) -> Double): Double {
    if (lines.size < 2) return 0.0
    val positions = lines.map(position)
    return positions.max() - positions.min()
}

/** A tenth of the photo's height between two lines is enough to see where they converge. */
private const val MIN_SPREAD = 0.1
private const val UNBELIEVABLE_DEGREES = 44.5f

/** A line within 30° of an axis is taken as meant for it; one further off is neither. */
private val CLASS_TOLERANCE = tan(Math.toRadians(30.0))

private const val EXACT_PULL = 1e-3

/**
 * Auto's restraint: against a camera tilted by 15°, it leaves most of the correction and gives up the
 * part the lines support least (`AutoUprightTest` sees it land between 10° and 15°).
 */
private const val AUTO_PULL = 0.1
private val AUTO_LIMITS = Triple(Math.toRadians(15.0), Math.toRadians(25.0), Math.toRadians(25.0))
