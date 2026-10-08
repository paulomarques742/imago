package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.UPRIGHT_AUTO
import eu.studio742.imago.core.model.UPRIGHT_FULL
import eu.studio742.imago.core.model.UPRIGHT_LEVEL
import eu.studio742.imago.core.model.UPRIGHT_VERTICAL
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUprightTest {
    private val width = 900
    private val height = 600
    private val aspect = width.toDouble() / height

    /**
     * A facade — a grid of window edges, vertical and level — photographed by a camera turned by
     * these angles, plus a few diagonal lines (a roof, a cable) that belong to neither axis.
     */
    private fun facade(rollDegrees: Double, pitchDegrees: Double, yawDegrees: Double): PixelBuffer {
        // From the photo to the facade: where on the facade each pixel of the photo looks.
        val camera = cameraMatrix(
            virtualFocal(aspect),
            Math.toRadians(rollDegrees),
            Math.toRadians(pitchDegrees),
            Math.toRadians(yawDegrees),
        )
        val buffer = PixelBuffer(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Nine samples per pixel: a camera's edges are anti-aliased, and so are these.
                var dark = 0
                for (sy in 0..2) for (sx in 0..2) {
                    val q = camera.mapDouble((x + (sx + 0.5) / 3) / height - aspect / 2, (y + (sy + 0.5) / 3) / height - 0.5)
                    val nearColumn = abs(q[0] / COLUMN - floor(q[0] / COLUMN + 0.5)) * COLUMN < LINE
                    val nearRow = abs(q[1] / ROW - floor(q[1] / ROW + 0.5)) * ROW < LINE
                    val diagonal = abs(q[1] - 0.6 * q[0] - 0.05) < LINE * 0.8
                    if (nearColumn || nearRow || diagonal) dark++
                }
                val shade = (0xD8 - (0xD8 - 0x30) * dark / 9)
                buffer.pixels[y * width + x] = (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
            }
        }
        return buffer
    }

    private fun solve(photo: PixelBuffer, mode: String): UprightAngles? =
        solveAutoUpright(FrameGeometry(width, height), detectLineSegments(photo), mode)

    @Test
    fun theDetectorFindsTheFacadeEdges() {
        val lines = detectLineSegments(facade(0.0, 0.0, 0.0))

        assertTrue("${lines.size} lines", lines.size >= 10)
        // Every edge of an upright facade is within a hair of an axis, except the diagonal.
        val offAxis = lines.count { line ->
            val dx = abs((line.x2 - line.x1) * aspect)
            val dy = abs((line.y2 - line.y1).toDouble())
            minOf(dx, dy) / maxOf(dx, dy) > 0.02 && minOf(dx, dy) / maxOf(dx, dy) < 0.5
        }
        assertTrue("$offAxis lines off both axes", offAxis <= 2)
    }

    @Test
    fun fullFindsTheWholeCamera() {
        val angles = solve(facade(rollDegrees = 3.0, pitchDegrees = 14.0, yawDegrees = -8.0), UPRIGHT_FULL)!!

        assertEquals(3f, angles.roll, 0.5f)
        assertEquals(14f, angles.pitch, 0.5f)
        assertEquals(-8f, angles.yaw, 0.5f)
    }

    /** Vertical makes the verticals parallel and leaves the turn alone. */
    @Test
    fun verticalStraightensVerticalsOnly() {
        val angles = solve(facade(rollDegrees = -2.0, pitchDegrees = 16.0, yawDegrees = 0.0), UPRIGHT_VERTICAL)!!

        assertEquals(-2f, angles.roll, 0.5f)
        assertEquals(16f, angles.pitch, 0.5f)
        assertEquals(0f, angles.yaw)
    }

    /** Level only rolls: a tilted camera keeps its tilt, the horizon comes out level. */
    @Test
    fun levelOnlyRolls() {
        val angles = solve(facade(rollDegrees = 4.0, pitchDegrees = 0.0, yawDegrees = 0.0), UPRIGHT_LEVEL)!!

        assertEquals(4f, angles.roll, 0.3f)
        assertEquals(0f, angles.pitch)
        assertEquals(0f, angles.yaw)
    }

    /** Auto corrects most of the way, never past the truth, and within its limits. */
    @Test
    fun autoIsBalanced() {
        val angles = solve(facade(rollDegrees = 2.0, pitchDegrees = 15.0, yawDegrees = 0.0), UPRIGHT_AUTO)!!

        assertTrue("pitch ${angles.pitch}", angles.pitch in 10f..15.5f)
        assertTrue("roll ${angles.roll}", abs(angles.roll) <= 15f)
    }

    /** A photo without straight lines — sky, water — has nothing for a mode to work on. */
    @Test
    fun aPhotoWithoutLinesFindsNothing() {
        val sky = PixelBuffer(width, height).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val shade = 120 + (y * 60 / height)
                pixels[y * width + x] = (0xFF shl 24) or (shade shl 16) or (shade shl 8) or 230
            }
        }

        assertNull(solve(sky, UPRIGHT_VERTICAL))
    }

    /**
     * Two verticals side by side say nothing about where they converge: the tilt is not moved, and
     * the fit never runs to the limit as it did on a photo of two rock edges.
     */
    @Test
    fun linesSideBySideDoNotTilt() {
        val close = listOf(
            LineSegment(0.50f, 0.2f, 0.505f, 0.8f, 300f),
            LineSegment(0.53f, 0.25f, 0.533f, 0.75f, 250f),
        )

        assertNull(solveAutoUpright(FrameGeometry(width, height), close, UPRIGHT_VERTICAL))
    }

    private companion object {
        const val COLUMN = 0.18
        const val ROW = 0.14
        const val LINE = 0.006
    }
}
