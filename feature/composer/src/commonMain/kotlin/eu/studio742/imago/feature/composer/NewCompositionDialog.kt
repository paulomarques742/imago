package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.studio742.imago.core.composition.BuiltInLayout
import eu.studio742.imago.core.composition.LayoutSlot
import eu.studio742.imago.core.composition.MAX_PAGE_SIDE
import eu.studio742.imago.core.composition.MIN_PAGE_SIDE
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.composition.PageFormatCategory
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.composition.SlotKind
import eu.studio742.imago.core.composition.slots
import eu.studio742.imago.core.designsystem.ImagoChipRow
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing

/**
 * Where a composition starts.
 *
 * It used to be two text prompts in a row — choosing "Story / Reel 9:16" and then "Hero + two"
 * without seeing either. They are decisions about shapes, and a list of names forces imagining them:
 * what is shown here is the page's shape and the drawing of the slots inside it.
 *
 * On a single screen, and not in two steps, because the two choices depend on each other — a 2 × 2
 * grid on a 9:16 is not the same as on a square, and changing the format redraws the layouts in front
 * of whoever is choosing.
 */
@Composable
internal fun NewCompositionDialog(
    onDismiss: () -> Unit,
    onCreate: (PageFormat, BuiltInLayout) -> Unit,
) {
    var format by remember { mutableStateOf(PageFormat.Default) }
    var tab by remember { mutableStateOf(FormatTab.SOCIAL) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ImagoColors.Surface,
            shape = RoundedCornerShape(ImagoRadii.Panel),
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(vertical = ImagoSpacing.Xl)) {
                Text(
                    text = stringResource(Res.string.composer_new_composition),
                    style = MaterialTheme.typography.titleMedium,
                    color = ImagoColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = ImagoSpacing.Xl),
                )
                Text(
                    text = stringResource(Res.string.composer_format),
                    style = MaterialTheme.typography.labelMedium,
                    color = ImagoColors.TextTertiary,
                    modifier = Modifier.padding(
                        start = ImagoSpacing.Xl,
                        end = ImagoSpacing.Xl,
                        top = ImagoSpacing.Lg,
                        bottom = ImagoSpacing.Sm,
                    ),
                )
                // Two rows instead of one: seventeen formats in a single row were an endless corridor
                // where A4 was three seconds of scrolling away from Story. The family first, its sizes
                // next — and "Custom" is a family like the others, so the custom size is not hidden at
                // the end of the list.
                ImagoChipRow(
                    options = FormatTab.entries,
                    selected = tab,
                    label = { stringResource(it.label) },
                    onSelect = { chosen ->
                        tab = chosen
                        chosen.category?.let { format = PageFormatPreset.of(it).first().format }
                    },
                    contentPadding = ImagoSpacing.Xl,
                )
                val category = tab.category
                if (category != null) {
                    ImagoChipRow(
                        options = PageFormatPreset.of(category),
                        selected = PageFormatPreset.matching(format) ?: PageFormatPreset.of(category).first(),
                        label = { stringResource(it.labelRes()) },
                        onSelect = { format = it.format },
                        contentPadding = ImagoSpacing.Xl,
                        modifier = Modifier.padding(top = ImagoSpacing.Sm),
                    )
                    Text(
                        text = "${format.pixelWidth} × ${format.pixelHeight} px",
                        style = MaterialTheme.typography.bodySmall,
                        color = ImagoColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = ImagoSpacing.Xl, vertical = ImagoSpacing.Xs),
                    )
                } else {
                    CustomFormatFields(format = format, onFormat = { format = it })
                }
                Text(
                    text = stringResource(Res.string.composer_initial_layout),
                    style = MaterialTheme.typography.labelMedium,
                    color = ImagoColors.TextTertiary,
                    modifier = Modifier.padding(
                        start = ImagoSpacing.Xl,
                        end = ImagoSpacing.Xl,
                        top = ImagoSpacing.Lg,
                        bottom = ImagoSpacing.Sm,
                    ),
                )
                // The limit is so the dialog does not grow past the screen on a phone: past it, the grid
                // scrolls inside instead of pushing "Cancel" off.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 260.dp),
                    contentPadding = PaddingValues(horizontal = ImagoSpacing.Xl),
                    horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                ) {
                    items(BuiltInLayout.entries, key = BuiltInLayout::name) { layout ->
                        LayoutTile(
                            layout = layout,
                            format = format,
                            onClick = { onCreate(format, layout) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        start = ImagoSpacing.Md,
                        end = ImagoSpacing.Md,
                        top = ImagoSpacing.Sm,
                    ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_cancel)) }
                }
            }
        }
    }
}

/**
 * The format families, plus the one with no ready-made sizes.
 *
 * It lives here and not in the model because it is a division of the screen: `PageFormatCategory`
 * arranges the presets, and "Custom" is not a preset — it is the absence of one.
 */
private enum class FormatTab(val label: StringResource, val category: PageFormatCategory?) {
    SOCIAL(PageFormatCategory.SOCIAL.labelRes(), PageFormatCategory.SOCIAL),
    PHOTO(PageFormatCategory.PHOTO.labelRes(), PageFormatCategory.PHOTO),
    EDITORIAL(PageFormatCategory.EDITORIAL.labelRes(), PageFormatCategory.EDITORIAL),
    CUSTOM(Res.string.composer_custom, null),
}

