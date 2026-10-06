package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.asUiText
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.KeyboardCapslock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionFont
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.MAX_TEXT_BACKGROUND_CORNER
import eu.studio742.imago.core.composition.MAX_TEXT_BACKGROUND_PADDING
import eu.studio742.imago.core.composition.MAX_TEXT_LETTER_SPACING
import eu.studio742.imago.core.composition.MAX_TEXT_LINE_SPACING
import eu.studio742.imago.core.composition.MAX_TEXT_SHADOW_OFFSET
import eu.studio742.imago.core.composition.MAX_TEXT_SHADOW_RADIUS
import eu.studio742.imago.core.composition.MAX_TEXT_STROKE_WIDTH
import eu.studio742.imago.core.composition.MIN_TEXT_LETTER_SPACING
import eu.studio742.imago.core.composition.MIN_TEXT_LINE_SPACING
import eu.studio742.imago.core.composition.MIN_TEXT_STROKE_WIDTH
import eu.studio742.imago.core.composition.TextAlignment
import eu.studio742.imago.core.composition.offeredWeights
import eu.studio742.imago.core.composition.weightName
import eu.studio742.imago.core.designsystem.ImagoChipRow
import eu.studio742.imago.core.designsystem.ImagoColors
import kotlin.math.abs

/**
 * A text's controls in the adjustments drawer: the alignment, the weight, the size, the spacings and
 * the three effects.
 *
 * Letter spacing appears in thousandths of an em, and the outline thickness as a percentage of the
 * font size. They are the units the model keeps both in, only as whole numbers, which read better
 * next to a slider than 0.05.
 */
@Composable
internal fun TextStyleControls(
    text: CompositionElement.Text,
    actions: ComposerEditorViewModel,
    rail: Boolean,
    onPickColor: (PendingColor) -> Unit,
) {
    // While a value is being dragged only it stays on scene; the rest of these controls is chrome and
    // leaves with the panel.
    val chrome = inspectorChromeAlpha(rail)
    Column {
        Row(
            Modifier.fillMaxWidth().alpha(chrome).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(stringResource(Res.string.composer_align_text_left), Icons.AutoMirrored.Outlined.FormatAlignLeft, active = text.alignment == TextAlignment.START) {
                actions.setTextAlignment(TextAlignment.START)
            }
            ToolButton(stringResource(Res.string.composer_align_text_center), Icons.Outlined.FormatAlignCenter, active = text.alignment == TextAlignment.CENTER) {
                actions.setTextAlignment(TextAlignment.CENTER)
            }
            ToolButton(stringResource(Res.string.composer_align_text_right), Icons.AutoMirrored.Outlined.FormatAlignRight, active = text.alignment == TextAlignment.END) {
                actions.setTextAlignment(TextAlignment.END)
            }
            ToolButton(stringResource(Res.string.composer_all_caps), Icons.Outlined.KeyboardCapslock, active = text.allCaps, onClick = actions::toggleTextAllCaps)
        }
        // The weights only appear when there is something to choose: Bebas Neue and Great Vibes have a single one.
        val weights = CompositionFonts.resolve(text.fontFamily).offeredWeights
        if (weights.size > 1) {
            ImagoChipRow(
                options = weights,
                selected = weights.minBy { abs(it - text.fontWeight) },
                label = { weightName(it) },
                onSelect = actions::setTextWeight,
                contentPadding = 0.dp,
                modifier = Modifier.alpha(chrome).padding(vertical = 4.dp),
            )
        }
        InspectorSlider(stringResource(Res.string.composer_size), text.fontSize, MIN_TEXT_SIZE..MAX_TEXT_SIZE, DEFAULT_TEXT_SIZE, actions, actions::setTextSize)
        InspectorSlider(
            stringResource(Res.string.composer_tracking), text.letterSpacing * 1000f, MIN_TEXT_LETTER_SPACING * 1000f..MAX_TEXT_LETTER_SPACING * 1000f, 0f, actions,
        ) { actions.setTextLetterSpacing(it / 1000f) }
        InspectorSlider(stringResource(Res.string.composer_leading), text.lineSpacing, MIN_TEXT_LINE_SPACING..MAX_TEXT_LINE_SPACING, 1f, actions, actions::setTextLineSpacing)

        TextEffect(stringResource(Res.string.composer_outline), stringResource(Res.string.composer_outline_color), text.strokeArgb, contrastingArgb(text.colorArgb), chrome, actions::setTextStroke, onPickColor) {
            InspectorSlider(
                stringResource(Res.string.composer_thickness), text.strokeWidth * 100f, MIN_TEXT_STROKE_WIDTH * 100f..MAX_TEXT_STROKE_WIDTH * 100f, 6f, actions,
            ) { actions.setTextStrokeWidth(it / 100f) }
        }
        TextEffect(stringResource(Res.string.composer_shadow), stringResource(Res.string.composer_shadow_color), text.shadowArgb, 0x99000000, chrome, actions::setTextShadow, onPickColor) {
            InspectorSlider(stringResource(Res.string.composer_blur), text.shadowRadius, 0f..MAX_TEXT_SHADOW_RADIUS, 8f, actions, actions::setTextShadowRadius)
            InspectorSlider(stringResource(Res.string.composer_offset_x), text.shadowOffsetX, -MAX_TEXT_SHADOW_OFFSET..MAX_TEXT_SHADOW_OFFSET, 0f, actions, actions::setTextShadowOffsetX)
            InspectorSlider(stringResource(Res.string.composer_offset_y), text.shadowOffsetY, -MAX_TEXT_SHADOW_OFFSET..MAX_TEXT_SHADOW_OFFSET, 0f, actions, actions::setTextShadowOffsetY)
        }
        TextEffect(
            stringResource(Res.string.composer_background), stringResource(Res.string.composer_background_color), text.backgroundArgb, contrastingArgb(text.colorArgb) and 0x00FFFFFF or 0xCC000000,
            chrome, actions::setTextBackground, onPickColor,
        ) {
            InspectorSlider(stringResource(Res.string.composer_padding), text.backgroundPadding, 0f..MAX_TEXT_BACKGROUND_PADDING, 24f, actions, actions::setTextBackgroundPadding)
            InspectorSlider(stringResource(Res.string.composer_corners), text.backgroundCornerRadius, 0f..MAX_TEXT_BACKGROUND_CORNER, 0f, actions, actions::setTextBackgroundCorner)
        }
    }
}

