package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.UUID
import kotlin.math.roundToInt

internal fun ComposerEditorUiState.frameDesignSelection() = editableSelection().takeIf { members ->
    members.isNotEmpty() && members.all { it.mediaFrame() != null && (it !is CompositionElement.Video || supportsVideoFrameOutlines) }
}.orEmpty()

@Composable
internal fun ComposerFramesDialog(state: ComposerEditorUiState, actions: ComposerEditorViewModel, onDismiss: () -> Unit) {
    val members = state.frameDesignSelection().ifEmpty { return }
    val primary = members.firstOrNull { it.id == state.selectedElementId } ?: members.first()
    val initial = primary.mediaFrame()!!
    var cutout by remember(primary.id) { mutableStateOf(initial.cutout) }
    var decoration by remember(primary.id) { mutableStateOf(initial.decoration) }
    var pickingColor by remember { mutableStateOf(false) }
    val colors = ImagoUi.colors; val window = LocalImagoWindow.current
    val ids = members.map { it.id }.toSet()
    val project = checkNotNull(state.project)
    val preview = remember(project, ids, cutout, decoration) { project.copy(elements = project.elements.map { element ->
        if (element.id !in ids) element else element.withMediaFrame(element.mediaFrame()!!.copy(cutout = cutout, decoration = decoration))
    }) }
    if (pickingColor) CompositionLocalProvider(LocalEyedropper provides null) {
        ColorPickerDialog(stringResource(Res.string.composer_frame_paper_color), decoration.colorArgb, state.brandKit?.paletteArgb.orEmpty(),
            onDismiss = { pickingColor = false }, onConfirm = { decoration = decoration.copy(colorArgb = it); pickingColor = false }, compositionColors = preview.usedColors())
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = minOf(600.dp, window.width - 32.dp)).fillMaxWidth()
            .heightIn(max = window.height - 32.dp).fillMaxHeight(.92f).testTag("composer-frames-dialog"),
            shape = MaterialTheme.shapes.large, color = colors.surface, contentColor = colors.text) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(Res.string.composer_frame_library), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompositionPagePreview(preview, preview.pages[state.currentPage], actions::thumbnailUrl, actions::apiKey,
                        Modifier.fillMaxWidth().height(180.dp).testTag("frames-preview"))
                    Text(stringResource(Res.string.composer_frame_cutouts), style = MaterialTheme.typography.titleSmall, color = colors.text)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaCutout.entries.forEach { choice ->
                            FrameChoice(stringResource(choice.label()), choice == cutout, "frame-cutout-${choice.name}", onClick = { cutout = choice }) {
                                val frame = initial.copy(cutout = choice, decoration = decoration)
                                clipPath(mediaCutoutPath(frame, size)) { drawRect(if (choice == cutout) colors.accent else colors.secondaryText) }
                            }
                        }
                    }
                    Text(stringResource(Res.string.composer_frame_decorations), style = MaterialTheme.typography.titleSmall, color = colors.text)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaFrameKind.entries.forEach { choice ->
                            FrameChoice(stringResource(choice.label()), choice == decoration.kind, "frame-decoration-${choice.name}", onClick = { decoration = decoration.copy(kind = choice) }) {
                                inset(8f, 8f) {
                                    val frame = initial.copy(shadowRadius = 0f, borderWidth = 0f, decoration = decoration.copy(kind = choice, width = 80f))
                                    drawCompositionMediaBackground(frame, size.width / REFERENCE_PAGE_WIDTH)
                                    clipPath(mediaCutoutPath(frame, size)) { drawRect(colors.secondaryText) }
                                    drawCompositionFrame(frame, size.width / REFERENCE_PAGE_WIDTH)
                                }
                            }
                        }
                    }
                    if (decoration.kind != MediaFrameKind.NONE) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.composer_frame_paper_color), color = colors.text, modifier = Modifier.weight(1f))
                            ColorDot(decoration.colorArgb, stringResource(Res.string.composer_frame_paper_color)) { pickingColor = true }
                        }
                        FrameDesignSlider(stringResource(Res.string.composer_thickness), decoration.width, 0f..120f, "frame-width", percent = false) { decoration = decoration.copy(width = it) }
                        FrameDesignSlider(stringResource(Res.string.composer_opacity), decoration.opacity, 0f..1f, "frame-opacity") { decoration = decoration.copy(opacity = it) }
                    }
                    if (decoration.kind == MediaFrameKind.POLAROID) FrameDesignSlider(stringResource(Res.string.composer_frame_bottom_margin),
                        decoration.bottomWeight, 0f..4f, "frame-bottom", percent = false, ratio = true) { decoration = decoration.copy(bottomWeight = it) }
                    if (cutout == MediaCutout.TORN_PAPER || decoration.kind == MediaFrameKind.TORN_PAPER) {
                        FrameDesignSlider(stringResource(Res.string.composer_frame_roughness), decoration.roughness, 0f..1f, "frame-roughness") { decoration = decoration.copy(roughness = it) }
                        TextButton(onClick = { decoration = decoration.copy(seed = UUID.randomUUID().hashCode()) }, modifier = Modifier.testTag("frame-new-edge")) {
                            Text(stringResource(Res.string.composer_frame_new_edge))
                        }
                    }
                    TextButton(onClick = { cutout = MediaCutout.RECTANGLE; decoration = decoration.copy(kind = MediaFrameKind.NONE) }, modifier = Modifier.testTag("frame-remove")) {
                        Text(stringResource(Res.string.composer_frame_remove))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_cancel)) }
                    Button(onClick = { actions.setMediaFrameDesign(cutout, decoration); onDismiss() }, modifier = Modifier.testTag("frames-confirm")) {
                        Text(stringResource(Res.string.composer_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameChoice(label: String, selected: Boolean, tag: String, onClick: () -> Unit, preview: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    val colors = ImagoUi.colors
    Surface(shape = MaterialTheme.shapes.small, color = if (selected) colors.selectedSurface else colors.elevated,
        modifier = Modifier.width(88.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).testTag(tag)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.fillMaxWidth().height(48.dp), onDraw = preview)
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.text, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun FrameDesignSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, percent: Boolean = true, ratio: Boolean = false, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ImagoUi.colors.text, modifier = Modifier.width(90.dp))
        ImagoSlider(value.coerceIn(range), onChange, range, range.start, Modifier.weight(1f).testTag(tag).semantics { contentDescription = label })
        val displayed = if (ratio) stringResource(Res.string.composer_frame_ratio_value, java.lang.String.format(java.util.Locale.getDefault(), "%.1f", value + 1f))
            else if (percent) stringResource(Res.string.composer_percent_value, (value * 100).roundToInt())
            else stringResource(Res.string.composer_frame_pixels_value, value.roundToInt())
        Text(displayed,
            style = MaterialTheme.typography.labelMedium, color = ImagoUi.colors.secondaryText, modifier = Modifier.padding(start = 8.dp).widthIn(min = 32.dp))
    }
}

private fun MediaCutout.label(): StringResource = when (this) {
    MediaCutout.RECTANGLE -> Res.string.composer_frame_rectangle
    MediaCutout.ELLIPSE -> Res.string.composer_frame_ellipse
    MediaCutout.CIRCLE -> Res.string.composer_frame_circle
    MediaCutout.DIAMOND -> Res.string.composer_frame_diamond
    MediaCutout.HEXAGON -> Res.string.composer_frame_hexagon
    MediaCutout.STAR -> Res.string.composer_frame_star
    MediaCutout.HEART -> Res.string.composer_frame_heart
    MediaCutout.TORN_PAPER -> Res.string.composer_frame_torn
}
private fun MediaFrameKind.label(): StringResource = when (this) {
    MediaFrameKind.NONE -> Res.string.composer_frame_plain
    MediaFrameKind.CLASSIC -> Res.string.composer_frame_classic
    MediaFrameKind.DOUBLE_LINE -> Res.string.composer_frame_double
    MediaFrameKind.POLAROID -> Res.string.composer_frame_polaroid
    MediaFrameKind.TORN_PAPER -> Res.string.composer_frame_torn
}
