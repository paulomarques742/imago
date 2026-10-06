package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.studio742.imago.core.composition.CompositionFont
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.FontCategory
import eu.studio742.imago.core.composition.weightName
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing

/** With accents and cedilla on purpose: it is the text that shows whether the font works for Portuguese. */
@Composable
internal fun fontSample(): String = stringResource(Res.string.composer_font_sample)

/**
 * The whole catalogue, each font written in itself, by category.
 *
 * It serves the brand editor and the composer's text. [sample] is each row's sample; the composer
 * passes the text itself, because it is easier to choose a font looking at the words that will be
 * used than at a sample sentence. [featured] goes at the top, in a section of its own: in the composer
 * they are the brand fonts, which are the ones chosen most.
 */
@Composable
internal fun FontPickerDialog(
    title: String,
    selected: CompositionFont,
    onDismiss: () -> Unit,
    onPick: (CompositionFont) -> Unit,
    sample: String = fontSample(),
    featured: List<Pair<String, CompositionFont>> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                if (featured.isNotEmpty()) {
                    item(key = "featured") { FontSectionHeader(stringResource(Res.string.composer_from_brand)) }
                    items(featured, key = { "featured-${it.first}" }) { (label, font) ->
                        FontRow(font, font == selected, sample, detail = label) { onPick(font) }
                    }
                }
                FontCategory.entries.forEach { category ->
                    val fonts = CompositionFonts.all.filter { it.category == category }
                    if (fonts.isEmpty()) return@forEach
                    item(key = "category-${category.name}") { FontSectionHeader(category.displayName()) }
                    items(fonts, key = CompositionFont::id) { font ->
                        FontRow(font, font == selected, sample, detail = font.weightsLabel()) { onPick(font) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_close)) } },
    )
}

@Composable
private fun FontSectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = ImagoColors.Gold,
        modifier = Modifier.padding(
            top = ImagoSpacing.Md,
            bottom = ImagoSpacing.Xs,
        ),
    )
}

@Composable
private fun FontRow(font: CompositionFont, chosen: Boolean, sample: String, detail: String, onClick: () -> Unit) {
    val family = rememberCompositionFontFamily(font.id, 400)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .background(if (chosen) ImagoColors.Gold.copy(alpha = .12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = ImagoSpacing.Sm, vertical = ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(font.displayName, fontFamily = family, fontSize = 20.sp, color = ImagoColors.TextPrimary, modifier = Modifier.weight(1f, fill = false))
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = ImagoColors.TextTertiary,
                    modifier = Modifier.padding(start = ImagoSpacing.Sm),
                )
            }
            Text(sample, fontFamily = family, fontSize = 14.sp, color = ImagoColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chosen) Icon(Icons.Outlined.Check, stringResource(Res.string.composer_chosen), tint = ImagoColors.Gold)
    }
}

/** A family's weights, said as they read in a font catalogue. */
private fun CompositionFont.weightsLabel(): String =
    if (isVariable) "${weightName(weights.first)}–${weightName(weights.last)}" else weightName(weights.first)
