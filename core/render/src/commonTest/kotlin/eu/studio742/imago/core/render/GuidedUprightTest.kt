package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.UprightGuide
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedUprightTest {
    private val width = 4500
    private val height = 3000
    private val aspect = width.toDouble() / height

    /**
     * Guides drawn on a photo taken by a camera turned by these angles: lines that are vertical and
     * level in the corrected view, carried back to where they lie in the photo.
     */
    private fun guidesFor(rollDegrees: Double, pitchDegrees: Double, yawDegrees: Double, verticals: Int, levels: Int): List<UprightGuide> {
        val camera = cameraMatrix(
            virtualFocal(aspect),
            Math.toRadians(rollDegrees),
            Math.toRadians(pitchDegrees),
            Math.toRadians(yawDegrees),
        ).inverse()
        fun toImage(x: Double, y: Double): Pair<Float, Float> {
            val q = camera.mapDouble(x, y)
            return (q[0] / aspect + 0.5).toFloat() to (q[1] + 0.5).toFloat()
        }
        fun guide(x1: Double, y1: Double, x2: Double, y2: Double): UprightGuide {
            val (u1, v1) = toImage(x1, y1)
            val (u2, v2) = toImage(x2, y2)
            return UprightGuide(u1, v1, u2, v2)
        }
        return buildList {
            listOf(-0.45, 0.4).take(verticals).forEach { add(guide(it, -0.3, it, 0.3)) }
            listOf(-0.25, 0.2).take(levels).forEach { add(guide(-0.5, it, 0.5, it)) }
        }
    }

    /** How far each guide is from its axis in the frame the stage shows, as the sine of the angle. */
    private fun worstLean(geometry: FrameGeometry, guides: List<UprightGuide>): Float {
        val start = FloatArray(2)
        val end = FloatArray(2)
        return guides.maxOf { guide ->
            geometry.framedFromImage(guide.x1, guide.y1, start)
            geometry.framedFromImage(guide.x2, guide.y2, end)
            // Back to photo proportions, or a vertical would look tilted by the frame's aspect.
            val swaps = geometry.quarterTurns % 2 == 1
            val frameAspect = (if (swaps) height.toFloat() / width else width.toFloat() / height) *
                geometry.cropWidth / geometry.cropHeight
            val dx = (end[0] - start[0]) * frameAspect
            val dy = end[1] - start[1]
            val length = hypot(dx, dy)
            if (abs(dy) > abs(dx)) abs(dx) / length else abs(dy) / length
        }
    }

    private fun FrameGeometry.with(angles: UprightAngles) = copy(
        perspective = perspective.copy(uprightRoll = angles.roll, uprightPitch = angles.pitch, uprightYaw = angles.yaw),
    )

    @Test
    fun twoVerticalsAndTwoLevelsFindTheCamera() {
        val guides = guidesFor(rollDegrees = 3.0, pitchDegrees = 14.0, yawDegrees = -9.0, verticals = 2, levels = 2)

        val angles = solveGuidedUpright(FrameGeometry(width, height), guides)

        assertEquals(3f, angles.roll, 0.05f)
        assertEquals(14f, angles.pitch, 0.05f)
        assertEquals(-9f, angles.yaw, 0.05f)
        assertTrue(worstLean(FrameGeometry(width, height).with(angles), guides) < 1e-3f)
    }

    /**
     * Two verticals say nothing about the turn: the answer is the one that turns least in all, which
     * leaves the turn within a tenth of a degree of none — the family of answers is curved in these
     * three angles, so it is not exactly zero.
     */
    @Test
    fun twoVerticalsStraightenWithoutTurning() {
        val guides = guidesFor(rollDegrees = -2.0, pitchDegrees = 20.0, yawDegrees = 0.0, verticals = 2, levels = 0)

        val angles = solveGuidedUpright(FrameGeometry(width, height), guides)

        assertEquals(0f, angles.yaw, 0.1f)
        assertTrue(worstLean(FrameGeometry(width, height).with(angles), guides) < 1e-3f)
    }

    /**
     * The sliders and the straighten keep adding on top: the guided part is what is missing, so the
     * guides come out straight whatever they hold.
     */
    @Test
    fun theSlidersStayOnTop() {
        val guides = guidesFor(rollDegrees = 1.0, pitchDegrees = 12.0, yawDegrees = 6.0, verticals = 2, levels = 2)
        val geometry = FrameGeometry(
            width,
            height,
            straighten = 4f,
            perspective = PerspectiveParameters(vertical = -10f, horizontal = 15f, aspect = 20f, offsetX = 10f),
        )

        val angles = solveGuidedUpright(geometry, guides)

        assertTrue(worstLean(geometry.with(angles), guides) < 1e-3f)
    }

    /** The guides live in the original image: a turn or a mirror applied later changes nothing. */
    @Test
    fun turnsAndMirrorsDoNotBendTheGuides() {
        val guides = guidesFor(rollDegrees = 2.0, pitchDegrees = 10.0, yawDegrees = -5.0, verticals = 2, levels = 1)
        for (turns in 0..3) {
            for (mirror in listOf(false, true)) {
                val geometry = FrameGeometry(width, height, quarterTurns = turns, mirrorH = mirror, cropX = 0.1f, cropWidth = 0.7f)
                val angles = solveGuidedUpright(geometry, guides)
                assertTrue("turns $turns, mirror $mirror", worstLean(geometry.with(angles), guides) < 2e-3f)
            }
        }
    }

    @Test
    fun oneGuideDoesNothingYet() {
        val guides = guidesFor(rollDegrees = 0.0, pitchDegrees = 15.0, yawDegrees = 0.0, verticals = 1, levels = 0)

        assertEquals(UprightAngles(), solveGuidedUpright(FrameGeometry(width, height), guides))
    }

    /** The single matrix a canvas draws with lands every pixel where [FrameGeometry.framedFromImage] does. */
    @Test
    fun theFrameMatrixAgreesWithThePointMapping() {
        val viaMatrix = FloatArray(2)
        val viaPoints = FloatArray(2)
        for (turns in 0..3) {
            for (mirrorV in listOf(false, true)) {
                val geometry = FrameGeometry(
                    width,
                    height,
                    cropX = 0.1f,
                    cropY = 0.05f,
                    cropWidth = 0.7f,
                    cropHeight = 0.8f,
                    straighten = -6f,
                    quarterTurns = turns,
                    mirrorV = mirrorV,
                    perspective = PerspectiveParameters(vertical = -30f, uprightYaw = 4f),
                )
                val matrix = geometry.framedFromImagePixels()
                for ((u, v) in listOf(0.2f to 0.3f, 0.5f to 0.5f, 0.85f to 0.7f)) {
                    matrix.map(u * width, v * height, viaMatrix)
                    geometry.framedFromImage(u, v, viaPoints)
                    assertEquals(viaPoints[0], viaMatrix[0], 1e-4f)
                    assertEquals(viaPoints[1], viaMatrix[1], 1e-4f)
                }
            }
        }
    }
}
