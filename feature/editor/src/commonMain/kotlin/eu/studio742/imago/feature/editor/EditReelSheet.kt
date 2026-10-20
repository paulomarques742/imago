package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.i18n.resolve
import coil3.Bitmap
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.editor_cancel
import eu.studio742.imago.feature.editor.resources.reel_done
import eu.studio742.imago.feature.editor.resources.reel_export
import eu.studio742.imago.feature.editor.resources.reel_order
import eu.studio742.imago.feature.editor.resources.reel_order_history
import eu.studio742.imago.feature.editor.resources.reel_order_recipe
import eu.studio742.imago.feature.editor.resources.reel_preview
import eu.studio742.imago.feature.editor.resources.reel_progress
import eu.studio742.imago.feature.editor.resources.reel_share
import eu.studio742.imago.feature.editor.resources.reel_sheet_title
import eu.studio742.imago.feature.editor.resources.reel_style
import eu.studio742.imago.feature.editor.resources.reel_style_list
import eu.studio742.imago.feature.editor.resources.reel_style_panel
import eu.studio742.imago.feature.editor.resources.reel_style_pill
import eu.studio742.imago.feature.editor.resources.reel_title_hint
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** The preview draws at half the video's size: it has to keep up while it plays. */
private const val PREVIEW_SCALE = 0.5f
private const val TITLE_MAX_LENGTH = 48

/**
 * "Show your edit": the video of the edit, played as it will come out, with the choices that change
 * it — style, title, order — and the export.
 */
