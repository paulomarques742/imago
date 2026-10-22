package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.MAX_PAGE_DURATION_MS
import eu.studio742.imago.core.composition.PageVideoPlan
import eu.studio742.imago.core.composition.VideoTimelineEdits
import eu.studio742.imago.core.composition.clipDurationMs
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val LaneHeader = 40.dp
private val LaneHeight = 40.dp
private val RulerHeight = 24.dp
private val EdgeHandle = 16.dp
private val SnapDistance = 10.dp
private val EndGrabDistance = 28.dp

/** What the screen reader's actions step by. */
private const val ACCESSIBLE_STEP_MS = 500L

/** The ruler's label spacings, in seconds; the first that leaves room for the text is used. */
private val LabelSteps = listOf(1, 2, 5, 10, 15, 30)

/**
 * A page's timeline: one lane per video, from the top layer down, over a ruler in seconds.
 *
 * Dragging a clip moves it along the page; dragging its edges trims it; the gold line is the end of
 * the page — how long the exported video lasts — and is dragged on the ruler. The speaker in front of
 * each lane chooses whether that clip is heard. Drags land on the page's edges and on the other clips'
 * edges when they pass near them, and on tenths of a second otherwise.
 *
 * What is drawn comes from the same `PageVideoPlan` both exporters encode from, so a loop shows its
 * repeats and a clip past the end shows cut where the video will cut it.
 */
