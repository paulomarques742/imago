package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGeometryTest {
    private val geometries: List<FrameGeometry> = buildList {
        for (turns in 0..3) {
            for (mirrorH in listOf(false, true)) {
                for (mirrorV in listOf(false, true)) {
                    for (straighten in listOf(0f, -30f, -5f, 7f, 30f)) {
                        add(
                            FrameGeometry(
                                sourceWidth = 4000,
                                sourceHeight = 3000,
                                cropX = 0.1f,
                                cropY = 0.2f,
                                cropWidth = 0.6f,
                                cropHeight = 0.5f,
                                straighten = straighten,
                                quarterTurns = turns,
                                mirrorH = mirrorH,
                                mirrorV = mirrorV,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * The two directions have to cancel out. It is the only way for the interface overlay and the mask
     * field not to diverge silently — the symptom would be a mask drawn in one place and applied in
     * another, and only with crop or rotation.
     */
    @Test
    fun theTwoDirectionsCancelOut() {
        val there = FloatArray(2)
        val back = FloatArray(2)
        for (geometry in geometries) {
            for (u in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    geometry.imageFromFramed(u, v, there)
                    geometry.framedFromImage(there[0], there[1], back)
                    assertEquals("u on $geometry", u, back[0], 1e-4f)
                    assertEquals("v on $geometry", v, back[1], 1e-4f)
                }
            }
        }
    }

    /** Without any geometry, the visible frame is the image and the coordinates pass through intact. */
    @Test
    fun withoutGeometryTheFrameIsTheImage() {
        val out = FloatArray(2)
        FrameGeometry(4000, 3000).imageFromFramed(0.3f, 0.8f, out)
        assertEquals(0.3f, out[0], 1e-6f)
        assertEquals(0.8f, out[1], 1e-6f)
    }

    /**
     * The continuous model of what `applyRecipeGeometry` and `FullResolutionExporter.applyGeometry` do
     * to the bitmap: rotation, then flip, then straighten, then crop — in the image → frame direction,
     * which is the direction the CPU works in.
     *
     * `Matrix().setScale(-1, 1)` takes x to 1 - x. `Matrix().setRotate(90)` takes (x, y) to (-y, x),
     * and `createBitmap` translates the result into the bounds, which in normalised coordinates gives
     * (1 - y, x). The straightening `Canvas` rotates clockwise with the coverage zoom, around the
     * centre.
     */
    private fun cpuFramedFromImage(geometry: FrameGeometry, u: Float, v: Float): FloatArray {
        var x = u
        var y = v
        repeat(geometry.quarterTurns) {
            val nextX = 1f - y
            val nextY = x
            x = nextX
            y = nextY
        }
        if (geometry.mirrorH) x = 1f - x
        if (geometry.mirrorV) y = 1f - y
        val swaps = geometry.quarterTurns % 2 == 1
        val width = if (swaps) geometry.sourceHeight else geometry.sourceWidth
        val height = if (swaps) geometry.sourceWidth else geometry.sourceHeight
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val radians = Math.toRadians(geometry.straighten.toDouble()).toFloat()
        val scale = straightenCoverScale(aspect, geometry.straighten)
        val pixelX = (x - 0.5f) * aspect
        val pixelY = y - 0.5f
        val rotatedX = (cos(radians) * pixelX - sin(radians) * pixelY) * scale
        val rotatedY = (sin(radians) * pixelX + cos(radians) * pixelY) * scale
        val straightenedX = rotatedX / aspect + 0.5f
        val straightenedY = rotatedY + 0.5f
        return floatArrayOf(
            (straightenedX - geometry.cropX) / geometry.cropWidth,
            (straightenedY - geometry.cropY) / geometry.cropHeight,
        )
    }

    /**
     * The contract masks need: what the preview shows and what the export writes have to be the same
     * framing. A mask is defined over one and applied over the other, and any disagreement here shows
     * up as the mask in the wrong place.
     */
    @Test
    fun thePreviewAndTheExportAgreeOnWhereEachPixelLands() {
        val shader = FloatArray(2)
        val disagreeing = mutableListOf<String>()
        for (geometry in geometries) {
            geometry.framedFromImage(0.2f, 0.3f, shader)
            val cpu = cpuFramedFromImage(geometry, 0.2f, 0.3f)
            if (abs(shader[0] - cpu[0]) > 1e-3f || abs(shader[1] - cpu[1]) > 1e-3f) {
                disagreeing += "voltas=${geometry.quarterTurns} espelhoH=${geometry.mirrorH} " +
                    "espelhoV=${geometry.mirrorV} endireitar=${geometry.straighten}: " +
                    "shader=(${shader[0]}, ${shader[1]}) cpu=(${cpu[0]}, ${cpu[1]})"
            }
        }
        assertTrue(
            "The preview and the export disagree on ${disagreeing.size} geometries:\n" +
                disagreeing.take(8).joinToString("\n"),
            disagreeing.isEmpty(),
        )
    }
}
