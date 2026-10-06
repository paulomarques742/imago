package eu.studio742.imago.feature.library

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.library.resources.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoMotion
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The strip to the right of the grid: wide enough for the thumb, narrow so it covers little. */
private val RAIL_WIDTH = 36.dp
private val THUMB_WIDTH = 6.dp
private val THUMB_HEIGHT = 52.dp

/** The minimum space between two year labels; below this the lower one is not drawn. */
private val MARKER_GAP = 22.dp

/**
 * How far above and below the handle the finger still grabs it.
 *
 * It is this slack — and not a strip clickable everywhere — that keeps the rest of the right corner
 * belonging to the tiles: one grabs the handle, one does not jump to where one tapped.
 */
private val GRAB_SLACK = 32.dp

/** How long the strip stays in view after the grid stops. */
private const val IDLE_HIDE_MS = 1800L

/**
 * The months fan: twelve targets in an arc, to the left of the strip.
 *
 * It is not an ellipse, on purpose. In an ellipse the months at the ends turn back inwards, over the
 * year ruler, and squeeze against each other — both defects at once. Here the vertical distance
 * between months is fixed, and what varies is how far each one moves away from the strip: none comes
 * closer than [ARC_INSET], and the middle one moves [ARC_BULGE] further. It still reads as a
 * semicircle, but with every target the same size.
 */
private val ARC_HEIGHT = 400.dp
private val ARC_INSET = 96.dp
private val ARC_BULGE = 96.dp
private val MONTH_CHIP = 32.dp

/** How long the finger has to rest on a year before the months appear. */
private const val ARC_DWELL_MS = 220L

/** From this distance to the handle, the finger stopped choosing the year and started choosing the month. */
private val ARC_ENTER = 64.dp

/**
 * From how many photos the strip appears.
 *
 * Below this, normal scrolling gets there in two gestures, and a handle crossing the right corner
 * would be just one more thing covering the grid.
 */
private const val FAST_SCROLL_MIN_ITEMS = 60

/** A month in the fan, and the grid position where it starts. */
internal class MonthSlot(val label: String, val index: Int)

/** A year in the strip: a step of the same height as the others, with its months. */
internal class YearSlot(
    val year: Int,
    val startIndex: Int,
    val endIndex: Int,
    val months: List<MonthSlot>,
)

/**
 * The map of the whole library, built on the months the server counts.
 *
 * It serves two jobs: saying which years exist, and saying at which grid position each month starts.
 * The count is the server's and the grid is the local catalogue's; when they differ — a filtered
 * export, a photo in the trash — the difference is made up by a scale, so the year's step and the
 * grid index do not drift apart.
 */
internal class LibraryTimeline(months: List<MonthUiModel>) {
    private class Entry(val start: LocalDate, val count: Int, val before: Int)

    private val entries: List<Entry>

    /** How many photos the library has, according to the server. */
    val total: Int

    val isEmpty: Boolean get() = entries.isEmpty()

    init {
        var running = 0
        entries = months
            .mapNotNull { month ->
                // The month id may come as a date or as an instant, depending on the server version;
                // the first ten characters are the date in both.
                runCatching { LocalDate.parse(month.value.take(10)) }.getOrNull()
                    ?.takeIf { month.assetCount > 0 }
                    ?.let { it to month.assetCount }
            }
            // From the most recent month to the oldest, like the grid.
            .sortedByDescending { (start, _) -> start }
            .map { (start, count) ->
                Entry(start, count, running).also { running += count }
            }
        total = running
    }

