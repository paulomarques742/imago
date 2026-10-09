package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.render.FrameGeometry
import eu.studio742.imago.core.render.Homography
import eu.studio742.imago.core.render.NeutralWhiteBalance
import eu.studio742.imago.core.render.PhotoBounds
import eu.studio742.imago.core.render.neutralWhiteBalanceOf
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The White Balance Selector's loupe, over the stage and above its gestures.
 *
 * The loupe sits on the point being read, the photo three times bigger inside it. A finger drags it by
 * how far it moves, from anywhere on the stage, so it never covers the point; a tap takes it there. A
 * mouse carries it under the pointer and a click accepts, as Lightroom's eyedropper does. Every move
 * reads the photo as it is before any adjustment — the colour the light gave it — and hands the white
 * balance that makes it grey to [onPick], or null where the area is too dark or burnt out to say.
 */
@Composable
internal fun WhiteBalancePickerOverlay(
    bounds: PhotoBounds,
    geometry: FrameGeometry,
    photo: ImageBitmap,
    onPick: (NeutralWhiteBalance?) -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val projection = MaskProjection(bounds, geometry)
    val currentProjection by rememberUpdatedState(projection)
    val currentPick by rememberUpdatedState(onPick)
    val currentAccept by rememberUpdatedState(onAccept)
    var point by remember { mutableStateOf<Offset?>(null) }
    val description = stringResource(Res.string.editor_white_balance_picker)

    fun read(at: Offset) {
        point = at
        currentPick(sampleAt(photo, currentProjection.toImage(at)))
    }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(photo) {
                awaitPointerEventScope {
                    var pressedAt: Offset? = null
                    var grabbed: Offset? = null
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val mouse = change.type == PointerType.Mouse
                        val stage = Offset(size.width.toFloat(), size.height.toFloat())
                        fun clamped(position: Offset) = Offset(
                            position.x.coerceIn(0f, stage.x),
                            position.y.coerceIn(0f, stage.y),
                        )
                        when (event.type) {
                            PointerEventType.Press -> {
                                pressedAt = change.position
                                grabbed = point ?: change.position
                                dragging = false
                                if (mouse) read(change.position)
                            }
                            PointerEventType.Move -> {
                                val start = pressedAt
                                when {
                                    mouse -> read(change.position)
                                    start != null -> {
                                        val moved = change.position - start
                                        if (!dragging && hypot(moved.x, moved.y) > viewConfiguration.touchSlop) dragging = true
                                        if (dragging) read(clamped((grabbed ?: start) + moved))
                                    }
                                }
                            }
                            PointerEventType.Release -> {
                                if (mouse) {
                                    currentAccept()
                                } else if (!dragging && pressedAt != null) {
                                    read(change.position)
                                }
                                pressedAt = null
                                dragging = false
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val at = point ?: Offset(
            ((bounds.left + bounds.right) / 2f).coerceIn(0f, size.width),
            ((bounds.top + bounds.bottom) / 2f).coerceIn(0f, size.height),
        )
        val radius = LOUPE_RADIUS.toPx()
        val loupe = Homography.translate(at.x.toDouble(), at.y.toDouble()) *
            Homography.scale(LOUPE_ZOOM, LOUPE_ZOOM) *
            Homography.translate(-at.x.toDouble(), -at.y.toDouble())
        val screen = Homography.translate(bounds.left.toDouble(), bounds.top.toDouble()) *
            Homography.scale(bounds.width.toDouble(), bounds.height.toDouble()) *
            geometry.framedFromImagePixels()
        drawCircle(Color.Black, radius, at)
        clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(at, radius)) }) {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.concat((loupe * screen).toComposeMatrix())
                canvas.drawImage(photo, Offset.Zero, Paint())
                canvas.restore()
            }
        }
        // The square read, at the loupe's scale, so that what is averaged is what is seen.
        val half = SAMPLE_SIZE / 2f * (bounds.width / photo.width) * LOUPE_ZOOM.toFloat()
        drawRect(
            Color.White,
            topLeft = at - Offset(half, half),
            size = androidx.compose.ui.geometry.Size(half * 2f, half * 2f),
            style = Stroke(1.dp.toPx()),
        )
        drawCircle(Color.Black.copy(alpha = 0.45f), radius + 1.5.dp.toPx(), at, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, radius, at, style = Stroke(2.dp.toPx()))
    }
}

/** The square of pixels around a point in normalised image coordinates; null outside the photo. */
private fun sampleAt(photo: ImageBitmap, image: Offset): NeutralWhiteBalance? {
    if (image.x !in 0f..1f || image.y !in 0f..1f) return null
    val half = SAMPLE_SIZE / 2
    val centreX = (image.x * photo.width).roundToInt()
    val centreY = (image.y * photo.height).roundToInt()
    val left = (centreX - half).coerceIn(0, (photo.width - SAMPLE_SIZE).coerceAtLeast(0))
    val top = (centreY - half).coerceIn(0, (photo.height - SAMPLE_SIZE).coerceAtLeast(0))
    val width = SAMPLE_SIZE.coerceAtMost(photo.width)
    val height = SAMPLE_SIZE.coerceAtMost(photo.height)
    val pixels = IntArray(width * height)
    photo.readPixels(pixels, startX = left, startY = top, width = width, height = height)
    return neutralWhiteBalanceOf(pixels)
}

/** Five pixels a side: enough to average the noise out, small enough to stay on a grey card. */
private const val SAMPLE_SIZE = 5
private val LOUPE_RADIUS = 48.dp
private const val LOUPE_ZOOM = 3.0
