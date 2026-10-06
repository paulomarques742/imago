@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionFont
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.toColorHex
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.feature.library.AssetUiModel
import eu.studio742.imago.feature.library.LibraryPickerRoute
import kotlin.math.roundToInt

/** On a tablet or a wide window the editor does not stretch: it is a form column. */
private val BrandEditorMaxWidth = 640.dp
private val PaletteRowHeight = 56.dp
private val LogoRowHeight = 64.dp
private val RowSpacing = 4.dp

private enum class BrandFontSlot(val label: StringResource) {
    PRIMARY(Res.string.composer_brand_primary_font),
    SECONDARY(Res.string.composer_brand_secondary_font),
}

/**
 * The brand editor: the palette, the two fonts and the logos, saved as they change.
 *
 * It lives in the "Brand" tab of the composer's hub, and opens over the compositions editor from the
 * brand panel ([BrandKitOverlay]). Choosing logos is the whole library, which needs the whole screen:
 * that is why whoever hosts the editor is the one who shows it, when [onPickLogos] is called, on top
 * of everything else.
 */
@Composable
internal fun BrandKitEditor(
    viewModel: BrandKitViewModel,
    onPickLogos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kit by viewModel.kit.collectAsStateWithLifecycle()
    var editingColor by remember { mutableStateOf<Int?>(null) }
    var addingColor by remember { mutableStateOf(false) }
    var choosingFont by remember { mutableStateOf<BrandFontSlot?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = BrandEditorMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xxl),
        ) {
            BrandPreview(kit, viewModel)
            PaletteSection(
                kit = kit,
                onEdit = { editingColor = it },
                onAdd = { addingColor = true },
                onRemove = viewModel::removeColor,
                onMove = viewModel::moveColor,
            )
            FontsSection(kit, onChoose = { choosingFont = it })
            LogosSection(kit, viewModel, onAdd = onPickLogos)
            Spacer(Modifier.height(ImagoSpacing.Lg))
        }
    }

    // A colour that stopped existing meanwhile — removed on another device — simply does not open.
    editingColor?.let { index ->
        kit.paletteArgb.getOrNull(index)?.let { argb ->
            ColorPickerDialog(
                title = if (index == 0) stringResource(Res.string.composer_brand_main_color) else stringResource(Res.string.composer_color_n, index + 1),
                initialArgb = argb,
                palette = kit.paletteArgb,
                onDismiss = { editingColor = null },
                onConfirm = { viewModel.setColor(index, it); editingColor = null },
            )
        }
    }
    if (addingColor) {
        ColorPickerDialog(
            title = stringResource(Res.string.composer_new_color),
            initialArgb = kit.paletteArgb.lastOrNull() ?: 0xFFFFFFFF,
            palette = kit.paletteArgb,
            onDismiss = { addingColor = false },
            onConfirm = { viewModel.addColor(it); addingColor = false },
        )
    }
    choosingFont?.let { slot ->
        FontPickerDialog(
            title = stringResource(slot.label),
            selected = CompositionFonts.resolve(if (slot == BrandFontSlot.PRIMARY) kit.primaryFont else kit.secondaryFont),
            onDismiss = { choosingFont = null },
            onPick = { font ->
                if (slot == BrandFontSlot.PRIMARY) viewModel.setPrimaryFont(font.id) else viewModel.setSecondaryFont(font.id)
                choosingFont = null
            },
        )
    }
}

/**
 * The brand editor over the composer, with its own bar and its own "back".
 *
 * On top and not on another screen, like the media picker: the composer stays as it was — the page on
 * stage, the selection, the armed mode — and coming back from the brand is coming back exactly there.
 */
@Composable
internal fun BrandKitOverlay(onClose: () -> Unit) {
    val viewModel = brandKitViewModel()
    var pickingLogos by remember { mutableStateOf(false) }
    // While the library is open it is what hears "back", to close albums and search.
    BackHandler(enabled = !pickingLogos) { onClose() }
    Surface(
        color = ImagoColors.Background,
        modifier = Modifier
            .fillMaxSize()
            // Without something consuming the touches, those landing on empty space hit the composer.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = ImagoSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.composer_back_to_composer), tint = ImagoColors.TextPrimary)
                }
                Text(stringResource(Res.string.composer_brand), style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary)
            }
            BrandKitEditor(viewModel, onPickLogos = { pickingLogos = true }, Modifier.weight(1f))
        }
    }
    if (pickingLogos) {
        BrandLogoPicker(
            onDismiss = { pickingLogos = false },
            onPicked = { viewModel.addLogos(it); pickingLogos = false },
        )
    }
}

