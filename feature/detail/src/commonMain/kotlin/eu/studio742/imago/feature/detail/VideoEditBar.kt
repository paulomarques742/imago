package eu.studio742.imago.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.model.MIN_VIDEO_TRIM_MS
import eu.studio742.imago.feature.detail.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong

private val HandleWidth = 14.dp
private val BarHeight = 44.dp

/**
 * What the person edits on a video, below the player: a turn, and the stretch that is kept. Nothing
 * here touches the file; [onDone] keeps the choice in the recipe and [onExport] makes a new video.
 */
@Composable
internal fun VideoEditBar(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    /** Where the video is playing, drawn over the bar. */
    positionMs: Long,
    onRange: (startMs: Long, endMs: Long) -> Unit,
    /** Whether an edge is being dragged: the rest of the bar steps aside to leave the video clear. */
    onDraggingChange: (Boolean) -> Unit,
    onRotate: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    /** Null until the edit changes the video: there is nothing to export before that. */
    onExport: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val aside by androidx.compose.animation.core.animateFloatAsState(if (dragging) 0f else 1f)
    Column(modifier.fillMaxWidth().padding(horizontal = ImagoSpacing.Lg)) {
        Row(Modifier.alpha(aside), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    Res.string.detail_video_trim_kept,
                    formatPlaybackTime(startMs, durationMs),
                    formatPlaybackTime(endMs, durationMs),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = ImagoUi.colors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRotate) {
                Icon(Icons.AutoMirrored.Outlined.RotateRight, stringResource(Res.string.detail_video_edit_rotate), tint = ImagoUi.colors.text)
            }
        }
        VideoTrimBar(
            durationMs, startMs, endMs, positionMs, onRange,
            onDraggingChange = { dragging = it; onDraggingChange(it) },
            modifier = Modifier.padding(vertical = ImagoSpacing.Xs),
        )
        Text(
            stringResource(Res.string.detail_video_trim_note),
            style = MaterialTheme.typography.labelSmall,
            color = ImagoUi.colors.tertiaryText,
            modifier = Modifier.alpha(aside).padding(vertical = ImagoSpacing.Xs),
        )
        Row(
            Modifier.fillMaxWidth().alpha(aside),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.detail_video_edit_cancel)) }
            if (onExport != null) {
                TextButton(onClick = onExport) { Text(stringResource(Res.string.detail_video_edit_export)) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text(stringResource(Res.string.detail_video_edit_done)) }
            }
        }
    }
}

/** The video as a bar with a handle at each end of what is kept; the part between them is lit. */
@Composable
private fun VideoTrimBar(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    onRange: (Long, Long) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDragging by rememberUpdatedState(onDraggingChange)
    val colors = ImagoUi.colors
    val density = LocalDensity.current
    val currentStart by rememberUpdatedState(startMs)
    val currentEnd by rememberUpdatedState(endMs)
    val currentDuration by rememberUpdatedState(durationMs)
    val description = stringResource(Res.string.detail_video_trim)
    val state = stringResource(
        Res.string.detail_video_trim_kept,
        formatPlaybackTime(startMs, durationMs),
        formatPlaybackTime(endMs, durationMs),
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(BarHeight)
            .semantics {
                contentDescription = description
                stateDescription = state
            }
            .pointerInput(Unit) {
                val handle = with(density) { HandleWidth.toPx() }
                var movesStart = true
                detectDragGestures(
                    onDragStart = { down ->
                        val track = size.width - handle * 2
                        fun x(ms: Long) = handle + track * ms / currentDuration.coerceAtLeast(1L)
                        // The edge nearer the finger moves.
                        movesStart = abs(down.x - x(currentStart)) <= abs(down.x - x(currentEnd))
                        currentDragging(true)
                    },
                    onDragEnd = { currentDragging(false) },
                    onDragCancel = { currentDragging(false) },
                ) { change, _ ->
                    change.consume()
                    val track = size.width - handle * 2
                    val ms = (((change.position.x - handle) / track) * currentDuration).toLong()
                    val snapped = (ms / 100.0).roundToLong() * 100L
                    if (movesStart) {
                        val start = snapped.coerceIn(0L, (currentEnd - MIN_VIDEO_TRIM_MS).coerceAtLeast(0L))
                        onRange(start, currentEnd)
                    } else {
                        val end = snapped.coerceIn(minOf(currentStart + MIN_VIDEO_TRIM_MS, currentDuration), currentDuration)
                        onRange(currentStart, end)
                    }
                }
            },
    ) {
        val handle = HandleWidth.toPx()
        val track = size.width - handle * 2
        val span = durationMs.coerceAtLeast(1L).toFloat()
        val left = handle + track * startMs / span
        val right = handle + track * endMs / span
        val corner = CornerRadius(4.dp.toPx())
        drawRoundRect(colors.selectedSurface, Offset(handle, 0f), Size(track, size.height), corner)
        drawRoundRect(colors.accent.copy(alpha = .45f), Offset(left, 0f), Size((right - left).coerceAtLeast(0f), size.height), corner)
        drawRoundRect(colors.accent, Offset(left - handle, 0f), Size(handle, size.height), corner)
        drawRoundRect(colors.accent, Offset(right, 0f), Size(handle, size.height), corner)
        // The playhead: where the video is, so the loop is seen going round.
        val playhead = handle + track * positionMs.coerceIn(0L, durationMs) / span
        val line = 2.dp.toPx()
        drawRoundRect(colors.text, Offset(playhead - line / 2, 0f), Size(line, size.height), CornerRadius(line / 2))
        val grip = 3.dp.toPx()
        drawRoundRect(colors.text, Offset(left - handle / 2 - grip / 2, size.height * .3f), Size(grip, size.height * .4f), CornerRadius(grip / 2))
        drawRoundRect(colors.text, Offset(right + handle / 2 - grip / 2, size.height * .3f), Size(grip, size.height * .4f), CornerRadius(grip / 2))
    }
}
