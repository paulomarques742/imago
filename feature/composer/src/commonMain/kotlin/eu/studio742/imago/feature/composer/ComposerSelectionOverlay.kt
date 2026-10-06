package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.ResizeCorner
import eu.studio742.imago.core.composition.rotateVector
import eu.studio742.imago.core.designsystem.ImagoColors

/** The visible disc. The touch target is [HandleTouchRadius], much larger, as accessibility requires. */
private val HandleRadius = 7.dp
private val HandleTouchRadius = 22.dp

/** Distance from the rotation handle to the top of the element. */
private val RotationHandleGap = 30.dp

private enum class SelectionHandle(val corner: ResizeCorner?) {
    TOP_LEFT(ResizeCorner.TOP_LEFT),
    TOP_RIGHT(ResizeCorner.TOP_RIGHT),
    BOTTOM_LEFT(ResizeCorner.BOTTOM_LEFT),
    BOTTOM_RIGHT(ResizeCorner.BOTTOM_RIGHT),
    ROTATE(null),
}

/**
 * The selection frame: outline, four corner handles and a rotation handle.
 *
 * Only the **drawing** rotates; the node that receives the finger stays still and aligned with the
 * screen. Two reasons: with the `pointerInput` inside a rotated layer Compose also rotates the
 * finger's coordinates, and the rotation starts feeding back on itself (rotating the element moves
 * the frame of reference, which reduces the measured angle, which reduces the rotation...); and the
 * node, stuck to the element, ran from under the finger while resizing. With it still the finger is
 * always in screen coordinates, and the counter-rotation is done by hand with [rotateVector] — which
 * is testable, unlike a transformation hidden in a layer.
 */
