package eu.studio742.imago.feature.editor

import java.time.format.FormatStyle
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.LocalAppLocale
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoNavBarSurface
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The edit timeline.
 *
 * It shows the path inside out — the most recent at the top — because that is where the user is and
 * where they want to be able to step back. The current entry is highlighted; tapping any other
 * restores it.
 */
@Composable
fun HistoryPanel(
    entries: List<HistoryEntry>,
    currentIndex: Int,
    onRestore: (Int) -> Unit,
    onClearAll: () -> Unit,
    conflicts: List<eu.studio742.imago.core.model.RecipeConflictVersion> = emptyList(),
    onApplyConflict: (Long) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                tint = ImagoColors.TextSecondary,
                modifier = Modifier.size(ImagoSizes.IconDefault),
            )
            Text(
                text = stringResource(Res.string.editor_history),
                style = MaterialTheme.typography.titleMedium,
                color = ImagoColors.TextPrimary,
                modifier = Modifier.weight(1f).padding(start = ImagoSpacing.Sm),
            )
            TextButton(onClick = onClearAll, enabled = entries.size > 1) {
                Text(stringResource(Res.string.editor_clear_all), color = ImagoColors.Danger)
            }
        }
        // Other devices' versions stay at the top, apart from the undo path.
        conflicts.forEach { version ->
            ConflictRow(version, onClick = { onApplyConflict(version.id) })
        }
        if (entries.size <= 1) {
            Box(
                Modifier.fillMaxSize().padding(ImagoSpacing.Xl),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    stringResource(Res.string.editor_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.TextTertiary,
                )
            }
            return@Column
        }
        // From the most recent to the oldest. `reversed()` keeps the original index in `entries`, which
        // is what `onRestore` needs to receive.
        val ordered = entries.indices.reversed()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = ImagoSpacing.Lg,
                end = ImagoSpacing.Lg,
                bottom = ImagoSpacing.Lg,
            ),
        ) {
            items(ordered.toList(), key = { entries[it].id }) { index ->
                HistoryRow(
                    entry = entries[index],
                    isCurrent = index == currentIndex,
                    // After the current point, what is left is the future redo would bring back.
                    isUndone = index > currentIndex,
                    isFirst = index == entries.lastIndex,
                    isLast = index == 0,
                    onClick = { onRestore(index) },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    isCurrent: Boolean,
    isUndone: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = when {
        isCurrent -> ImagoColors.TextPrimary
        isUndone -> ImagoColors.TextDisabled
        else -> ImagoColors.TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .selectable(selected = isCurrent, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimelineMarker(isCurrent = isCurrent, isUndone = isUndone, isFirst = isFirst, isLast = isLast)
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(ImagoRadii.Medium))
                .background(if (isCurrent) ImagoColors.Charcoal else Color.Transparent)
                .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = entry.icon.vector(),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(ImagoSizes.IconDefault),
            )
            Column(Modifier.weight(1f).padding(start = ImagoSpacing.Md)) {
                Text(
                    text = entry.label.resolve(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.detail.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUndone) ImagoColors.TextDisabled else ImagoColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                entry.valueText?.let {
                    Text(
                        text = it.resolve(),
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                    )
                }
                Text(
                    text = formatHistoryTime(entry.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUndone) ImagoColors.TextDisabled else ImagoColors.TextTertiary,
                )
            }
        }
    }
}

/** "Version from Galaxy Tab · 12 Sep, 18:40": tapping applies it as a new step. */
@Composable
private fun ConflictRow(version: eu.studio742.imago.core.model.RecipeConflictVersion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ImagoSpacing.Lg, vertical = 3.dp)
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.Charcoal)
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Devices, contentDescription = null, tint = ImagoColors.TextSecondary, modifier = Modifier.size(ImagoSizes.IconDefault))
        Column(Modifier.weight(1f).padding(start = ImagoSpacing.Md)) {
            Text(
                stringResource(Res.string.editor_conflict_version, version.deviceName, formatHistoryTime(version.editedAt)),
                style = MaterialTheme.typography.bodyLarge,
                color = ImagoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(Res.string.editor_conflict_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
            )
        }
    }
}

