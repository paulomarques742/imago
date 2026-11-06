@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoSlider
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal fun CompositionElement.Shape.isClosedShape() = kind == ShapeKind.RECTANGLE || kind == ShapeKind.ELLIPSE
internal fun CompositionElement.hasOutlineCorners() = when (this) {
    is CompositionElement.Photo -> frame.cutout == MediaCutout.RECTANGLE
    is CompositionElement.MediaPlaceholder -> frame.cutout == MediaCutout.RECTANGLE
    is CompositionElement.Video -> supportsVideoFrameOutlines && frame.cutout == MediaCutout.RECTANGLE
    is CompositionElement.Shape -> kind == ShapeKind.RECTANGLE
    else -> false
}

internal fun CompositionElement.outlineStyle(): FrameStyle? = when (this) {
    is CompositionElement.Shape -> FrameStyle(cornerRadius = cornerRadius, borderWidth = strokeWidth, borderArgb = strokeArgb)
    is CompositionElement.Photo -> frame
    is CompositionElement.MediaPlaceholder -> frame
    is CompositionElement.Video -> frame.takeIf { supportsVideoFrameOutlines }
    else -> null
}

/** The same selection and group-lock rules as the editing commands, also usable by render-only previews. */
internal fun ComposerEditorUiState.outlineSelection(): List<CompositionElement> {
    val gridMembers = project?.layouts?.flatMap { it.root.memberIds() }.orEmpty().toSet()
    return editableSelection().takeIf { it.isNotEmpty() && it.all { member -> member.outlineStyle() != null &&
        (member is CompositionElement.Shape || member.id !in gridMembers) } }.orEmpty()
}

@Composable
internal fun ComposerOutlineBubble(
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onSize: (IntSize) -> Unit,
    onDrag: (Offset) -> Unit,
    onClose: () -> Unit,
) {
    val elements = state.outlineSelection().ifEmpty { return }
    val selected = elements.firstOrNull { it.id == state.selectedElementId } ?: elements.first()
    val style = selected.outlineStyle() ?: return
    val colors = ImagoUi.colors
    var pickingFill by remember(selected.id) { mutableStateOf<Boolean?>(null) }
    val shape = selected as? CompositionElement.Shape
    val fillAvailable = elements.all { it is CompositionElement.Shape && it.isClosedShape() }
    val noFill = fillAvailable && elements.all { ((it as CompositionElement.Shape).fillArgb ushr 24 and 255) == 0L }
    // Keep the dialog owner composed while sampling; only the bubble leaves the stage.
    pickingFill?.let { forFill ->
        val fillColor = shape?.fillArgb ?: 0L
        val initial = if (forFill && (fillColor ushr 24 and 255) == 0L) fillColor.withColorAlpha(1f)
            else if (forFill) fillColor else style.borderArgb
        ColorPickerDialog(
            title = stringResource(if (forFill) Res.string.composer_shape_fill else Res.string.composer_outline_color),
            initialArgb = initial,
            palette = state.brandKit?.paletteArgb.orEmpty(),
            compositionColors = state.project?.usedColors().orEmpty(),
            onDismiss = { pickingFill = null },
            onConfirm = { if (forFill) actions.setShapeFill(it) else actions.setOutlineColor(it); pickingFill = null },
        )
    }
    BackHandler(enabled = visible && pickingFill == null, onBack = onClose)
    if (!visible) return
    ComposerModeBubble(modifier.testTag("composer-outline-bubble"), onSize, onDrag) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.composer_outline), style = MaterialTheme.typography.titleSmall,
                color = colors.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
            ColorDot(style.borderArgb, stringResource(Res.string.composer_outline_color)) { pickingFill = false }
            IconButton(onClick = onClose, modifier = Modifier.testTag("outline-close")) {
                Icon(Icons.Outlined.Close, stringResource(Res.string.composer_close), tint = colors.secondaryText)
            }
        }
        if (fillAvailable && shape != null) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.composer_fill), style = MaterialTheme.typography.labelMedium, color = colors.text)
                ColorDot(shape.fillArgb, stringResource(Res.string.composer_shape_fill)) { pickingFill = true }
                TextButton(onClick = actions::setShapeNoFill,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (noFill) colors.accent else colors.secondaryText),
                    modifier = Modifier.testTag("outline-no-fill").semantics { this.selected = noFill }) {
                    Text(stringResource(Res.string.composer_no_fill))
                }
            }
        }
        val minimum = if (elements.any { it is CompositionElement.Shape && !it.isClosedShape() }) MIN_LINE_STROKE else 0f
        OutlineSlider(stringResource(Res.string.composer_thickness), style.borderWidth, minimum..MAX_SHAPE_STROKE,
            if (minimum > 0f) 4f else 0f, "outline-width", actions, onChange = actions::setOutlineWidth)
        OutlineSlider(stringResource(Res.string.composer_outline_opacity), ((style.borderArgb ushr 24) and 255) / 255f,
            0f..1f, 1f, "outline-opacity", actions, percent = true, onChange = actions::setOutlineOpacity)
        if (elements.all { it.hasOutlineCorners() }) {
            OutlineSlider(stringResource(Res.string.composer_corners), style.cornerRadius, 0f..MAX_FRAME_CORNER,
                0f, "outline-corners", actions, percent = true, onChange = actions::setOutlineCorner)
        }
    }
}

@Composable
private fun OutlineSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, neutral: Float, tag: String,
    actions: ComposerEditorViewModel, percent: Boolean = false, onChange: (Float) -> Unit,
) {
    val colors = ImagoUi.colors
    var editing by remember(label) { mutableStateOf(false) }
    val finish = { if (editing) { editing = false; actions.endGesture(stage = false) } }
    DisposableEffect(actions, label) { onDispose { finish() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        ImagoSlider(value.coerceIn(range), {
            if (!editing) { actions.beginGesture(stage = false); editing = true }
            onChange(it)
        }, range, neutral, onValueChangeFinished = finish,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp).testTag(tag).semantics { contentDescription = label })
        Text(if (percent) stringResource(Res.string.composer_percent_value, (value * 100).roundToInt()) else value.formatOne(),
            color = colors.secondaryText, style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End, modifier = Modifier.width(42.dp))
    }
}
