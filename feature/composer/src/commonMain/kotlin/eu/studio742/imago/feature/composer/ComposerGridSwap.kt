package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal data class GridSwapDrag(val source: String, val target: String?, val position: Offset)

internal fun CompositionLayout.cellAt(point: Offset, pageAspect: Float, origin: Offset, width: Float, height: Float): String? {
    val b = frame.bounds
    val centre = origin + Offset((b.x + b.width / 2f) * width, (b.y + b.height / 2f) * height)
    val (x, y) = rotateVector(point.x - centre.x, point.y - centre.y, -frame.rotationDegrees)
    val local = Offset(.5f + x / (b.width * width), .5f + y / (b.height * height))
    return geometry(pageAspect).cells.entries.firstOrNull { (_, cell) ->
        local.x >= cell.x && local.x <= cell.right && local.y >= cell.y && local.y <= cell.bottom
    }?.key
}

@Composable
internal fun Modifier.composerGridSwapGestures(
    preview: CompositionProject, layout: CompositionLayout, origin: Offset, width: Float, height: Float,
    onDrag: (GridSwapDrag?) -> Unit, onSwap: (String, String) -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val current by rememberUpdatedState(layout)
    val project by rememberUpdatedState(preview)
    val currentOrigin by rememberUpdatedState(origin)
    val change by rememberUpdatedState(onDrag)
    val swap by rememberUpdatedState(onSwap)
    val feedback by rememberUpdatedState(onLongPress)
    return pointerInput(layout.groupId, width, height) {
        var drag: GridSwapDrag? = null
        fun cell(point: Offset): String? = current.cellAt(point, project.format.aspectRatio, currentOrigin, width, height)
            ?.takeIf { id -> project.elements.any { it.id == id && it.visible && !it.locked } }
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    drag = cell(point)?.let { GridSwapDrag(it, null, point) }
                    if (drag != null) feedback()
                    change(drag)
                },
                onDragCancel = { drag = null; change(null) },
                onDragEnd = {
                    drag?.let { value -> value.target?.takeIf { it != value.source }?.let { swap(value.source, it) } }
                    drag = null; change(null)
                },
                onDrag = { pointer, _ ->
                    drag = drag?.copy(position = pointer.position, target = cell(pointer.position))
                    change(drag)
                    if (drag != null) pointer.consume()
                },
            )
        } finally { change(null) }
    }
}

/** Visual and accessibility nodes have no pointer filter: dividers keep their own hit targets. */
@Composable
internal fun ComposerGridSwapOverlay(
    preview: CompositionProject, layout: CompositionLayout, origin: Offset, width: Float, height: Float,
    drag: GridSwapDrag?, thumbnailUrl: (String) -> String, apiKey: (String) -> String, onSwap: (String, String) -> Unit,
) {
    val colors = ImagoUi.colors
    val density = LocalDensity.current
    val cells = layout.geometry(preview.format.aspectRatio).cells
    val members = preview.elements.filter { it.id in cells && it.visible && !it.locked }
    val labels = members.associate { it.id to it.accessibilityLabel() }
    val b = layout.frame.bounds
    val pivot = origin + Offset((b.x + b.width / 2f) * width, (b.y + b.height / 2f) * height)
    fun centre(cell: NormalizedRect): Offset {
        val (x, y) = rotateVector((cell.x + cell.width / 2f - .5f) * b.width * width,
            (cell.y + cell.height / 2f - .5f) * b.height * height, layout.frame.rotationDegrees)
        return pivot + Offset(x, y)
    }
    members.forEach { member ->
        val cell = cells.getValue(member.id)
        val at = centre(cell)
        val size = Size(cell.width * b.width * width, cell.height * b.height * height)
        val actions = members.filterNot { it.id == member.id }.map { other ->
            val label = stringResource(Res.string.composer_grid_swap_with, labels.getValue(other.id))
            CustomAccessibilityAction(label) { onSwap(member.id, other.id); true }
        }
        Box(Modifier.offset { IntOffset((at.x - size.width / 2f).roundToInt(), (at.y - size.height / 2f).roundToInt()) }
            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
            .testTag("grid-cell-${member.id}").semantics {
                contentDescription = labels.getValue(member.id)
                customActions = actions
            })
    }
    if (drag == null) return
    Canvas(Modifier.fillMaxSize().testTag("grid-swap-feedback")) {
        rotate(layout.frame.rotationDegrees, pivot) {
            listOfNotNull(drag.source, drag.target).distinct().forEach { id ->
                cells[id]?.let { cell ->
                    val at = origin + Offset((b.x + cell.x * b.width) * width, (b.y + cell.y * b.height) * height)
                    val size = Size(cell.width * b.width * width, cell.height * b.height * height)
                    drawRect(colors.accent.copy(alpha = .18f), at, size)
                    drawRect(colors.accent, at, size, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
    val source = members.firstOrNull { it.id == drag.source } ?: return
    val cell = cells.getValue(source.id)
    val ghostWidth = with(density) { (cell.width * b.width * width).toDp() }
    val ghostHeight = with(density) { (cell.height * b.height * height).toDp() }
    val ghost = remember(preview, source.id) {
        val element = source.withGroupId(null).withTransform(source.transform.copy(bounds = NormalizedRect(0f, 0f, 1f, 1f), rotationDegrees = 0f))
        preview.copy(elements = listOf(if (element is CompositionElement.Video) element.copy(pageIndex = 0) else element),
            layouts = emptyList(), pages = listOf(preview.pages.first()), background = CompositionBackground.Solid(0x00000000))
    }
    Box(Modifier.offset { IntOffset((drag.position.x - cell.width * b.width * width / 2f).roundToInt(),
        (drag.position.y - cell.height * b.height * height / 2f).roundToInt()) }.size(ghostWidth, ghostHeight).alpha(.8f)) {
        CompositionPageCanvas(ghost, ghost.pages.first().copy(backgroundOverride = null), ghostWidth, ghostHeight, thumbnailUrl, apiKey)
    }
}