/**
 * An effect that turns on and off: the switch, the colour when it is on, and its measurements below.
 * Off, the measurements hide but stay saved in the text.
 */
@Composable
private fun TextEffect(
    label: String,
    colorTitle: String,
    argb: Long?,
    defaultArgb: Long,
    chrome: Float,
    onSet: (Long?) -> Unit,
    onPickColor: (PendingColor) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().alpha(chrome).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ImagoColors.TextSecondary, modifier = Modifier.weight(1f))
        if (argb != null) {
            Box(
                Modifier.padding(end = 12.dp).size(28.dp)
                    .background(Color(argb), CircleShape)
                    .border(1.dp, ImagoColors.BorderSubtle, CircleShape)
                    .clickable { onPickColor(PendingColor(colorTitle.asUiText(), argb) { onSet(it) }) }
                    .semantics { contentDescription = colorTitle },
            )
        }
        Switch(
            checked = argb != null,
            onCheckedChange = { onSet(if (it) defaultArgb else null) },
            colors = SwitchDefaults.colors(checkedTrackColor = ImagoColors.Gold),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
    if (argb != null) content()
}

/**
 * A text's font picker: the whole catalogue, with the brand fonts at the top and each row written
 * with the words of the text itself.
 */
@Composable
internal fun TextFontPicker(
    text: CompositionElement.Text,
    brandKit: BrandKit?,
    onDismiss: () -> Unit,
    onPick: (CompositionFont) -> Unit,
) {
    val sample = text.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: fontSample()
    val featured = brandKit?.let {
        listOf(stringResource(Res.string.composer_main) to CompositionFonts.resolve(it.primaryFont), stringResource(Res.string.composer_secondary) to CompositionFonts.resolve(it.secondaryFont))
    }.orEmpty()
    FontPickerDialog(
        title = stringResource(Res.string.composer_font),
        selected = CompositionFonts.resolve(text.fontFamily),
        onDismiss = onDismiss,
        onPick = onPick,
        sample = sample,
        featured = featured,
    )
}

/** Black over a light letter, white over a dark one: the outline and the background have to show. */
internal fun contrastingArgb(argb: Long): Long {
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return if (luma > 140) 0xFF000000 else 0xFFFFFFFF
}