/** The whole library, choosing logos: images only, several at once. */
@Composable
internal fun BrandLogoPicker(onDismiss: () -> Unit, onPicked: (List<ComposerMedia>) -> Unit) {
    Surface(
        color = ImagoColors.Background,
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LibraryPickerRoute(
            title = stringResource(Res.string.composer_choose_logos),
            subtitle = stringResource(Res.string.composer_logos_no_videos),
            allowsVideo = false,
            multiple = true,
            onConfirm = { chosen -> onPicked(chosen.map(AssetUiModel::toComposerMedia)) },
            onCancel = onDismiss,
        )
    }
}

/** The brand applied: what the palette, the fonts and the first logo give together. */
@Composable
private fun BrandPreview(kit: BrandKit, viewModel: BrandKitViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Large))
            .background(ImagoColors.SurfaceElevated)
            .border(1.dp, ImagoColors.BorderSubtle, RoundedCornerShape(ImagoRadii.Large))
            .padding(ImagoSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.composer_brand_title_sample),
                    fontFamily = rememberCompositionFontFamily(kit.primaryFont, 700),
                    fontWeight = FontWeight(CompositionFonts.resolve(kit.primaryFont).weight(700)),
                    fontSize = 26.sp,
                    color = ImagoColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.composer_brand_paragraph_sample),
                    fontFamily = rememberCompositionFontFamily(kit.secondaryFont, 400),
                    fontSize = 15.sp,
                    color = ImagoColors.TextSecondary,
                    modifier = Modifier.padding(top = ImagoSpacing.Xs),
                )
            }
            kit.logos.firstOrNull()?.let { logo ->
                LogoThumbnail(logo, viewModel, Modifier.padding(start = ImagoSpacing.Md).size(64.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
            kit.paletteArgb.forEachIndexed { index, argb ->
                Box(
                    Modifier
                        .height(10.dp)
                        // The main one takes more: it is the colour the brand uses most.
                        .weight(if (index == 0) 2f else 1f)
                        .clip(RoundedCornerShape(ImagoRadii.Pill))
                        .background(Color(argb))
                        .border(1.dp, ImagoColors.BorderSubtle, RoundedCornerShape(ImagoRadii.Pill)),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = ImagoSpacing.Sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
    }
}

@Composable
private fun PaletteSection(
    kit: BrandKit,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Column {
        SectionHeader(stringResource(Res.string.composer_palette), stringResource(Res.string.composer_palette_hint))
        ReorderableColumn(kit.paletteArgb, PaletteRowHeight, onMove) { argb, index, dragging, handle ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(PaletteRowHeight)
                    .clip(RoundedCornerShape(ImagoRadii.Medium))
                    .background(if (dragging) ImagoColors.Graphite else ImagoColors.SurfaceElevated)
                    .clickable { onEdit(index) }
                    .padding(start = ImagoSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(argb))
                        .border(1.dp, ImagoColors.BorderVisible, CircleShape),
                )
                Column(Modifier.weight(1f).padding(start = ImagoSpacing.Md)) {
                    Text(
                        if (index == 0) stringResource(Res.string.composer_main) else stringResource(Res.string.composer_color_n, index + 1),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ImagoColors.TextPrimary,
                    )
                    Text(argb.toColorHex(), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
                }
                // The last colour is not removed: a brand has at least the main one.
                if (kit.paletteArgb.size > 1) {
                    IconButton(onClick = { onRemove(index) }, Modifier.size(ImagoSizes.TouchTarget)) {
                        Icon(Icons.Outlined.Delete, stringResource(Res.string.composer_remove_color), Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.TextSecondary)
                    }
                }
                DragHandleBox(handle, stringResource(Res.string.composer_reorder_color))
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.padding(top = ImagoSpacing.Xs)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(ImagoSizes.IconSmall))
            Text(stringResource(Res.string.composer_add_color), Modifier.padding(start = ImagoSpacing.Sm))
        }
    }
}

@Composable
private fun FontsSection(kit: BrandKit, onChoose: (BrandFontSlot) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(RowSpacing)) {
        SectionHeader(stringResource(Res.string.composer_fonts), stringResource(Res.string.composer_fonts_hint))
        FontSlotRow(BrandFontSlot.PRIMARY, CompositionFonts.resolve(kit.primaryFont)) { onChoose(BrandFontSlot.PRIMARY) }
        FontSlotRow(BrandFontSlot.SECONDARY, CompositionFonts.resolve(kit.secondaryFont)) { onChoose(BrandFontSlot.SECONDARY) }
    }
}

@Composable
private fun FontSlotRow(slot: BrandFontSlot, font: CompositionFont, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(slot.label), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
            Text(
                font.displayName,
                fontFamily = rememberCompositionFontFamily(font.id, 400),
                fontSize = 22.sp,
                color = ImagoColors.TextPrimary,
            )
        }
        Text(font.category.displayName(), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = ImagoSpacing.Sm), tint = ImagoColors.TextTertiary)
    }
}

