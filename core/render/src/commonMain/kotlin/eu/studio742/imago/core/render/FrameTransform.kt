package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The perspective as the pipeline receives it. The units are the recipe's sliders; see
 * `eu.studio742.imago.core.model.Perspective` for what each one means.
 */
data class PerspectiveParameters(
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    val aspect: Float = 0f,
    val scale: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val constrainCrop: Boolean = true,
    /** What an Upright mode found, in degrees, under the sliders. */
    val uprightRoll: Float = 0f,
    val uprightPitch: Float = 0f,
    val uprightYaw: Float = 0f,
) {
    /** Nothing but the straighten's rotation and its cover zoom: the matrix there was before version 10. */
    val isNeutral: Boolean
        get() = vertical == 0f && horizontal == 0f && aspect == 0f && scale == 0f &&
            offsetX == 0f && offsetY == 0f && constrainCrop &&
            uprightRoll == 0f && uprightPitch == 0f && uprightYaw == 0f
}

/**
 * A 3×3 projective matrix, row-major, acting on `(x, y, 1)`.
 *
 * Computed in doubles: a strong tilt puts the photo's corners near the virtual camera's horizon, and
 * in floats the inverse loses the last pixel.
 */
class Homography internal constructor(private val m: DoubleArray) {
    /**
     * Writes the image of `(x, y)` into [out]. Returns false — and writes nothing meaningful — when
     * the point is behind the camera, which only happens beyond what the photo covers.
     */
    fun map(x: Float, y: Float, out: FloatArray): Boolean {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= W_EPSILON) return false
        out[0] = ((m[0] * x + m[1] * y + m[2]) / w).toFloat()
        out[1] = ((m[3] * x + m[4] * y + m[5]) / w).toFloat()
        return true
    }

    fun inverse(): Homography {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val determinant = a[0] * c00 + a[1] * c01 + a[2] * c02
        val k = 1.0 / determinant
        return Homography(
            doubleArrayOf(
                c00 * k, (a[2] * a[7] - a[1] * a[8]) * k, (a[1] * a[5] - a[2] * a[4]) * k,
                c01 * k, (a[0] * a[8] - a[2] * a[6]) * k, (a[2] * a[3] - a[0] * a[5]) * k,
                c02 * k, (a[1] * a[6] - a[0] * a[7]) * k, (a[0] * a[4] - a[1] * a[3]) * k,
            ),
        )
    }

    /** The nine values, row-major, for a GL uniform uploaded transposed or an Android `Matrix`. */
    fun values(): FloatArray = FloatArray(9) { m[it].toFloat() }

    operator fun times(other: Homography): Homography {
        val a = m
        val b = other.m
        return Homography(
            DoubleArray(9) { index ->
                val row = index / 3
                val column = index % 3
                a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column]
            },
        )
    }

    internal fun mapDouble(x: Double, y: Double): DoubleArray {
        val w = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    internal fun w(x: Double, y: Double): Double = m[6] * x + m[7] * y + m[8]

    companion object {
        fun of(vararg values: Double) = Homography(values)
        fun translate(x: Double, y: Double) = of(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0)
        fun scale(x: Double, y: Double) = of(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 1.0)
    }
}

/**
 * From the oriented photo to the frame, both centred and measured in photo heights — the photo's own
 * width is [aspect].
 *
 * The photo is a plane seen by a pinhole camera with the principal point at the centre, and the
 * correction turns that camera: `K · Rz · Rx · Ry · K⁻¹`. The roll about the optical axis is the
 * straighten, and with the principal point at the centre it is exactly the 2D rotation the straighten
 * always was. The tilt (`Rx`, from [PerspectiveParameters.vertical]) and the turn (`Ry`, from
 * [PerspectiveParameters.horizontal]) move the optical axis, so the photo's centre is brought back to
 * the frame's before the aspect, the offset and the scale.
 *
 * The focal length is fixed at 28 mm on full frame, as darktable's generic lens, and not read from the
 * EXIF: Immich gives the real focal length and not the equivalent, many phone photos carry none, and
 * a recipe has to render the same on every device. It only changes how much each slider step turns.
 *
 * With [PerspectiveParameters.constrainCrop] the scale is the smallest that keeps the whole frame
 * inside the transformed photo: each corner of the frame against each side of the transformed photo
 * is a linear bound on the scale, and the answer is the largest of them. With the perspective neutral
 * it is [straightenCoverScale] itself, so that every recipe from before version 10 renders as it did.
 */