/** The dot and the line that connect the entries. Purely decorative — the whole row is a target. */
@Composable
private fun TimelineMarker(isCurrent: Boolean, isUndone: Boolean, isFirst: Boolean, isLast: Boolean) {
    val color = when {
        isCurrent -> ImagoColors.BrandWhite
        isUndone -> ImagoColors.TextDisabled
        else -> ImagoColors.TextTertiary
    }
    Canvas(
        Modifier
            .width(28.dp)
            .height(ImagoSizes.TouchTarget + ImagoSpacing.Sm),
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        // The line does not go past the top of the first entry or the bottom of the last: it would stop in the void.
        if (!isLast) {
            drawLine(
                color = ImagoColors.BorderVisible,
                start = Offset(centerX, 0f),
                end = Offset(centerX, centerY),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (!isFirst) {
            drawLine(
                color = ImagoColors.BorderVisible,
                start = Offset(centerX, centerY),
                end = Offset(centerX, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (isCurrent) {
            drawCircle(color = color, radius = 5.dp.toPx(), center = Offset(centerX, centerY))
        } else {
            drawCircle(
                color = color,
                radius = 4.dp.toPx(),
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

private fun HistoryIcon.vector(): ImageVector = when (this) {
    HistoryIcon.ORIGINAL -> Icons.Outlined.Photo
    HistoryIcon.EXPOSURE -> Icons.Outlined.WbSunny
    HistoryIcon.CONTRAST -> Icons.Outlined.Contrast
    HistoryIcon.HIGHLIGHTS -> Icons.Outlined.Landscape
    HistoryIcon.SHADOWS -> Icons.Outlined.Brightness4
    HistoryIcon.WHITES -> Icons.Outlined.Circle
    HistoryIcon.BLACKS -> Icons.Outlined.Brightness6
    HistoryIcon.COLOR -> Icons.Outlined.Palette
    HistoryIcon.CURVE -> Icons.Outlined.Timeline
    HistoryIcon.HSL -> Icons.Filled.Circle
    HistoryIcon.GRADE -> Icons.Outlined.ColorLens
    HistoryIcon.DETAIL -> Icons.Outlined.Tune
    HistoryIcon.EFFECTS -> Icons.Outlined.Grain
    HistoryIcon.CROP -> Icons.Outlined.Crop
    HistoryIcon.RECIPE -> Icons.Outlined.AutoAwesome
    HistoryIcon.MASK -> Icons.Outlined.Adjust
}

/** "Today, 18:42" while it is today; from then on, the day too. The day and the time come from the language. */
@Composable
private fun formatHistoryTime(value: String): String {
    val today = stringResource(Res.string.editor_today_at, "%s")
    val datePattern = stringResource(Res.string.editor_history_date_pattern)
    val locale = LocalAppLocale.current
    return runCatching {
        val instant = runCatching { Instant.parse(value) }.getOrElse { java.time.OffsetDateTime.parse(value).toInstant() }
        val time = instant.atZone(ZoneId.systemDefault())
        val isToday = Duration.between(time.toLocalDate().atStartOfDay(ZoneId.systemDefault()), Instant.now().atZone(ZoneId.systemDefault()))
            .toDays() == 0L
        val clock = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        if (isToday) today.format(clock) else "${time.format(DateTimeFormatter.ofPattern(datePattern, locale))}, $clock"
    }.getOrDefault("")
}

/**
 * The header of a tool with its own interface.
 *
 * The back arrow is what guarantees these modes are never a dead end: one entered from inside a
 * category, and leaves to the same one.
 */
@Composable
fun SubToolHeader(
    title: String,
    onClose: () -> Unit,
    onReset: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(ImagoRadii.Small))
                .clickable(role = Role.Button, onClick = onClose)
                .sizeIn(minHeight = ImagoSizes.TouchTarget)
                .padding(end = ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.editor_back_to_adjustments),
                tint = ImagoColors.TextSecondary,
                modifier = Modifier.size(ImagoSizes.IconDefault),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = ImagoColors.TextPrimary,
                modifier = Modifier.padding(start = ImagoSpacing.Sm),
            )
        }
        Box(Modifier.weight(1f))
        onReset?.let { TextButton(onClick = it) { Text(stringResource(Res.string.editor_reset)) } }
    }
}

/**
 * The editor's toolbar.
 *
 * It holds the tools that open a panel — and the recipes, which open their library. It is always in
 * view, with nothing selected until a tool is opened; tapping the open tool again closes its panel and
 * leaves the bar. What used to sit here as well had a place of its own already: compare is at the top
 * and under a long press on the photo, copy and paste in the menu, saving a recipe in the library,
 * and export — which is not a tool — went up to the top bar.
 */
@Composable
fun EditorToolBar(
    selected: EditorTool?,
    onSelect: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
    tools: List<EditorTool> = editorTools(recipeMode = false),
) {
    ImagoNavBarSurface(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ImagoSpacing.Sm, vertical = ImagoSpacing.Sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tools.forEach { tool ->
                EditorToolButton(
                    icon = tool.vector(),
                    label = tool.label(),
                    selected = selected == tool,
                    enabled = true,
                    onClick = { onSelect(tool) },
                )
            }
        }
    }
}

@Composable
private fun EditorToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        !enabled -> ImagoColors.TextDisabled
        selected -> ImagoColors.TextPrimary
        else -> ImagoColors.TextTertiary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            // The filled capsule is what marks the active tool in the mockup — stronger than a
            // difference in opacity over an arbitrary photo.
            .background(if (selected) ImagoColors.Graphite else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            // In a sliding row each button measures its own label: none is squeezed to fit beside the
            // others, and "History" no longer decides everyone's width.
            .sizeIn(minWidth = ImagoSizes.TouchTarget, minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ImagoSizes.IconLarge),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = ImagoSpacing.Xs),
        )
    }
}

@Composable
private fun EditorTool.label() = when (this) {
    EditorTool.ADJUSTMENTS -> stringResource(Res.string.editor_tool_adjust)
    EditorTool.CROP -> stringResource(Res.string.editor_tool_crop)
    EditorTool.RECIPES -> stringResource(Res.string.editor_tool_recipes)
    EditorTool.HISTORY -> stringResource(Res.string.editor_history)
}

private fun EditorTool.vector(): ImageVector = when (this) {
    EditorTool.ADJUSTMENTS -> Icons.Outlined.Tune
    EditorTool.CROP -> Icons.Outlined.Crop
    EditorTool.RECIPES -> Icons.Outlined.AutoAwesome
    EditorTool.HISTORY -> Icons.Outlined.History
}