    /**
     * The years, from the most recent to the oldest, with where each month starts in the grid.
     *
     * The scale reconciles the server's count with the grid's: if the local catalogue has fewer
     * photos than the server counts, the indices shrink in the same proportion instead of pointing
     * past the end of the list.
     */
    fun yearSlots(itemCount: Int): List<YearSlot> {
        if (entries.isEmpty() || total <= 0 || itemCount <= 0) return emptyList()
        val scale = itemCount.toFloat() / total
        fun at(position: Int) = (position * scale).roundToInt().coerceIn(0, itemCount - 1)
        // `groupBy` keeps the encounter order, and the entries already come from the most recent to
        // the oldest: the years come out in the strip's order without sorting them again.
        return entries.groupBy { it.start.year }.map { (year, yearEntries) ->
            val last = yearEntries.last()
            YearSlot(
                year = year,
                startIndex = at(yearEntries.first().before),
                endIndex = at(last.before + last.count),
                months = yearEntries.map { entry ->
                    MonthSlot(label = monthLabel(entry.start.month), index = at(entry.before))
                },
            )
        }
    }
}

/** "Jan", "Feb", … — three letters are enough for a thirty-two-point target. */
private fun monthLabel(month: Month): String =
    month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .take(3)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/**
 * The conversion between the position on the strip and the index in the grid.
 *
 * Each year takes a step of equal height, and not the space proportional to its photos. That is the
 * difference that makes this usable: with seventy thousand photos, 2019 alone was worth a fifth of
 * the strip and 2005 half a millimetre. Within the step the position is still proportional, so the
 * vertical drag gives an approximation; the right month is chosen in the fan.
 *
 * Without years — in a sliced view, where the server's months do not describe the grid — the map is
 * the list itself, and the strip goes back to being a proportional scroll bar.
 */
internal class RailScale(val years: List<YearSlot>, private val itemCount: Int) {
    private val lastIndex = (itemCount - 1).coerceAtLeast(0)

    fun fractionOf(index: Int): Float {
        if (years.isEmpty()) return (index.toFloat() / lastIndex.coerceAtLeast(1)).coerceIn(0f, 1f)
        val slot = years.indexOfLast { it.startIndex <= index }.coerceAtLeast(0)
        val year = years[slot]
        val span = (year.endIndex - year.startIndex).coerceAtLeast(1)
        val within = ((index - year.startIndex).toFloat() / span).coerceIn(0f, 1f)
        return ((slot + within) / years.size).coerceIn(0f, 1f)
    }

    fun slotAt(fraction: Float): Int =
        if (years.isEmpty()) 0 else (fraction * years.size).toInt().coerceIn(0, years.lastIndex)

    fun indexAt(fraction: Float): Int {
        if (years.isEmpty()) return (fraction * lastIndex).roundToInt().coerceIn(0, lastIndex)
        val scaled = fraction.coerceIn(0f, 1f) * years.size
        val slot = scaled.toInt().coerceIn(0, years.lastIndex)
        val year = years[slot]
        val within = (scaled - slot).coerceIn(0f, 1f)
        return (year.startIndex + within * (year.endIndex - year.startIndex))
            .roundToInt()
            .coerceIn(0, lastIndex)
    }
}

/**
 * The library's fast scroll: the year vertically, the month in the fan.
 *
 * The years are steps of equal height, a thirty-point target each, with a haptic tick marking the
 * passage over each one. Beside the handle the fan of that year's months opens; moving the finger
 * away from the strip starts choosing the month instead of the year, and releasing confirms it.
 *
 * The grid follows the finger live the whole time, because the whole timeline is in the local
 * catalogue: what is missing at a distant position is the thumbnail, not the photo.
 */
@Composable
internal fun LibraryFastScroll(
    assets: LazyPagingItems<AssetUiModel>,
    gridState: LazyStaggeredGridState,
    /** The library map; empty when the view is sliced and the months do not describe it. */
    timeline: LibraryTimeline,
    modifier: Modifier = Modifier,
) {
    val itemCount = assets.itemCount
    if (itemCount < FAST_SCROLL_MIN_ITEMS) return

    val scale = remember(timeline, itemCount) { RailScale(timeline.yearSlots(itemCount), itemCount) }

    val position by remember(scale) {
        derivedStateOf { scale.fractionOf(gridState.firstVisibleItemIndex) }
    }

    // The gesture writes an index; this effect is what carries it out. A `scrollToItem` per frame
    // would fight the previous one over the same scroll — here only the last request survives.
    var live by remember { mutableIntStateOf(-1) }
    LaunchedEffect(gridState) {
        snapshotFlow { live }.collectLatest { index -> if (index >= 0) gridState.scrollToItem(index) }
    }

    FastScrollRail(
        position = position,
        isScrolling = gridState.isScrollInProgress,
        scale = scale,
        modifier = modifier,
        // Without years there is no fan nor year to announce; the bubble says the date of the
        // destination photo, which in a sliced view is the only thing known about it.
        dateAt = { index -> assets.peek(index)?.date?.let(::formatScrubberDate) },
        onScrub = { index -> live = index },
    )
}