@Composable
private fun LogosSection(kit: BrandKit, viewModel: BrandKitViewModel, onAdd: () -> Unit) {
    Column {
        SectionHeader(stringResource(Res.string.composer_logos), stringResource(Res.string.composer_logos_hint))
        if (kit.logos.isEmpty()) {
            Text(
                stringResource(Res.string.composer_no_logos),
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoColors.TextSecondary,
                modifier = Modifier.padding(vertical = ImagoSpacing.Sm),
            )
        } else {
            ReorderableColumn(kit.logos, LogoRowHeight, viewModel::moveLogo) { logo, _, dragging, handle ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(LogoRowHeight)
                        .clip(RoundedCornerShape(ImagoRadii.Medium))
                        .background(if (dragging) ImagoColors.Graphite else ImagoColors.SurfaceElevated)
                        .padding(start = ImagoSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogoThumbnail(logo, viewModel, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(start = ImagoSpacing.Md)) {
                        Text(
                            logo.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ImagoColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (logo.width != null && logo.height != null) {
                            Text("${logo.width} × ${logo.height}", style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
                        }
                    }
                    IconButton(onClick = { viewModel.removeLogo(logo.assetId) }, Modifier.size(ImagoSizes.TouchTarget)) {
                        Icon(Icons.Outlined.Delete, stringResource(Res.string.composer_remove_logo), Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.TextSecondary)
                    }
                    DragHandleBox(handle, stringResource(Res.string.composer_reorder_logo))
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.padding(top = ImagoSpacing.Sm)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(ImagoSizes.IconSmall))
            Text(if (kit.logos.isEmpty()) stringResource(Res.string.composer_choose_logos) else stringResource(Res.string.composer_add_logos), Modifier.padding(start = ImagoSpacing.Sm))
        }
    }
}

@Composable
private fun LogoThumbnail(logo: MediaReference, viewModel: BrandKitViewModel, modifier: Modifier) {
    AsyncImage(
        ImageRequest.Builder(LocalPlatformContext.current)
            .data(viewModel.thumbnailUrl(logo.assetId))
            .libraryAuth(viewModel.apiKey(logo.assetId))
            .build(),
        contentDescription = logo.fileName,
        // A logo is shown whole: cropping it to fill the square hid half the brand.
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .background(ImagoColors.Charcoal),
    )
}

@Composable
private fun DragHandleBox(handle: Modifier, label: String) {
    Box(
        handle.size(ImagoSizes.TouchTarget).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragHandle, null, Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.TextTertiary)
    }
}

/**
 * A short list reordered by dragging the handle, like the composer's layers.
 *
 * With one difference: the order only changes here while the finger is down, and [onMove] is called
 * once, on release. In the layers every row crossed is a change to the project; here it would be a
 * brand save — and an upload to the account — per row.
 *
 * The rows are all [rowHeight] tall: that is how the dragged distance converts into positions.
 */
@Composable
private fun <T> ReorderableColumn(
    items: List<T>,
    rowHeight: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    row: @Composable (item: T, index: Int, dragging: Boolean, handle: Modifier) -> Unit,
) {
    // Each entry keeps its original position: it is the row's identity during the drag, and the `from`
    // to hand over at the end. Two equal colours in the palette are still two rows.
    var order by remember(items) { mutableStateOf(items.withIndex().toList()) }
    var dragged by remember(items) { mutableStateOf<Int?>(null) }
    var offset by remember(items) { mutableStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (rowHeight + RowSpacing).toPx() }
    val move by rememberUpdatedState(onMove)

    fun drag(delta: Float) {
        val original = dragged ?: return
        offset += delta
        val position = order.indexOfFirst { it.index == original }
        val target = (position + (offset / stepPx).roundToInt()).coerceIn(0, order.lastIndex)
        if (target != position) {
            order = order.moved(position, target)
            offset -= (target - position) * stepPx
        }
    }

    fun finish() {
        val original = dragged ?: return
        val position = order.indexOfFirst { it.index == original }
        dragged = null
        offset = 0f
        if (position != original) move(original, position)
    }

    Column(verticalArrangement = Arrangement.spacedBy(RowSpacing)) {
        order.forEachIndexed { position, entry ->
            key(entry.index) {
                val dragging = entry.index == dragged
                // Keyed on the list too: when it changes the state above is another, and a gesture still
                // holding the old one left the second drag without effect.
                val handle = Modifier.pointerInput(entry.index, items) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragged = entry.index; offset = 0f },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    ) { change, delta -> change.consume(); drag(delta.y) }
                }
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .offset { IntOffset(0, if (dragging) offset.roundToInt() else 0) },
                ) {
                    row(entry.value, position, dragging, handle)
                }
            }
        }
    }
}