@Composable
internal fun ComposerSelectionOverlay(
    elementId: String,
    bounds: NormalizedRect,
    rotationDegrees: Float,
    locked: Boolean,
    /**
     * In framing mode the frame only marks the element: the handles leave and no gesture is installed,
     * because the finger belongs to the image underneath — it is the one sliding inside the mask. A
     * `fillMaxSize` box with `pointerInput` would keep catching everything first.
     */
    positioning: Boolean,
    pageWidthPx: Float,
    pageHeightPx: Float,
    onBeginGesture: () -> Unit,
    onEndGesture: () -> Unit,
    onResize: (ResizeCorner, Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onScale: (Float) -> Unit,
    onRotateBy: (Float) -> Unit,
    /** A clean tap inside the element. Whoever counts the two taps is the stage. */
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    val touchRadiusPx = with(density) { HandleTouchRadius.toPx() }
    val handleRadiusPx = with(density) { HandleRadius.toPx() }
    val rotationGapPx = with(density) { RotationHandleGap.toPx() }

    // Everything the gesture block reads that is not `mutableStateOf` has to go through here: the block
    // only restarts when `elementId` changes, and without this it stayed holding the values of the
    // composition it was born in. It is the same trap that already cost crop mode in the editor.
    val currentBounds by rememberUpdatedState(bounds)
    val currentRotation by rememberUpdatedState(rotationDegrees)
    val currentLocked by rememberUpdatedState(locked)
    val currentPageWidth by rememberUpdatedState(pageWidthPx)
    val currentPageHeight by rememberUpdatedState(pageHeightPx)
    val beginGesture by rememberUpdatedState(onBeginGesture)
    val endGesture by rememberUpdatedState(onEndGesture)
    val resize by rememberUpdatedState(onResize)
    // Not called `rotate`: it would collide with the `DrawScope.rotate` extension used in the Canvas below.
    val rotateTo by rememberUpdatedState(onRotate)
    val move by rememberUpdatedState(onMove)
    val scale by rememberUpdatedState(onScale)
    val rotateBy by rememberUpdatedState(onRotateBy)
    val tap by rememberUpdatedState(onTap)

    // The box covers the whole stage instead of following the element. That is what makes dragging
    // stable: a box stuck to the element moves under the finger while resizing, and `positionChange()`
    // — which is relative to the node — measured the difference between the finger and the box instead
    // of the finger's movement. On the top and left handles, which move the origin, that cancelled out
    // and the handle got stuck. With the node still, the movement is the finger's and nothing else.
    Box(
        Modifier
            .fillMaxSize()
            // In the middle of framing this box leaves the gestures entirely, and not just on the
            // inside. A `pointerInput` node hit by the touch does not share it with the siblings it
            // covers — not even when its block does nothing with it —, and what is under this box is
            // precisely the photo one wants to drag. Keeping the mode **inside** the block left the
            // node standing, and with it the touch stuck here: the mode opened and the finger moved
            // nothing.
            .then(
                if (positioning) {
                    Modifier
                } else {
                    Modifier.pointerInput(elementId) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val handle = handleAt(
                                position = down.position,
                                centre = stageCentre(currentBounds, currentPageWidth, currentPageHeight),
                                size = stageSize(currentBounds, currentPageWidth, currentPageHeight),
                                rotationDegrees = currentRotation,
                                rotationGapPx = rotationGapPx,
                                touchRadiusPx = touchRadiusPx,
                            )
                            if (currentLocked) return@awaitEachGesture

                            val insideElement = isInsideElement(
                                position = down.position,
                                centre = stageCentre(currentBounds, currentPageWidth, currentPageHeight),
                                size = stageSize(currentBounds, currentPageWidth, currentPageHeight),
                                rotationDegrees = currentRotation,
                            )

                            if (handle != null) {
                                down.consume()
                                beginGesture()
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        if (event.changes.count { it.pressed } > 1) break
                                        when (handle) {
                                            SelectionHandle.ROTATE -> {
                                                val centre = stageCentre(currentBounds, currentPageWidth, currentPageHeight)
                                                val vector = change.position - centre
                                                // The handle is at -90° from the centre when the rotation is zero.
                                                val degrees = Math.toDegrees(atan2(vector.y, vector.x).toDouble()).toFloat() + 90f
                                                rotateTo(degrees)
                                            }
                                            else -> {
                                                val delta = change.positionChange()
                                                val (localX, localY) = rotateVector(delta.x, delta.y, -currentRotation)
                                                resize(
                                                    checkNotNull(handle.corner),
                                                    localX / currentPageWidth,
                                                    localY / currentPageHeight,
                                                )
                                            }
                                        }
                                        change.consume()
                                    }
                                } finally {
                                    endGesture()
                                }
                                return@awaitEachGesture
                            }

                            // Outside a handle but inside the element: this box covers the whole stage
                            // and sits on top of everything, precisely so the handles do not lose the
                            // drag — and it is that same design that keeps the touch from ever reaching
                            // the element underneath. Contrary to what this comment used to say, Compose
                            // does not let an unconsumed touch fall to a covered SIBLING; it only lets it
                            // go up to an ANCESTOR. With nobody handling move and pinch here, the touch kept
                            // going up to the stage's background — which has its own `pointerInput` — and
                            // selected it.
                            //
                            // `insideElement` matters for a second reason: this box covers the WHOLE stage,
                            // not just the selected element. A touch outside the element — even over ANOTHER
                            // element, not yet selected — also goes through here, and without this filter it
                            // would be treated as "move the current element" instead of letting that other
                            // element be selected. Outside the element the box still returns without
                            // consuming, as before — that it switches selection correctly is an existing
                            // limitation, not something this fix makes worse.
                            if (!insideElement) return@awaitEachGesture

                            // Move and pinch then live here, with the same maths as
                            // `composerElementGestures`, minus the counter-rotation: that function runs
                            // inside the element's rotated layer and has to undo the angle; this box is not
                            // rotated, the finger's movement already arrives in stage space.
                            down.consume()
                            beginGesture()
                            try {
                                var pinching = false
                                // An already selected element is covered by this box, so it is here — and
                                // not in `composerElementGestures` — that the taps on it now land. Without
                                // this, the second tap of a double tap was never counted.
                                var travelled = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        pinching = true
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        if (centroid != Offset.Unspecified) {
                                            // This frame's ratio, of the order of 1.005 — it accumulates,
                                            // never compared with a threshold.
                                            scale(event.calculateZoom())
                                            rotateBy(event.calculateRotation())
                                            val pan = event.calculatePan()
                                            move(pan.x / currentPageWidth, pan.y / currentPageHeight)
                                        }
                                    } else if (!pinching && pressed.size == 1) {
                                        val delta = pressed.first().positionChange()
                                        travelled += abs(delta.x) + abs(delta.y)
                                        move(delta.x / currentPageWidth, delta.y / currentPageHeight)
                                    }
                                    event.changes.forEach(PointerInputChange::consume)
                                } while (event.changes.any { it.pressed })
                                if (!pinching && travelled <= viewConfiguration.touchSlop) tap()
                            } finally {
                                endGesture()
                            }
                        }
                    }
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val elementSize = Size(bounds.width * pageWidthPx, bounds.height * pageHeightPx)
            val topLeft = Offset(bounds.x * pageWidthPx, bounds.y * pageHeightPx)
            val centre = Offset(topLeft.x + elementSize.width / 2f, topLeft.y + elementSize.height / 2f)
            // The rotation is applied to the drawing, not to the box, so the gestures' node stays still.
            rotate(degrees = rotationDegrees, pivot = centre) {
                drawRect(ImagoColors.Gold, topLeft, elementSize, style = Stroke(width = 2.dp.toPx()))
                // While framing, the frame only says where it is: resize and rotate handles would be
                // targets that do not respond, because no gesture is installed in this mode.
                if (locked || positioning) return@rotate

                val rotationHandle = Offset(centre.x, topLeft.y - rotationGapPx)
                drawLine(ImagoColors.Gold, Offset(centre.x, topLeft.y), rotationHandle, strokeWidth = 2.dp.toPx())
                listOf(
                    topLeft,
                    Offset(topLeft.x + elementSize.width, topLeft.y),
                    Offset(topLeft.x, topLeft.y + elementSize.height),
                    Offset(topLeft.x + elementSize.width, topLeft.y + elementSize.height),
                    rotationHandle,
                ).forEach { handle ->
                    drawCircle(Color.Black.copy(alpha = .45f), handleRadiusPx + 1.dp.toPx(), handle)
                    drawCircle(ImagoColors.BrandWhite, handleRadiusPx, handle)
                    drawCircle(ImagoColors.Gold, handleRadiusPx, handle, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

/** The element's centre in stage pixels. */
private fun stageCentre(bounds: NormalizedRect, pageWidthPx: Float, pageHeightPx: Float) = Offset(
    (bounds.x + bounds.width / 2f) * pageWidthPx,
    (bounds.y + bounds.height / 2f) * pageHeightPx,
)

private fun stageSize(bounds: NormalizedRect, pageWidthPx: Float, pageHeightPx: Float) =
    Size(bounds.width * pageWidthPx, bounds.height * pageHeightPx)

/**
 * True when the touch falls inside the element's rectangle — already counting the rotation.
 *
 * The same calculation as [handleAt], reversed: instead of comparing to nominal handles already
 * rotated to the screen, it undoes the touch's rotation into the element's frame of reference and
 * compares there, where the rectangle is always aligned with the axes.
 */
private fun isInsideElement(position: Offset, centre: Offset, size: Size, rotationDegrees: Float): Boolean {
    val local = position - centre
    val (unrotatedX, unrotatedY) = rotateVector(local.x, local.y, -rotationDegrees)
    return abs(unrotatedX) <= size.width / 2f && abs(unrotatedY) <= size.height / 2f
}

/**
 * Which of the handles was touched, in screen coordinates.
 *
 * The handles live in the element's frame of reference, so the nominal positions are rotated to the
 * screen before measuring the distance to the finger — the inverse of the calculation resizing does.
 */
private fun handleAt(
    position: Offset,
    centre: Offset,
    size: Size,
    rotationDegrees: Float,
    rotationGapPx: Float,
    touchRadiusPx: Float,
): SelectionHandle? {
    val halfWidth = size.width / 2f
    val halfHeight = size.height / 2f
    val candidates = listOf(
        SelectionHandle.TOP_LEFT to Offset(-halfWidth, -halfHeight),
        SelectionHandle.TOP_RIGHT to Offset(halfWidth, -halfHeight),
        SelectionHandle.BOTTOM_LEFT to Offset(-halfWidth, halfHeight),
        SelectionHandle.BOTTOM_RIGHT to Offset(halfWidth, halfHeight),
        SelectionHandle.ROTATE to Offset(0f, -halfHeight - rotationGapPx),
    )
    return candidates.map { (handle, local) ->
        val (screenX, screenY) = rotateVector(local.x, local.y, rotationDegrees)
        handle to (position - Offset(centre.x + screenX, centre.y + screenY)).getDistance()
    }.filter { it.second <= touchRadiusPx }.minByOrNull { it.second }?.first
}

/**
 * The element's own gesture: one finger moves, two fingers scale and rotate.
 *
 * This block lives **inside** the graphics layer that rotates the element, so Compose delivers the
 * movement already in its frame of reference — which is why move converts it back to the screen with
 * [rotateVector]. That conversion was what was missing: the local value went straight to the model and
 * a tilted element ran off along its own diagonal instead of following the finger.
 *
 * `elementId` is the **only** key. `locked` used to be there, and locking the element mid-drag killed
 * the gesture in progress, because the new block's `awaitFirstDown` only resolves with a new touch.
 */
internal fun Modifier.composerElementGestures(
    elementId: String,
    locked: () -> Boolean,
    rotationDegrees: () -> Float,
    /** The flip is also on the layer, so the local movement comes inverted on that axis. */
    mirrorHorizontal: () -> Boolean,
    mirrorVertical: () -> Boolean,
    pageWidthPx: () -> Float,
    pageHeightPx: () -> Float,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    /** A short tap that neither moved nor stretched: the cheapest gesture this block can tell apart. */
    onTap: () -> Unit,
    onBeginGesture: () -> Unit,
    onEndGesture: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onScale: (Float) -> Unit,
    onRotate: (Float) -> Unit,
): Modifier = pointerInput(elementId) {
    awaitEachGesture {
        // `requireUnconsumed = true` is what keeps the element from following a handle: the frame is on
        // top, sees the touch first and consumes it when it lands on a handle. Without this, the two
        // gestures ran at the same time and resizing dragged the element too.
        val down = awaitFirstDown(requireUnconsumed = true)
        onSelect()
        if (locked()) return@awaitEachGesture
        down.consume()
        // The context menu is decided at the end, not halfway.
        //
        // An `awaitLongPressOrCancellation` would have to run **before** the drag loop and would compete
        // with it for the same `down` — and this canvas has already had two gesture arbitration bugs.
        // This way the drag is never interrupted: how far the finger moved and how long it stayed is
        // measured, and only when it lifts is it known whether that was a long press or a drag.
        val pressedAt = System.currentTimeMillis()
        var travelled = 0f
        onBeginGesture()
        try {
            var pinching = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                // The layer applies flip and then rotation; undoing goes in reverse order. Flipping a
                // single axis also inverts the direction the pinch rotates in.
                val flipX = if (mirrorHorizontal()) -1f else 1f
                val flipY = if (mirrorVertical()) -1f else 1f
                fun toScreen(dx: Float, dy: Float) = rotateVector(dx * flipX, dy * flipY, rotationDegrees())
                if (pressed.size >= 2) {
                    pinching = true
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid != Offset.Unspecified) {
                        // `calculateZoom` returns **this frame's** ratio, of the order of 1.005. It goes
                        // straight to the accumulated scale; comparing it with a threshold stuck the gesture.
                        onScale(event.calculateZoom())
                        onRotate(rotationDegrees() + event.calculateRotation() * flipX * flipY)
                        val pan = event.calculatePan()
                        val (panX, panY) = toScreen(pan.x, pan.y)
                        onMove(panX / pageWidthPx(), panY / pageHeightPx())
                    }
                } else if (!pinching && pressed.size == 1) {
                    val delta = pressed.first().positionChange()
                    travelled += abs(delta.x) + abs(delta.y)
                    val (screenX, screenY) = toScreen(delta.x, delta.y)
                    onMove(screenX / pageWidthPx(), screenY / pageHeightPx())
                }
                event.changes.forEach(PointerInputChange::consume)
            } while (event.changes.any { it.pressed })
            // The tap and the long press both come out of here, for the same reason the context menu
            // is decided at the end: a separate `detectTapGestures` would compete again for the `down`
            // with the drag loop. Whoever neither moved nor stretched did one of the two, and the clock
            // says which.
            if (!pinching && travelled <= viewConfiguration.touchSlop) {
                if (System.currentTimeMillis() - pressedAt >= LONG_PRESS_MS) onLongPress() else onTap()
            }
        } finally {
            onEndGesture()
        }
    }
}

/** How long a finger resting on an element is worth a menu. The same threshold as the system. */
private const val LONG_PRESS_MS = 500L
