package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.model.UprightGuide
import eu.studio742.imago.core.render.FrameGeometry
import eu.studio742.imago.core.render.Homography
import eu.studio742.imago.core.render.PhotoBounds
import kotlin.math.hypot

/** Which end of which guide a finger holds. */
internal data class GuideGrip(val index: Int, val end: Int)

/**
 * The end of a guide under [position], if any, within [radius] screen pixels. The nearest one wins:
 * two guides meeting at a corner are both reachable.
 */
internal fun hitGuideEnd(guides: List<UprightGuide>, projection: MaskProjection, position: Offset, radius: Float): GuideGrip? {
    var best: GuideGrip? = null
    var bestDistance = radius
    guides.forEachIndexed { index, guide ->
        for (end in 1..2) {
            val point = if (end == 1) projection.toScreen(guide.x1, guide.y1) else projection.toScreen(guide.x2, guide.y2)
            val distance = hypot(point.x - position.x, point.y - position.y)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = GuideGrip(index, end)
            }
        }
    }
    return best
}

internal fun UprightGuide.withEnd(end: Int, x: Float, y: Float): UprightGuide =
    if (end == 1) copy(x1 = x, y1 = y) else copy(x2 = x, y2 = y)

/**
 * A [Homography] as Compose's 4×4 matrix, which a canvas can concatenate. Compose maps a point as
 * `this[input, output]`, with the depth in row and column 3: the 2D matrix goes there transposed.
 */
internal fun Homography.toComposeMatrix(): Matrix {
    val h = values()
    return Matrix().apply {
        this[0, 0] = h[0]; this[1, 0] = h[1]; this[3, 0] = h[2]
        this[0, 1] = h[3]; this[1, 1] = h[4]; this[3, 1] = h[5]
        this[0, 3] = h[6]; this[1, 3] = h[7]; this[3, 3] = h[8]
    }
}

/**
 * The guided Upright's guides over the photo and, while a finger holds one, the loupe.
 *
 * The finger covers exactly the point being placed, which is why there is a loupe: the photo three
 * times bigger, off to the side, with the crosshair where the finger is. It draws the photo through
 * [FrameGeometry.framedFromImagePixels] — the stage's own geometry, as one matrix — so the line seen in
 * the loupe runs at the same angle as on the stage. The tones are not there: the loupe is for lines,
 * and lines are in the pixels before any adjustment.
 */
@Composable
internal fun UprightGuidesOverlay(
    guides: List<UprightGuide>,
    bounds: PhotoBounds,
    geometry: FrameGeometry,
    photo: ImageBitmap?,
    finger: Offset?,
    modifier: Modifier = Modifier,
) {
    val projection = MaskProjection(bounds, geometry)
    Canvas(modifier) {
        val line = 2.dp.toPx()
        val handle = 7.dp.toPx()
        fun drawGuides(map: (Offset) -> Offset, width: Float) {
            guides.forEach { guide ->
                val start = map(projection.toScreen(guide.x1, guide.y1))
                val end = map(projection.toScreen(guide.x2, guide.y2))
                drawLine(Color.Black.copy(alpha = 0.5f), start, end, width + 2.dp.toPx())
                drawLine(ImagoColors.Gold, start, end, width)
            }
        }
        drawGuides({ it }, line)
        guides.forEach { guide ->
            for (point in listOf(projection.toScreen(guide.x1, guide.y1), projection.toScreen(guide.x2, guide.y2))) {
                drawCircle(Color.Black.copy(alpha = 0.5f), handle + 1.dp.toPx(), point)
                drawCircle(Color.White, handle, point)
                drawCircle(ImagoColors.Gold, handle * 0.55f, point)
            }
        }

        val touch = finger ?: return@Canvas
        val radius = LOUPE_RADIUS.toPx()
        val gap = LOUPE_GAP.toPx()
        // Above the finger, unless that is off the stage: then below.
        val centre = Offset(
            touch.x.coerceIn(radius, size.width - radius),
            if (touch.y - gap - radius >= 0f) touch.y - gap else touch.y + gap,
        )
        val loupe = Homography.translate(centre.x.toDouble(), centre.y.toDouble()) *
            Homography.scale(LOUPE_ZOOM, LOUPE_ZOOM) *
            Homography.translate(-touch.x.toDouble(), -touch.y.toDouble())
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, radius)) }
        drawCircle(Color.Black, radius, centre)
        clipPath(circle) {
            if (photo != null) {
                val screen = Homography.translate(bounds.left.toDouble(), bounds.top.toDouble()) *
                    Homography.scale(bounds.width.toDouble(), bounds.height.toDouble()) *
                    geometry.framedFromImagePixels()
                drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.concat((loupe * screen).toComposeMatrix())
                    canvas.drawImage(photo, Offset.Zero, Paint())
                    canvas.restore()
                }
            }
            val scratch = FloatArray(2)
            drawGuides({ point ->
                loupe.map(point.x, point.y, scratch)
                Offset(scratch[0], scratch[1])
            }, line)
        }
        val cross = 9.dp.toPx()
        drawLine(Color.White, centre - Offset(cross, 0f), centre + Offset(cross, 0f), 1.dp.toPx())
        drawLine(Color.White, centre - Offset(0f, cross), centre + Offset(0f, cross), 1.dp.toPx())
        drawCircle(Color.White, radius, centre, style = Stroke(2.dp.toPx()))
    }
}

private val LOUPE_RADIUS = 56.dp
/** From the finger to the loupe's centre: enough for the loupe to clear a fingertip. */
private val LOUPE_GAP = 96.dp
private const val LOUPE_ZOOM = 3.0
