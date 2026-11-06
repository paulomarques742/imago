package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
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
import eu.studio742.imago.core.composition.snapRotation
import eu.studio742.imago.core.designsystem.ImagoUi

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
    LEFT(null), RIGHT(null),
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
    origin: Offset = Offset.Zero,
    rotationDegrees: Float,
    locked: Boolean,
    /**
     * In framing mode the frame only marks the element: the handles leave and no gesture is installed,
     * because the finger belongs to the image underneath — it is the one sliding inside the mask. A
     * `fillMaxSize` box with `pointerInput` would keep catching everything first.
     */
    positioning: Boolean,
    textWidthHandles: Boolean = false,
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
    val uiColors = ImagoUi.colors
    val density = LocalDensity.current
    val touchRadiusPx = with(density) { HandleTouchRadius.toPx() }
    val handleRadiusPx = with(density) { HandleRadius.toPx() }
    val rotationGapPx = with(density) { RotationHandleGap.toPx() }

    // Everything the gesture block reads that is not `mutableStateOf` has to go through here: the block
    // only restarts when `elementId` changes, and without this it stayed holding the values of the
    // composition it was born in. It is the same trap that already cost crop mode in the editor.
    // Only the stage ancestor handles the pegs. A full-screen sibling input node would hide
    // every unselected element underneath it from hit testing.
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val elementSize = Size(bounds.width * pageWidthPx, bounds.height * pageHeightPx)
            val topLeft = origin + Offset(bounds.x * pageWidthPx, bounds.y * pageHeightPx)
            val centre = Offset(topLeft.x + elementSize.width / 2f, topLeft.y + elementSize.height / 2f)
            // The rotation is applied to the drawing, not to the box, so the gestures' node stays still.
            rotate(degrees = rotationDegrees, pivot = centre) {
                drawRect(uiColors.accent, topLeft, elementSize, style = Stroke(width = 2.dp.toPx()))
                // While framing, the frame only says where it is: resize and rotate handles would be
                // targets that do not respond, because no gesture is installed in this mode.
                if (locked || positioning) return@rotate

                val rotationHandle = Offset(centre.x, topLeft.y - rotationGapPx)
                drawLine(uiColors.accent, Offset(centre.x, topLeft.y), rotationHandle, strokeWidth = 2.dp.toPx())
                (listOf(
                    topLeft,
                    Offset(topLeft.x + elementSize.width, topLeft.y),
                    Offset(topLeft.x, topLeft.y + elementSize.height),
                    Offset(topLeft.x + elementSize.width, topLeft.y + elementSize.height),
                    rotationHandle,
                ) + if (textWidthHandles) listOf(Offset(topLeft.x, centre.y), Offset(topLeft.x + elementSize.width, centre.y)) else emptyList()).forEach { handle ->
                    drawCircle(uiColors.workspace.copy(alpha = .45f), handleRadiusPx + 1.dp.toPx(), handle)
                    drawCircle(uiColors.text, handleRadiusPx, handle)
                    drawCircle(uiColors.accent, handleRadiusPx, handle, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

/** Handles run on the stage ancestor, so other elements remain available to hit testing. */
@Composable
internal fun Modifier.composerHandleGestures(
    state: ComposerEditorUiState,
    pageWidthPx: Float,
    pageHeightPx: Float,
    enabled: Boolean,
    actions: ComposerEditorViewModel,
    origin: Offset = Offset.Zero,
): Modifier {
    val liveState by rememberUpdatedState(state)
    val width by rememberUpdatedState(pageWidthPx)
    val height by rememberUpdatedState(pageHeightPx)
    val liveOrigin by rememberUpdatedState(origin)
    val touchRadius = with(LocalDensity.current) { HandleTouchRadius.toPx() }
    val rotationGap = with(LocalDensity.current) { RotationHandleGap.toPx() }
    val snapDistance = with(LocalDensity.current) { ComposerSnapDistance.toPx() }
    fun frame(): Pair<NormalizedRect, Float>? {
        val current = liveState
        val elements = current.editableSelection()
        if (elements.isEmpty() || elements.none { it.visible } || elements.any { it.locked }) return null
        current.manipulatedLayout()?.let { return it.frame.bounds to it.frame.rotationDegrees }
        if (elements.size == 1) return elements.single().let { it.transform.bounds to it.transform.rotationDegrees }
        val left = elements.minOf { it.transform.bounds.x }
        val top = elements.minOf { it.transform.bounds.y }
        return NormalizedRect(left, top, elements.maxOf { it.transform.bounds.right } - left,
            elements.maxOf { it.transform.bounds.bottom } - top) to 0f
    }
    return pointerInput(state.project?.id, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
            val initial = frame() ?: return@awaitEachGesture
            val handle = handleAt(down.position - liveOrigin, stageCentre(initial.first, width, height),
                stageSize(initial.first, width, height), initial.second, rotationGap, touchRadius,
                liveState.editableSelection().singleOrNull() is eu.studio742.imago.core.composition.CompositionElement.Text && liveState.manipulatedLayout() == null)
                ?: return@awaitEachGesture
            down.consume()
            if (handle.corner == null) actions.beginGesture() else actions.beginResizeGesture(handle.corner)
            val pivot = stageCentre(initial.first, width, height)
            fun angle(position: Offset): Float = Math.toDegrees(atan2((position - liveOrigin - pivot).y,
                (position - liveOrigin - pivot).x).toDouble()).toFloat()
            var previousAngle = angle(down.position)
            var requestedAngle = initial.second
            var appliedAngle = initial.second
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.isConsumed } || event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val current = frame() ?: break
                    if (handle == SelectionHandle.ROTATE) {
                        val nextAngle = angle(change.position)
                        requestedAngle += ((nextAngle - previousAngle + 540f) % 360f) - 180f
                        val target = snapRotation(requestedAngle)
                        actions.rotateSelectedBy(((target - appliedAngle + 540f) % 360f) - 180f)
                        previousAngle = nextAngle
                        appliedAngle = target
                    } else if (handle == SelectionHandle.LEFT || handle == SelectionHandle.RIGHT) {
                        val delta = change.positionChange()
                        val (x, _) = rotateVector(delta.x, delta.y, -current.second)
                        actions.resizeTextWidth(x / width, fromLeft = handle == SelectionHandle.LEFT)
                    } else {
                        val delta = change.positionChange()
                        val (x, y) = rotateVector(delta.x, delta.y, -current.second)
                        actions.resizeSelectedWithGuides(x / width, y / height, snapDistance / width, snapDistance / height)
                    }
                    event.changes.forEach(PointerInputChange::consume)
                }
            } finally { actions.endGesture() }
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
    textWidthHandles: Boolean = false,
): SelectionHandle? {
    val halfWidth = size.width / 2f
    val halfHeight = size.height / 2f
    val candidates = listOf(
        SelectionHandle.TOP_LEFT to Offset(-halfWidth, -halfHeight),
        SelectionHandle.TOP_RIGHT to Offset(halfWidth, -halfHeight),
        SelectionHandle.BOTTOM_LEFT to Offset(-halfWidth, halfHeight),
        SelectionHandle.BOTTOM_RIGHT to Offset(halfWidth, halfHeight),
        SelectionHandle.ROTATE to Offset(0f, -halfHeight - rotationGapPx),
    ) + if (textWidthHandles) listOf(SelectionHandle.LEFT to Offset(-halfWidth, 0f), SelectionHandle.RIGHT to Offset(halfWidth, 0f)) else emptyList()
    return candidates.map { (handle, local) ->
        val (screenX, screenY) = rotateVector(local.x, local.y, rotationDegrees)
        handle to (position - Offset(centre.x + screenX, centre.y + screenY)).getDistance()
    }.filter { it.second <= touchRadiusPx }.minByOrNull { it.second }?.first
}

/**
 * One finger moves the element; the stage owns two-finger navigation.
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
): Modifier = composed {
    val currentLocked by rememberUpdatedState(locked)
    val currentRotationDegrees by rememberUpdatedState(rotationDegrees)
    val currentMirrorHorizontal by rememberUpdatedState(mirrorHorizontal)
    val currentMirrorVertical by rememberUpdatedState(mirrorVertical)
    val currentPageWidthPx by rememberUpdatedState(pageWidthPx)
    val currentPageHeightPx by rememberUpdatedState(pageHeightPx)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnBeginGesture by rememberUpdatedState(onBeginGesture)
    val currentOnEndGesture by rememberUpdatedState(onEndGesture)
    val currentOnMove by rememberUpdatedState(onMove)
    Modifier.pointerInput(elementId) {
    awaitEachGesture {
        // `requireUnconsumed = true` is what keeps the element from following a handle: the frame is on
        // top, sees the touch first and consumes it when it lands on a handle. Without this, the two
        // gestures ran at the same time and resizing dragged the element too.
        val down = awaitFirstDown(requireUnconsumed = true)
        if (currentLocked()) return@awaitEachGesture
        down.consume()
        val pressedAt = down.uptimeMillis
        val startedAt = System.currentTimeMillis()
        var longPressed = false
        var releasedAt = pressedAt
        var travelled = Offset.Zero
        var dragging = false
        var cancelled = false
        try {
            do {
                val pending = if (!dragging && !longPressed) {
                    withTimeoutOrNull((LONG_PRESS_MS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1)) {
                        awaitPointerEvent()
                    }
                } else awaitPointerEvent()
                if (pending == null) {
                    currentOnLongPress()
                    longPressed = true
                }
                val event = pending ?: awaitPointerEvent()
                if (event.changes.any { it.isConsumed } || event.changes.count { it.pressed } > 1) {
                    cancelled = true
                    break
                }
                releasedAt = event.changes.maxOf { it.uptimeMillis }
                val pressed = event.changes.filter { it.pressed }
                val flipX = if (currentMirrorHorizontal()) -1f else 1f
                val flipY = if (currentMirrorVertical()) -1f else 1f
                fun toScreen(delta: Offset): Pair<Float, Float> = rotateVector(delta.x * flipX, delta.y * flipY, currentRotationDegrees())
                if (pressed.size == 1) {
                    val delta = pressed.first().positionChange()
                    travelled += delta
                    if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) {
                        currentOnSelect(); currentOnBeginGesture(); dragging = true
                        val (x, y) = toScreen(travelled)
                        currentOnMove(x / currentPageWidthPx(), y / currentPageHeightPx())
                    } else if (dragging) {
                        val (x, y) = toScreen(delta)
                        currentOnMove(x / currentPageWidthPx(), y / currentPageHeightPx())
                    }
                }
                event.changes.forEach(PointerInputChange::consume)
            } while (event.changes.any { it.pressed })
            if (!cancelled && !dragging && !longPressed) {
                if (releasedAt - pressedAt >= LONG_PRESS_MS) currentOnLongPress() else currentOnTap()
            }
        } finally {
            if (dragging) currentOnEndGesture()
        }
    }
}

}

/** How long a finger resting on an element is worth a menu. The same threshold as the system. */
private const val LONG_PRESS_MS = 500L
