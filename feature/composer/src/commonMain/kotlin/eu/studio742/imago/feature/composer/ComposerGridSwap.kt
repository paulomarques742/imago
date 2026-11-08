package eu.studio742.imago.feature.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal data class GridSwapDrag(val source: String, val target: String?, val position: Offset, val overTrash: Boolean = false)

private val GridTrashSize = 64.dp
private val GridTrashInset = 24.dp

/** A finger hides what it drags, so the catch area is wider than the icon drawn under it. */
private fun Density.overGridTrash(point: Offset, stage: Size, inset: Dp): Boolean {
    val centre = Offset(stage.width / 2f, stage.height - (inset + GridTrashInset + GridTrashSize / 2).toPx())
    return (point - centre).getDistance() <= GridTrashSize.toPx()
}

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
    onFeedback: () -> Unit, onRemove: ((String) -> Unit)? = null, trashInset: Dp = 0.dp,
): Modifier {
    val current by rememberUpdatedState(layout)
    val project by rememberUpdatedState(preview)
    val currentOrigin by rememberUpdatedState(origin)
    val change by rememberUpdatedState(onDrag)
    val swap by rememberUpdatedState(onSwap)
    val feedback by rememberUpdatedState(onFeedback)
    val remove by rememberUpdatedState(onRemove)
    val inset by rememberUpdatedState(trashInset)
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
                    drag?.let { value ->
                        if (value.overTrash) remove?.invoke(value.source)
                        else value.target?.takeIf { it != value.source }?.let { swap(value.source, it) }
                    }
                    drag = null; change(null)
                },
                onDrag = { pointer, _ ->
                    val trash = remove != null && overGridTrash(pointer.position, size.toSize(), inset)
                    if (trash && drag?.overTrash == false) feedback()
                    drag = drag?.copy(position = pointer.position, target = if (trash) null else cell(pointer.position), overTrash = trash)
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
    onRemove: ((String) -> Unit)? = null, trashInset: Dp = 0.dp, selected: String? = null, onSelect: ((String) -> Unit)? = null,
) {
    val colors = ImagoUi.colors
    val density = LocalDensity.current
    val cells = layout.geometry(preview.format.aspectRatio).cells
    val members = preview.elements.filter { it.id in cells && it.visible && !it.locked }
    val labels = members.associate { it.id to it.accessibilityLabel() }
    val removeLabel = stringResource(Res.string.composer_grid_remove_element)
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
        } + listOfNotNull(onRemove?.let { remove -> CustomAccessibilityAction(removeLabel) { remove(member.id); true } })
        Box(Modifier.offset { IntOffset((at.x - size.width / 2f).roundToInt(), (at.y - size.height / 2f).roundToInt()) }
            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
            .testTag("grid-cell-${member.id}").semantics {
                contentDescription = labels.getValue(member.id)
                customActions = actions
                onSelect?.let { select -> onClick { select(member.id); true } }
            })
    }
    if (drag == null) {
        val cell = cells[selected] ?: return
        Canvas(Modifier.fillMaxSize().testTag("grid-selected-cell")) {
            rotate(layout.frame.rotationDegrees, pivot) {
                drawRect(colors.accent, origin + Offset((b.x + cell.x * b.width) * width, (b.y + cell.y * b.height) * height),
                    Size(cell.width * b.width * width, cell.height * b.height * height), style = Stroke(2.dp.toPx()))
            }
        }
        return
    }
    Canvas(Modifier.fillMaxSize().testTag("grid-swap-feedback")) {
        rotate(layout.frame.rotationDegrees, pivot) {
            listOfNotNull(drag.source, drag.target).distinct().forEach { id ->
                cells[id]?.let { cell ->
                    val at = origin + Offset((b.x + cell.x * b.width) * width, (b.y + cell.y * b.height) * height)
                    val size = Size(cell.width * b.width * width, cell.height * b.height * height)
                    val tint = if (drag.overTrash) colors.danger else colors.accent
                    drawRect(tint.copy(alpha = .18f), at, size)
                    drawRect(tint, at, size, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
    members.firstOrNull { it.id == drag.source }?.let { source ->
        val cell = cells.getValue(source.id)
        val ghostWidth = with(density) { (cell.width * b.width * width).toDp() }
        val ghostHeight = with(density) { (cell.height * b.height * height).toDp() }
        val ghost = remember(preview, source.id) {
            val element = source.withGroupId(null).withTransform(source.transform.copy(bounds = NormalizedRect(0f, 0f, 1f, 1f), rotationDegrees = 0f))
            preview.copy(elements = listOf(if (element is CompositionElement.Video) element.copy(pageIndex = 0) else element),
                layouts = emptyList(), pages = listOf(preview.pages.first()), background = CompositionBackground.Solid(0x00000000))
        }
        Box(Modifier.offset { IntOffset((drag.position.x - cell.width * b.width * width / 2f).roundToInt(),
            (drag.position.y - cell.height * b.height * height / 2f).roundToInt()) }.size(ghostWidth, ghostHeight)
            .scale(if (drag.overTrash) .4f else 1f).alpha(.8f)) {
            CompositionPageCanvas(ghost, ghost.pages.first().copy(backgroundOverride = null), ghostWidth, ghostHeight, thumbnailUrl, apiKey)
        }
    }
    if (onRemove != null) GridTrashTarget(drag.overTrash, removeLabel, trashInset)
}

/** Drawn over the dragged image, so the bin stays readable while the element is above it. */
@Composable
private fun GridTrashTarget(active: Boolean, label: String, inset: Dp) {
    val colors = ImagoUi.colors
    val scale by animateFloatAsState(if (active) 1.2f else 1f)
    Box(Modifier.fillMaxSize().padding(bottom = inset + GridTrashInset), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.size(GridTrashSize).scale(scale).clip(CircleShape).background(if (active) colors.dangerFill else colors.elevated)
            .border(1.dp, colors.border, CircleShape).testTag("grid-trash"), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Delete, label, tint = if (active) colors.onDanger else colors.danger)
        }
    }
}