@Composable
internal fun PageVideoTimeline(
    project: CompositionProject,
    pageIndex: Int,
    selectedId: String?,
    actions: ComposerEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val plan = remember(project, pageIndex) { PageVideoPlan.of(project, pageIndex) }
    // The top layer first, as in the layers list.
    val clips = remember(plan) { plan.clips.sortedByDescending { it.video.zIndex } }
    if (clips.isEmpty()) return
    val pageMs = plan.durationMs
    // The scale stays put while a finger is on the timeline: if it followed the clip being dragged out,
    // what is under the finger would slide away from it.
    var frozenSpan by remember { mutableStateOf<Long?>(null) }
    val furthest = clips.maxOf { it.video.timing.startOffsetMs + it.video.timing.clipDurationMs }
    val spanMs = frozenSpan ?: timelineSpan(pageMs, furthest)
    val onDragging: (Boolean) -> Unit = { dragging -> frozenSpan = if (dragging) spanMs else null }

    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.composer_timeline), style = MaterialTheme.typography.labelMedium, color = ImagoColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.composer_timeline_length, seconds(pageMs)),
                style = MaterialTheme.typography.labelMedium,
                color = ImagoColors.Gold,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val trackWidthPx = with(LocalDensity.current) { (maxWidth - LaneHeader).toPx() }.coerceAtLeast(1f)
            val pxPerMs = trackWidthPx / spanMs
            val clipEdges = clips.flatMap { listOf(it.video.timing.startOffsetMs, it.video.timing.startOffsetMs + it.video.timing.clipDurationMs) }
            Column {
                Row {
                    Spacer(Modifier.width(LaneHeader))
                    TimelineRuler(
                        spanMs = spanMs,
                        pageMs = pageMs,
                        pxPerMs = pxPerMs,
                        snapTargets = clipEdges,
                        onDragging = onDragging,
                        actions = actions,
                        pageIndex = pageIndex,
                        modifier = Modifier.weight(1f),
                    )
                }
                clips.forEach { clip ->
                    TimelineLane(
                        clip = clip,
                        pageMs = pageMs,
                        pxPerMs = pxPerMs,
                        selected = clip.video.id == selectedId,
                        snapTargets = listOf(0L, pageMs) + clips.filter { it.video.id != clip.video.id }.flatMap {
                            listOf(it.video.timing.startOffsetMs, it.video.timing.startOffsetMs + it.video.timing.clipDurationMs)
                        },
                        onDragging = onDragging,
                        actions = actions,
                    )
                }
            }
        }
        Text(
            stringResource(Res.string.composer_timeline_hint),
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/**
 * How many milliseconds the timeline shows: the page and its clips, with room to drag the end out,
 * rounded up to whole seconds.
 */
internal fun timelineSpan(pageMs: Long, furthestClipEndMs: Long): Long {
    val content = max(pageMs, furthestClipEndMs).coerceAtMost(MAX_PAGE_DURATION_MS)
    val withRoom = max(content * 5 / 4, content + 2_000).coerceIn(5_000, MAX_PAGE_DURATION_MS + 2_000)
    return ceil(withRoom / 1000.0).toLong() * 1000
}

@Composable
private fun TimelineRuler(
    spanMs: Long,
    pageMs: Long,
    pxPerMs: Float,
    snapTargets: List<Long>,
    onDragging: (Boolean) -> Unit,
    actions: ComposerEditorViewModel,
    pageIndex: Int,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val currentPage by rememberUpdatedState(pageMs)
    val currentScale by rememberUpdatedState(pxPerMs)
    val currentTargets by rememberUpdatedState(snapTargets)
    val endLabel = stringResource(Res.string.composer_timeline_video_end)
    val lengthText = stringResource(Res.string.composer_timeline_length, seconds(pageMs))
    val shorter = stringResource(Res.string.composer_video_end_earlier)
    val longer = stringResource(Res.string.composer_video_end_later)
    val labelStyle = TextStyle(color = ImagoColors.TextTertiary, fontSize = 10.sp)
    Canvas(
        modifier
            .height(RulerHeight)
            .semantics {
                contentDescription = "$endLabel, $lengthText"
                customActions = listOf(
                    CustomAccessibilityAction(shorter) { actions.setPageDuration(pageIndex, pageMs - 1_000); true },
                    CustomAccessibilityAction(longer) { actions.setPageDuration(pageIndex, pageMs + 1_000); true },
                )
            }
            .pointerInput(Unit) {
                val grab = with(density) { EndGrabDistance.toPx() }
                val tolerance = with(density) { SnapDistance.toPx() }
                var dragging = false
                var start = 0L
                var travelled = 0f
                detectDragGestures(
                    onDragStart = { down ->
                        dragging = abs(down.x - currentPage * currentScale) <= grab
                        if (dragging) {
                            start = currentPage
                            travelled = 0f
                            onDragging(true)
                            actions.beginGesture(stage = false)
                        }
                    },
                    onDragEnd = { if (dragging) { dragging = false; actions.endGesture(stage = false); onDragging(false) } },
                    onDragCancel = { if (dragging) { dragging = false; actions.endGesture(stage = false); onDragging(false) } },
                ) { change, amount ->
                    if (!dragging) return@detectDragGestures
                    change.consume()
                    travelled += amount.x
                    val target = start + (travelled / currentScale).toLong()
                    actions.setPageDuration(pageIndex, VideoTimelineEdits.snap(target, currentTargets, (tolerance / currentScale).toLong()))
                }
            },
    ) {
        val labelEvery = LabelSteps.firstOrNull { it * 1000 * pxPerMs >= 34.dp.toPx() } ?: 60
        val seconds = (spanMs / 1000).toInt()
        for (second in 0..seconds) {
            val x = second * 1000 * pxPerMs
            val major = second % labelEvery == 0
            drawLine(
                color = if (major) ImagoColors.BorderStrong else ImagoColors.BorderSubtle,
                start = Offset(x, size.height * if (major) .45f else .7f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            if (major) {
                // A label that would not fit before the edge is left out; laid out without room, it throws.
                val label = measurer.measure(second.toString(), labelStyle)
                val left = x + 2.dp.toPx()
                if (left + label.size.width <= size.width) drawText(label, topLeft = Offset(left, 0f))
            }
        }
        // Past the end of the page nothing is exported.
        val end = pageMs * pxPerMs
        drawRect(Color.Black.copy(alpha = .35f), topLeft = Offset(end, 0f), size = Size((size.width - end).coerceAtLeast(0f), size.height))
        val tab = 6.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(end - tab, 0f)
                lineTo(end + tab, 0f)
                lineTo(end + tab, size.height - tab)
                lineTo(end, size.height)
                lineTo(end - tab, size.height - tab)
                close()
            },
            ImagoColors.Gold,
        )
    }
}

@Composable
private fun TimelineLane(
    clip: PageVideoPlan.Layer.Clip,
    pageMs: Long,
    pxPerMs: Float,
    selected: Boolean,
    snapTargets: List<Long>,
    onDragging: (Boolean) -> Unit,
    actions: ComposerEditorViewModel,
) {
    val video = clip.video
    val name = video.media.fileName
    Row(Modifier.fillMaxWidth().height(LaneHeight), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.setVideoAudible(video.id, !clip.audible) }, modifier = Modifier.size(LaneHeader)) {
            Icon(
                if (clip.audible) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                contentDescription = stringResource(
                    if (clip.audible) Res.string.composer_clip_sound_on else Res.string.composer_clip_sound_off,
                    name,
                ),
                tint = if (clip.audible) ImagoColors.TextPrimary else ImagoColors.TextTertiary,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .clipToBounds()
                .drawBehind {
                    drawRoundRect(ImagoColors.Graphite, cornerRadius = CornerRadius(4.dp.toPx()))
                    // The repeats of a loop, after the clip itself.
                    var cursor = 0L
                    var first = true
                    clip.segments.forEach { segment ->
                        if (segment is PageVideoPlan.Segment.Source) {
                            if (!first) {
                                drawRoundRect(
                                    ImagoColors.Gold.copy(alpha = .22f),
                                    topLeft = Offset(cursor * pxPerMs, 0f),
                                    size = Size(segment.durationMs * pxPerMs, size.height),
                                    cornerRadius = CornerRadius(4.dp.toPx()),
                                )
                            }
                            first = false
                        }
                        cursor += segment.durationMs
                    }
                },
        ) {
            ClipBlock(clip, pxPerMs, selected, snapTargets, onDragging, actions)
            // What runs past the end of the page is cut in the export, and is shown darkened.
            Box(
                Modifier.fillMaxHeight().fillMaxWidth().drawBehind {
                    val end = pageMs * pxPerMs
                    drawRect(Color.Black.copy(alpha = .45f), topLeft = Offset(end, 0f), size = Size((size.width - end).coerceAtLeast(0f), size.height))
                    drawLine(ImagoColors.Gold, Offset(end, 0f), Offset(end, size.height), strokeWidth = 2.dp.toPx())
                },
            )
        }
    }
}