@Composable
internal fun EditReelSheet(
    state: EditorUiState,
    initialStyle: () -> ReelStyle,
    onStyle: (ReelStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val platform = rememberReelPlatform()
    val exports = viewModel { ReelExportViewModel() }
    val export by exports.state.collectAsState()
    val busy = export.progress != null

    var style by rememberSaveable { mutableStateOf(initialStyle()) }
    var title by rememberSaveable { mutableStateOf("") }
    val hasHistory = state.history.size > 1 && state.historyIndex > 0
    var fromHistory by rememberSaveable { mutableStateOf(true) }
    val recipe = state.recipe
    val script = remember(recipe, state.history, state.historyIndex, fromHistory && hasHistory) {
        when {
            recipe == null -> null
            fromHistory && hasHistory -> EditReelScript.fromHistory(state.history, state.historyIndex)
            else -> EditReelScript.fromRecipe(recipe)
        }
    }
    val photo = state.bitmap
    val preview = rememberReelPreview(platform, script, style, title, photo, paused = busy)
    val exportMeasurer = rememberTextMeasurer()

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = ImagoColors.Background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth > maxHeight && maxWidth >= 600.dp
                val previewHeight = maxHeight * 0.55f
                val controls: @Composable ColumnScope.() -> Unit = {
                    ReelChoices(
                        style = style,
                        // A new choice makes a new video: the one already saved stops being the answer.
                        onStyle = {
                            style = it
                            onStyle(it)
                            exports.acknowledge()
                        },
                        title = title,
                        onTitle = {
                            title = it.take(TITLE_MAX_LENGTH)
                            exports.acknowledge()
                        },
                        hasHistory = hasHistory,
                        fromHistory = fromHistory,
                        onFromHistory = {
                            fromHistory = it
                            exports.acknowledge()
                        },
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(20.dp))
                    ReelExportArea(
                        export = export,
                        canExport = script != null && photo != null,
                        canShare = platform.share != null,
                        onExport = {
                            val currentScript = script ?: return@ReelExportArea
                            val pixels = photo ?: return@ReelExportArea
                            exports.export(
                                platform = platform,
                                script = currentScript,
                                style = style,
                                title = title,
                                photo = pixels,
                                fileName = reelFileName(state.asset?.fileName.orEmpty()),
                                textMeasurer = exportMeasurer,
                            )
                        },
                        onCancel = exports::cancel,
                        onShare = { export.saved?.let { saved -> platform.share?.invoke(saved) } },
                        onDone = {
                            exports.acknowledge()
                            onDismiss()
                        },
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    ReelTopBar(onClose = onDismiss, enabled = !busy)
                    if (wide) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                        ) {
                            ReelPreview(preview, Modifier.fillMaxHeight().weight(1f))
                            Column(
                                modifier = Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                content = controls,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        ) {
                            ReelPreview(preview, Modifier.fillMaxWidth().height(previewHeight))
                            Spacer(Modifier.height(20.dp))
                            controls()
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The video playing in real time: each tick draws the frame of the instant it is, skipping the ones
 * a slow device cannot keep up with, so the preview never plays slower than the file will.
 */
@Composable
private fun rememberReelPreview(
    platform: ReelPlatform,
    script: EditReelScript?,
    style: ReelStyle,
    title: String,
    photo: Bitmap?,
    /** While the video is being made the preview holds its frame: the two would share the processor. */
    paused: Boolean,
): ImageBitmap? {
    val measurer = rememberTextMeasurer()
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(platform, script, style, title, photo, paused) {
        if (paused) return@LaunchedEffect
        val currentScript = script ?: return@LaunchedEffect
        val bitmap = photo ?: return@LaunchedEffect
        // Typing a title restarts the preview at every letter; a pause lets the word finish first.
        delay(200)
        val texts = reelTexts(currentScript, title)
        val painter = ReelFramePainter(currentScript, style, texts, measurer, scale = PREVIEW_SCALE)
        val photos = withContext(Dispatchers.Default) { platform.photoRenderer(bitmap, painter.photoMaxSide) }
        try {
            val renderer = EditReelRenderer(currentScript, photos, painter)
            val start = TimeSource.Monotonic.markNow()
            while (isActive) {
                val elapsed = start.elapsedNow().inWholeMilliseconds % currentScript.durationMs
                val index = (elapsed * renderer.fps / 1000).toInt().coerceIn(0, renderer.frameCount - 1)
                frame = withContext(Dispatchers.Default) { renderer.frame(index) }
                delay(1)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { photos.close() }
        }
    }
    return frame
}

@Composable
private fun ReelTopBar(onClose: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = enabled) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.editor_cancel))
        }
        Text(
            stringResource(Res.string.reel_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = ImagoColors.TextPrimary,
        )
    }
}

@Composable
private fun ReelPreview(frame: ImageBitmap?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                .clip(shape).background(ImagoColors.BrandBlack),
            contentAlignment = Alignment.Center,
        ) {
            if (frame == null) {
                CircularProgressIndicator(color = ImagoColors.TextSecondary)
            } else {
                Image(
                    bitmap = frame,
                    contentDescription = stringResource(Res.string.reel_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ReelChoices(
    style: ReelStyle,
    onStyle: (ReelStyle) -> Unit,
    title: String,
    onTitle: (String) -> Unit,
    hasHistory: Boolean,
    fromHistory: Boolean,
    onFromHistory: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceLabel(stringResource(Res.string.reel_style))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReelStyle.entries.forEach { option ->
                FilterChip(
                    selected = style == option,
                    onClick = { onStyle(option) },
                    enabled = enabled,
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }
        if (hasHistory) {
            Spacer(Modifier.height(6.dp))
            ChoiceLabel(stringResource(Res.string.reel_order))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = fromHistory,
                    onClick = { onFromHistory(true) },
                    enabled = enabled,
                    label = { Text(stringResource(Res.string.reel_order_history)) },
                )
                FilterChip(
                    selected = !fromHistory,
                    onClick = { onFromHistory(false) },
                    enabled = enabled,
                    label = { Text(stringResource(Res.string.reel_order_recipe)) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.reel_title_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChoiceLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = ImagoColors.TextSecondary)
}

@Composable
private fun ReelExportArea(
    export: ReelExportState,
    canExport: Boolean,
    canShare: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val progress = export.progress
        val saved = export.saved
        when {
            progress != null -> {
                Text(
                    stringResource(Res.string.reel_progress, "%d%%".format((progress * 100).toInt())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.TextPrimary,
                )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.editor_cancel)) }
            }
            saved != null -> {
                Text(saved.message.resolve(), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
                if (canShare) {
                    Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.reel_share)) }
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.reel_done)) }
            }
            else -> {
                export.error?.let { error ->
                    Text(error.resolve(), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.Error)
                }
                Button(onClick = onExport, enabled = canExport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.reel_export))
                }
            }
        }
    }
}

private fun ReelStyle.labelRes() = when (this) {
    ReelStyle.PANEL -> Res.string.reel_style_panel
    ReelStyle.PILL -> Res.string.reel_style_pill
    ReelStyle.LIST -> Res.string.reel_style_list
}