/**
 * The handle, the year ruler and the months fan.
 *
 * The gesture is a single one, from start to end: grab the handle, drag vertically to choose the
 * year and move the finger to the left to choose the month. While the finger is in the fan the year
 * is locked — otherwise the fan would run away from under the finger while pointing at it.
 */
@Composable
private fun FastScrollRail(
    position: Float,
    isScrolling: Boolean,
    scale: RailScale,
    dateAt: (Int) -> String?,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val railDescription = stringResource(Res.string.library_fast_scroll)
    val thumbHeightPx = with(density) { THUMB_HEIGHT.toPx() }
    val grabSlackPx = with(density) { GRAB_SLACK.toPx() }
    val arcEnterPx = with(density) { ARC_ENTER.toPx() }
    val arcHeightPx = with(density) { ARC_HEIGHT.toPx() }
    val arcInsetPx = with(density) { ARC_INSET.toPx() }
    val arcBulgePx = with(density) { ARC_BULGE.toPx() }
    val markerGapPx = with(density) { MARKER_GAP.toPx() }

    var railHeight by remember { mutableIntStateOf(0) }
    var grabbed by remember { mutableStateOf(false) }
    var railFraction by remember { mutableFloatStateOf(0f) }
    var monthChoice by remember { mutableIntStateOf(-1) }
    val positionNow = rememberUpdatedState(position)
    val scaleNow = rememberUpdatedState(scale)
    val scrub = rememberUpdatedState(onScrub)

    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(isScrolling, grabbed) {
        if (isScrolling || grabbed) {
            visible = true
        } else {
            delay(IDLE_HIDE_MS)
            visible = false
        }
    }
    val opacity by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(ImagoMotion.Default),
        label = "fast-scroll",
    )

    // Off screen nothing stays here: an invisible strip catching taps stole the right column's tile
    // from whoever just wanted to open a photo.
    if (opacity <= 0f) return

    var monthsOpen by remember { mutableStateOf(false) }
    val fraction = if (grabbed) railFraction else position
    val travel = (railHeight - thumbHeightPx).coerceAtLeast(0f)
    val thumbTop = fraction * travel
    val thumbCentre = thumbTop + thumbHeightPx / 2f
    // The fan has to fit on the screen. When the handle is against an end it is the fan's centre that
    // moves back — and as the drawing and the choice use the same centre, the finger keeps pointing
    // at the month it sees.
    val arcCentre = arcCentreFor(thumbCentre, railHeight.toFloat(), arcHeightPx / 2f)
    val slot = scale.slotAt(fraction)
    val year = scale.years.getOrNull(slot)
    val monthsOpenNow = rememberUpdatedState(monthsOpen)

    // The months only appear after the finger rests on a year. While sweeping the ruler they would be
    // twelve balls jumping around at every step; at rest, they are the next choice.
    LaunchedEffect(grabbed, slot, monthChoice >= 0) {
        if (!grabbed) {
            monthsOpen = false
            return@LaunchedEffect
        }
        // While choosing a month the fan is already open — closing it here would pull it from under the finger.
        if (monthChoice >= 0) return@LaunchedEffect
        monthsOpen = false
        delay(ARC_DWELL_MS)
        monthsOpen = true
    }
    val arcOpacity by animateFloatAsState(
        targetValue = if (monthsOpen) 1f else 0f,
        animationSpec = tween(ImagoMotion.Fast),
        label = "fast-scroll-arc",
    )

    fun monthOffsets(count: Int): List<Pair<Float, Float>> = List(count) { position ->
        // From the top down, like the grid: the year's most recent month is at the top. The height is
        // split in equal parts; the offset is a cosine, zero at the ends and full in the middle.
        val step = if (count == 1) 0.5 else position.toDouble() / (count - 1)
        val x = -(arcInsetPx + arcBulgePx * cos(Math.PI * (step - 0.5)).toFloat())
        val y = ((step - 0.5) * arcHeightPx).toFloat()
        x to y
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
            .alpha(opacity)
            .onSizeChanged { railHeight = it.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val height = size.height.toFloat()
                    val span = (height - thumbHeightPx).coerceAtLeast(1f)
                    val top = positionNow.value * span
                    if (down.position.y < top - grabSlackPx ||
                        down.position.y > top + thumbHeightPx + grabSlackPx
                    ) {
                        return@awaitEachGesture
                    }
                    grabbed = true
                    railFraction = positionNow.value
                    monthChoice = -1
                    down.consume()
                    var lastSlot = scaleNow.value.slotAt(railFraction)
                    var lastMonth = -1

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        change.consume()
                        val current = scaleNow.value
                        val centre = arcCentreFor(
                            thumbCentre = railFraction * span + thumbHeightPx / 2f,
                            height = height,
                            margin = arcHeightPx / 2f,
                        )
                        val dx = change.position.x - size.width
                        val dy = change.position.y - centre
                        val months = current.years.getOrNull(current.slotAt(railFraction))?.months.orEmpty()
                        val inArc = monthsOpenNow.value && months.size > 1 &&
                            dx < 0f && hypot(dx, dy) > arcEnterPx

                        if (inArc) {
                            // The month closest to the finger, and not the closest angle: the fan is
                            // flattened, and with different radii on the two axes distance is the only
                            // measure that does not lie.
                            val offsets = monthOffsets(months.size)
                            val nearest = offsets.indices.minByOrNull { index ->
                                val (x, y) = offsets[index]
                                hypot(dx - x, dy - y)
                            } ?: -1
                            if (nearest != lastMonth) {
                                lastMonth = nearest
                                monthChoice = nearest
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                months.getOrNull(nearest)?.let { scrub.value(it.index) }
                            }
                        } else {
                            if (lastMonth != -1) {
                                lastMonth = -1
                                monthChoice = -1
                            }
                            railFraction = ((change.position.y - thumbHeightPx / 2f) / span)
                                .coerceIn(0f, 1f)
                            val slot = current.slotAt(railFraction)
                            if (slot != lastSlot) {
                                lastSlot = slot
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            scrub.value(current.indexAt(railFraction))
                        }
                    } while (event.changes.any { it.pressed })

                    // Releasing over a month confirms it. The jump has already happened; what is left
                    // is telling the hand that made it.
                    if (monthChoice >= 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    grabbed = false
                    monthChoice = -1
                }
            },
    ) {
        // The ruler: one year per step, with the labels that fit. Only while the handle is grabbed —
        // twenty-four permanent years in the right corner were more clutter than help for someone
        // just scrolling.
        if (grabbed && scale.years.isNotEmpty() && travel > 0f) {
            var lastLabel = Float.NEGATIVE_INFINITY
            scale.years.forEachIndexed { index, yearSlot ->
                val y = (index + 0.5f) / scale.years.size * travel + thumbHeightPx / 2f
                if (y - lastLabel >= markerGapPx) {
                    lastLabel = y
                    val current = index == slot
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .graphicsLayer {
                                translationY = y - size.height / 2f
                                // The year under the finger grows a little. It grows into the screen,
                                // not out of it: anchored to the right, or it would touch the margin
                                // and be cut off.
                                val enlarged = if (current) 1.2f else 1f
                                scaleX = enlarged
                                scaleY = enlarged
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            }
                            .padding(end = RAIL_WIDTH)
                            .wrapContentWidth(Alignment.End, unbounded = true)
                            .clip(RoundedCornerShape(ImagoRadii.Pill))
                            .background(Color.Black.copy(alpha = if (current) 0.70f else 0.45f))
                            .padding(horizontal = ImagoSpacing.Sm, vertical = 2.dp),
                    ) {
                        Text(
                            text = yearSlot.year.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (current) ImagoColors.BrandWhite else ImagoColors.TextTertiary,
                        )
                    }
                }
            }
        }

        if (grabbed) {
            val months = year?.months.orEmpty()
            if (months.size > 1 && arcOpacity > 0f) {
                val offsets = monthOffsets(months.size)
                months.forEachIndexed { index, month ->
                    val (x, y) = offsets[index]
                    val chosen = index == monthChoice
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .graphicsLayer {
                                // The fan is anchored to the strip's right corner; `x` is negative, so
                                // it grows into the screen.
                                translationX = x + size.width / 2f
                                translationY = arcCentre + y - size.height / 2f
                                // The fan comes in growing from where it will stay; the chosen one is a
                                // little larger than the others, and that alone marks it.
                                val enlarged = (if (chosen) 1.2f else 1f) * (0.85f + 0.15f * arcOpacity)
                                scaleX = enlarged
                                scaleY = enlarged
                                alpha = arcOpacity
                            }
                            .size(MONTH_CHIP)
                            .shadow(if (chosen) 8.dp else 3.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                if (chosen) ImagoColors.BrandWhite else Color.Black.copy(alpha = 0.72f),
                            )
                            .border(
                                width = 1.dp,
                                color = if (chosen) Color.Transparent else ImagoColors.BorderVisible,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = month.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (chosen) ImagoColors.BrandBlack else ImagoColors.TextSecondary,
                        )
                    }
                }
            }

            // The bubble only says the year, even with a month chosen: the month is already said by the
            // ball that grew under the finger, and a bubble wide enough for "Mar 2019" would hit the
            // fan in the middle of the screen. Without years — sliced view — the date is left.
            val label = if (year != null) year.year.toString() else dateAt(scale.indexAt(fraction))
            if (!label.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .graphicsLayer { translationY = thumbCentre - size.height / 2f }
                        .padding(end = RAIL_WIDTH)
                        .wrapContentWidth(Alignment.End, unbounded = true)
                        .shadow(8.dp, RoundedCornerShape(ImagoRadii.Pill))
                        .clip(RoundedCornerShape(ImagoRadii.Pill))
                        .background(ImagoColors.Charcoal)
                        .border(1.dp, ImagoColors.BorderVisible, RoundedCornerShape(ImagoRadii.Pill))
                        .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Md),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = ImagoColors.TextPrimary,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer { translationY = thumbTop }
                .padding(end = ImagoSpacing.Md)
                .width(THUMB_WIDTH)
                .height(THUMB_HEIGHT)
                // The shadow is what separates it from the photo: over a light sky, a white handle
                // without an outline disappeared.
                .shadow(6.dp, RoundedCornerShape(ImagoRadii.Pill))
                .clip(RoundedCornerShape(ImagoRadii.Pill))
                .background(if (grabbed) ImagoColors.BrandWhite else ImagoColors.Ivory)
                .border(1.dp, Color.Black.copy(alpha = 0.28f), RoundedCornerShape(ImagoRadii.Pill))
                .semantics { contentDescription = railDescription },
        )
    }
}

/** The centre of the fan, moved back far enough for the months at the ends not to leave the screen. */
private fun arcCentreFor(thumbCentre: Float, height: Float, margin: Float): Float {
    val safe = margin.coerceAtMost(height / 2f)
    return thumbCentre.coerceIn(safe, (height - safe).coerceAtLeast(safe))
}

/** "16 May 2024" from an ISO instant. If the date makes no sense, it returns it raw. */
internal fun formatScrubberDate(value: String): String = runCatching {
    LocalDate.parse(value.take(10))
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
}.getOrDefault(value)