private enum class ClipDrag { MOVE, TRIM_START, TRIM_END }

@Composable
private fun ClipBlock(
    clip: PageVideoPlan.Layer.Clip,
    pxPerMs: Float,
    selected: Boolean,
    snapTargets: List<Long>,
    onDragging: (Boolean) -> Unit,
    actions: ComposerEditorViewModel,
) {
    val density = LocalDensity.current
    val video = clip.video
    val timing = video.timing
    val currentTiming by rememberUpdatedState(timing)
    val currentScale by rememberUpdatedState(pxPerMs)
    val currentTargets by rememberUpdatedState(snapTargets)
    val widthDp = with(density) { (timing.clipDurationMs * pxPerMs).toDp() }
    val description = stringResource(
        Res.string.composer_clip_description,
        video.media.fileName,
        seconds(timing.trimStartMs),
        seconds(timing.trimEndMs),
        seconds(timing.startOffsetMs),
    )
    val earlier = stringResource(Res.string.composer_clip_move_earlier)
    val later = stringResource(Res.string.composer_clip_move_later)
    val endEarlier = stringResource(Res.string.composer_clip_end_earlier)
    val endLater = stringResource(Res.string.composer_clip_end_later)
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .offset { IntOffset((timing.startOffsetMs * pxPerMs).roundToInt(), 0) }
            .width(widthDp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) ImagoColors.Gold.copy(alpha = .55f) else ImagoColors.Ivory.copy(alpha = .28f))
            .border(if (selected) 2.dp else 1.dp, if (selected) ImagoColors.Gold else ImagoColors.BorderStrong, shape)
            .semantics {
                contentDescription = description
                val end = timing.startOffsetMs + timing.clipDurationMs
                customActions = listOf(
                    CustomAccessibilityAction(earlier) { actions.moveVideoClip(video.id, timing.startOffsetMs - ACCESSIBLE_STEP_MS); true },
                    CustomAccessibilityAction(later) { actions.moveVideoClip(video.id, timing.startOffsetMs + ACCESSIBLE_STEP_MS); true },
                    CustomAccessibilityAction(endEarlier) { actions.trimVideoClipEnd(video.id, end - ACCESSIBLE_STEP_MS); true },
                    CustomAccessibilityAction(endLater) { actions.trimVideoClipEnd(video.id, end + ACCESSIBLE_STEP_MS); true },
                )
            }
            .pointerInput(video.id) {
                detectTapGestures { actions.selectElement(video.id) }
            }
            .pointerInput(video.id) {
                val handle = with(density) { EdgeHandle.toPx() }
                val tolerance = with(density) { SnapDistance.toPx() }
                var mode = ClipDrag.MOVE
                var startOffset = 0L
                var length = 0L
                var travelled = 0f
                fun finish() {
                    actions.endGesture(stage = false)
                    onDragging(false)
                }
                detectDragGestures(
                    onDragStart = { down ->
                        val width = size.width.toFloat()
                        // A short clip leaves no middle: then the edge nearest the finger wins only within
                        // a third of the block each.
                        val edge = minOf(handle, width / 3f)
                        mode = when {
                            down.x <= edge -> ClipDrag.TRIM_START
                            down.x >= width - edge -> ClipDrag.TRIM_END
                            else -> ClipDrag.MOVE
                        }
                        startOffset = currentTiming.startOffsetMs
                        length = currentTiming.clipDurationMs
                        travelled = 0f
                        actions.selectElement(video.id)
                        onDragging(true)
                        actions.beginGesture(stage = false)
                    },
                    onDragEnd = ::finish,
                    onDragCancel = ::finish,
                ) { change, amount ->
                    change.consume()
                    travelled += amount.x
                    val deltaMs = (travelled / currentScale).toLong()
                    val toleranceMs = (tolerance / currentScale).toLong()
                    when (mode) {
                        ClipDrag.MOVE -> actions.moveVideoClip(
                            video.id,
                            snapMove(startOffset + deltaMs, length, currentTargets, toleranceMs),
                        )
                        ClipDrag.TRIM_START -> actions.trimVideoClipStart(
                            video.id,
                            VideoTimelineEdits.snap(startOffset + deltaMs, currentTargets, toleranceMs),
                        )
                        ClipDrag.TRIM_END -> actions.trimVideoClipEnd(
                            video.id,
                            VideoTimelineEdits.snap(startOffset + length + deltaMs, currentTargets, toleranceMs),
                        )
                    }
                }
            },
    ) {
        Text(
            video.media.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = EdgeHandle),
        )
        // The grips say where trimming starts.
        Box(Modifier.align(Alignment.CenterStart).padding(start = 5.dp).size(3.dp, 14.dp).background(ImagoColors.TextPrimary, RoundedCornerShape(2.dp)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).size(3.dp, 14.dp).background(ImagoColors.TextPrimary, RoundedCornerShape(2.dp)))
    }
}

/** A moved clip lines up by whichever of its edges is nearer a target; otherwise it moves by steps. */
internal fun snapMove(startMs: Long, lengthMs: Long, targets: Collection<Long>, toleranceMs: Long): Long {
    val byStart = targets.minByOrNull { abs(it - startMs) }?.takeIf { abs(it - startMs) <= toleranceMs }
    val byEnd = targets.minByOrNull { abs(it - (startMs + lengthMs)) }?.takeIf { abs(it - (startMs + lengthMs)) <= toleranceMs }
    return when {
        byStart != null && (byEnd == null || abs(byStart - startMs) <= abs(byEnd - startMs - lengthMs)) -> byStart
        byEnd != null -> byEnd - lengthMs
        else -> VideoTimelineEdits.snap(startMs, emptyList(), 0)
    }
}

private fun seconds(ms: Long): String = String.format(java.util.Locale.getDefault(), "%.1f", ms / 1000f)