fun frameTransform(aspect: Float, straighten: Float, perspective: PerspectiveParameters): Homography {
    val a = aspect.toDouble().coerceAtLeast(0.0001)
    if (perspective.isNeutral) {
        val cover = straightenCoverScale(aspect, straighten).toDouble()
        return Homography.scale(cover, cover) * rollMatrix(Math.toRadians(straighten.toDouble()))
    }

    val focal = virtualFocal(a)
    val (roll, sliderTilt, sliderTurn) = cameraAngles(straighten, perspective)
    var tilt = sliderTilt
    var turn = sliderTurn
    // Both at their extremes would take a corner of the photo behind the camera. The two shrink
    // together until the nearest corner is in front again, which keeps the slider monotonic.
    var camera = cameraMatrix(focal, roll, tilt, turn)
    if (nearestCornerDepth(camera, a) < MIN_CORNER_DEPTH) {
        var low = 0.0
        var high = 1.0
        repeat(30) {
            val middle = (low + high) / 2
            if (nearestCornerDepth(cameraMatrix(focal, roll, tilt * middle, turn * middle), a) >= MIN_CORNER_DEPTH) {
                low = middle
            } else {
                high = middle
            }
        }
        tilt *= low
        turn *= low
        camera = cameraMatrix(focal, roll, tilt, turn)
    }

    val centre = camera.mapDouble(0.0, 0.0)
    val stretchAcross = 1.0 + max(perspective.aspect, 0f) / 100.0
    val stretchDown = 1.0 + max(-perspective.aspect, 0f) / 100.0
    val shaped = Homography.scale(stretchAcross, stretchDown) * Homography.translate(-centre[0], -centre[1]) * camera

    var offsetX = perspective.offsetX / 100.0 * OFFSET_RANGE * a
    var offsetY = perspective.offsetY / 100.0 * OFFSET_RANGE
    val scale: Double
    if (perspective.constrainCrop) {
        val photo = listOf(
            shaped.mapDouble(-a / 2, -0.5),
            shaped.mapDouble(a / 2, -0.5),
            shaped.mapDouble(a / 2, 0.5),
            shaped.mapDouble(-a / 2, 0.5),
        )
        val sides = photo.indices.map { index -> Side.between(photo[index], photo[(index + 1) % photo.size]) }
        // The offset may not take the frame's centre out of the photo, or no scale would cover it: it
        // stops halfway to the nearest side.
        var reach = 1.0
        sides.forEach { side ->
            val towards = side.normalX * offsetX + side.normalY * offsetY
            val room = side.distance
            if (-towards > 0) reach = min(reach, OFFSET_SLACK * room / -towards)
        }
        offsetX *= reach
        offsetY *= reach
        var cover = 0.0
        for (side in sides) {
            val distance = side.distance + side.normalX * offsetX + side.normalY * offsetY
            for (cornerX in doubleArrayOf(-a / 2, a / 2)) {
                for (cornerY in doubleArrayOf(-0.5, 0.5)) {
                    cover = max(cover, (side.normalX * cornerX + side.normalY * cornerY) / distance)
                }
            }
        }
        scale = cover * (1.0 + max(perspective.scale, 0f) / 100.0)
    } else {
        scale = max(1.0 + perspective.scale / 100.0, MIN_FREE_SCALE)
    }
    return Homography.scale(scale, scale) * Homography.translate(offsetX, offsetY) * shaped
}

/**
 * [frameTransform] for a bitmap of [width] × [height] pixels already turned and flipped, from its
 * pixels to the frame's: what an Android `Canvas` draws with. Composed here, and not with the
 * platform matrix's pre and post operations, so that the desktop tests check it.
 */