/**
 * Width and height by hand.
 *
 * The fields are born with the measurement of the format that was chosen, and not empty: whoever comes
 * from an "A4 portrait" to swap the orientation types two numbers that are almost all there already,
 * instead of inventing them from scratch. Whatever is outside the limits does not touch the format —
 * the page stays the last valid one, and the notice below says why, so the create button is never
 * stuck without explanation.
 */
@Composable
private fun CustomFormatFields(format: PageFormat, onFormat: (PageFormat) -> Unit) {
    var width by remember { mutableStateOf(format.pixelWidth.toString()) }
    var height by remember { mutableStateOf(format.pixelHeight.toString()) }
    fun publish() {
        val w = width.toIntOrNull() ?: return
        val h = height.toIntOrNull() ?: return
        if (w in MIN_PAGE_SIDE..MAX_PAGE_SIDE && h in MIN_PAGE_SIDE..MAX_PAGE_SIDE) onFormat(PageFormat.custom(w, h))
    }
    Column(Modifier.padding(horizontal = ImagoSpacing.Xl, vertical = ImagoSpacing.Sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            OutlinedTextField(
                value = width,
                onValueChange = { width = it.filter(Char::isDigit).take(4); publish() },
                label = { Text(stringResource(Res.string.composer_width)) },
                suffix = { Text("px") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = height,
                onValueChange = { height = it.filter(Char::isDigit).take(4); publish() },
                label = { Text(stringResource(Res.string.composer_height)) },
                suffix = { Text("px") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(Res.string.composer_custom_size_hint, MIN_PAGE_SIDE, MAX_PAGE_SIDE, format.pixelWidth, format.pixelHeight),
            style = MaterialTheme.typography.bodySmall,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.padding(top = ImagoSpacing.Xs),
        )
    }
}

@Composable
private fun LayoutTile(layout: BuiltInLayout, format: PageFormat, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.SurfaceElevated)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(ImagoSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LayoutDiagram(
            layout = layout,
            format = format,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        )
        Text(
            text = stringResource(layout.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = ImagoSpacing.Xs),
        )
    }
}

/**
 * The drawn layout: the page in its format, and the boxes where the media will land.
 *
 * Panoramas take more than one page, and are drawn as such — the patch crosses the separations
 * instead of stopping at them, which is exactly what tells a panorama apart from two photos side by
 * side.
 */
@Composable
private fun LayoutDiagram(layout: BuiltInLayout, format: PageFormat, modifier: Modifier) {
    val slots = layout.slots()
    val pages = layout.minimumPages.coerceAtLeast(1)
    val pageAspect = format.aspectRatio
    Canvas(modifier) {
        val totalAspect = pageAspect * pages
        val width: Float
        val height: Float
        if (size.width / size.height > totalAspect) {
            height = size.height
            width = height * totalAspect
        } else {
            width = size.width
            height = width / totalAspect
        }
        val origin = Offset((size.width - width) / 2f, (size.height - height) / 2f)
        val pageWidth = width / pages
        val corner = CornerRadius(3.dp.toPx())

        drawRoundRect(
            color = ImagoColors.Charcoal,
            topLeft = origin,
            size = Size(width, height),
            cornerRadius = corner,
        )
        slots.forEach { slot -> drawSlot(slot, origin, pageWidth, height) }
        // The separations over the media: it is the page's cut, not a frame around the content.
        repeat(pages - 1) { index ->
            val x = origin.x + pageWidth * (index + 1)
            drawRect(
                color = ImagoColors.SurfaceElevated,
                topLeft = Offset(x - 1.dp.toPx(), origin.y),
                size = Size(2.dp.toPx(), height),
            )
        }
        drawRoundRect(
            color = ImagoColors.BorderVisible,
            topLeft = origin,
            size = Size(width, height),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** Text boxes are lines and not patches: at a tenth of the size, that is what reads them as text. */
private fun DrawScope.drawSlot(slot: LayoutSlot, origin: Offset, pageWidth: Float, height: Float) {
    val bounds = slot.bounds
    val topLeft = Offset(origin.x + pageWidth * bounds.x, origin.y + height * bounds.y)
    val size = Size(pageWidth * bounds.width, height * bounds.height)
    when (slot.kind) {
        SlotKind.MEDIA -> drawRoundRect(
            color = ImagoColors.BorderStrong,
            topLeft = topLeft,
            size = size,
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        SlotKind.TEXT -> {
            val lines = 3
            val lineHeight = 1.5f.dp.toPx()
            val gap = (size.height - lines * lineHeight) / (lines - 1).coerceAtLeast(1)
            repeat(lines) { index ->
                // The last line is shorter, like the last line of a paragraph.
                val lineWidth = if (index == lines - 1) size.width * 0.6f else size.width
                drawRect(
                    color = ImagoColors.TextTertiary,
                    topLeft = Offset(topLeft.x, topLeft.y + (lineHeight + gap) * index),
                    size = Size(lineWidth, lineHeight),
                )
            }
        }
    }
}