fun frameTransformInPixels(width: Int, height: Int, straighten: Float, perspective: PerspectiveParameters): Homography {
    val w = width.toDouble()
    val h = height.toDouble().coerceAtLeast(1.0)
    return Homography.translate(w / 2, h / 2) * Homography.scale(h, h) *
        frameTransform((w / h).toFloat(), straighten, perspective) *
        Homography.scale(1 / h, 1 / h) * Homography.translate(-w / 2, -h / 2)
}

/** The virtual camera's focal length, in photo heights, for a photo [aspect] wide. */
internal fun virtualFocal(aspect: Double): Double = VIRTUAL_FOCAL_PER_DIAGONAL * sqrt(aspect * aspect + 1.0)

/**
 * Roll, tilt and turn, in radians: the straighten and the sliders, on top of what an Upright mode
 * found. The Upright solver works in these same totals and subtracts the rest to find its own part.
 */
internal fun cameraAngles(straighten: Float, perspective: PerspectiveParameters): Triple<Double, Double, Double> = Triple(
    Math.toRadians((straighten + perspective.uprightRoll).toDouble()),
    -perspective.vertical / 100.0 * MAX_TILT_RADIANS + Math.toRadians(perspective.uprightPitch.toDouble()),
    perspective.horizontal / 100.0 * MAX_TILT_RADIANS + Math.toRadians(perspective.uprightYaw.toDouble()),
)

/** A side of the transformed photo as `normal · p <= distance`, with the normal pointing out. */
private class Side(val normalX: Double, val normalY: Double, val distance: Double) {
    companion object {
        fun between(start: DoubleArray, end: DoubleArray): Side {
            var normalX = end[1] - start[1]
            var normalY = start[0] - end[0]
            val length = sqrt(normalX * normalX + normalY * normalY).coerceAtLeast(1e-12)
            normalX /= length
            normalY /= length
            var distance = normalX * start[0] + normalY * start[1]
            // The centre is inside the photo by construction; the normal points away from it.
            if (distance < 0) {
                normalX = -normalX
                normalY = -normalY
                distance = -distance
            }
            return Side(normalX, normalY, distance)
        }
    }
}

private fun rollMatrix(radians: Double): Homography {
    val cosine = cos(radians)
    val sine = sin(radians)
    return Homography.of(cosine, -sine, 0.0, sine, cosine, 0.0, 0.0, 0.0, 1.0)
}

internal fun cameraMatrix(focal: Double, roll: Double, tilt: Double, turn: Double): Homography {
    val tiltCos = cos(tilt)
    val tiltSin = sin(tilt)
    val turnCos = cos(turn)
    val turnSin = sin(turn)
    // K · Rx · K⁻¹ and K · Ry · K⁻¹, already multiplied out with K = diag(f, f, 1).
    val tilted = Homography.of(1.0, 0.0, 0.0, 0.0, tiltCos, -tiltSin * focal, 0.0, tiltSin / focal, tiltCos)
    val turned = Homography.of(turnCos, 0.0, turnSin * focal, 0.0, 1.0, 0.0, -turnSin / focal, 0.0, turnCos)
    return rollMatrix(roll) * tilted * turned
}

private fun nearestCornerDepth(camera: Homography, aspect: Double): Double = minOf(
    camera.w(-aspect / 2, -0.5),
    camera.w(aspect / 2, -0.5),
    camera.w(aspect / 2, 0.5),
    camera.w(-aspect / 2, 0.5),
)

/** 28 mm over the 43.27 mm diagonal of a full-frame sensor: darktable's generic lens. */
private const val VIRTUAL_FOCAL_PER_DIAGONAL = 28.0 / 43.266615
internal val MAX_TILT_RADIANS = Math.toRadians(45.0)

/** How close to the camera's plane a corner may come before both angles shrink. */
private const val MIN_CORNER_DEPTH = 0.1

/** An offset of 100 moves the photo by half its own width or height. */
private const val OFFSET_RANGE = 0.5
private const val OFFSET_SLACK = 0.5
private const val MIN_FREE_SCALE = 0.5
private const val W_EPSILON = 1e-9
