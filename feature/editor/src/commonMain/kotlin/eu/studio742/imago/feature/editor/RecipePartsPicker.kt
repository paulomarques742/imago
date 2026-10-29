package eu.studio742.imago.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The last choice of parts made on this device, or the look alone before there was one. */
internal fun ConfigurationRepository.rememberedRecipeParts(): Set<RecipePart> =
    recipePartsChoice?.let { RecipePart.parse(it.split(',')) } ?: RecipePart.LOOK

internal fun ConfigurationRepository.rememberRecipeParts(parts: Set<RecipePart>) {
    recipePartsChoice = parts.sorted().joinToString(",") { it.name }
}

/**
 * The parts a recipe ends with once the person confirmed [chosen] for it. A recipe from before the
 * choice stays without one while the choice is still what it already meant.
 */
internal fun SavedRecipe.partsAfterChoosing(chosen: Set<RecipePart>): Set<RecipePart>? =
    if (parts == null && chosen == RecipePart.LEGACY) null else chosen

/** One group of boxes, named and ordered as the editor's panels, so each box is found where its slider is. */
internal class RecipePartGroup(val title: StringResource, val parts: List<Pair<RecipePart, StringResource>>)

internal val RECIPE_PART_GROUPS: List<RecipePartGroup> = listOf(
    RecipePartGroup(
        Res.string.editor_panel_light,
        listOf(
            RecipePart.EXPOSURE to Res.string.editor_adj_exposure,
            RecipePart.CONTRAST to Res.string.editor_adj_contrast,
            RecipePart.HIGHLIGHTS to Res.string.editor_adj_highlights,
            RecipePart.SHADOWS to Res.string.editor_adj_shadows,
            RecipePart.WHITES to Res.string.editor_adj_whites,
            RecipePart.BLACKS to Res.string.editor_adj_blacks,
            RecipePart.TONE_CURVE to Res.string.editor_tone_curve,
        ),
    ),
    RecipePartGroup(
        Res.string.editor_panel_color,
        listOf(
            RecipePart.PROFILE to Res.string.editor_profile,
            RecipePart.WHITE_BALANCE to Res.string.editor_white_balance,
            RecipePart.VIBRANCE to Res.string.editor_adj_vibrance,
            RecipePart.SATURATION to Res.string.editor_adj_saturation,
            RecipePart.COLOR_MIXER to Res.string.editor_color_mixer,
            RecipePart.COLOR_GRADING to Res.string.editor_color_grading,
        ),
    ),
    RecipePartGroup(
        Res.string.editor_panel_detail,
        listOf(
            RecipePart.TEXTURE to Res.string.editor_adj_texture,
            RecipePart.CLARITY to Res.string.editor_adj_clarity,
            RecipePart.DEHAZE to Res.string.editor_adj_dehaze,
        ),
    ),
    RecipePartGroup(
        Res.string.editor_panel_effects,
        listOf(
            RecipePart.VIGNETTE to Res.string.editor_group_vignette,
            RecipePart.GRAIN to Res.string.editor_group_grain,
        ),
    ),
    RecipePartGroup(Res.string.editor_panel_masks, listOf(RecipePart.MASKS to Res.string.editor_panel_masks)),
    RecipePartGroup(Res.string.editor_tool_remove, listOf(RecipePart.REMOVE to Res.string.editor_tool_remove)),
    RecipePartGroup(
        Res.string.editor_part_geometry,
        listOf(
            RecipePart.CROP to Res.string.editor_tool_crop,
            RecipePart.STRAIGHTEN to Res.string.editor_straighten,
            RecipePart.ROTATE_AND_FLIP to Res.string.editor_part_rotate_flip,
            RecipePart.PERSPECTIVE to Res.string.editor_chip_perspective,
        ),
    ),
)

/**
 * Lightroom's boxes: a group ticks or clears everything in it, and opens to tick one slider at a time.
 * It has no scroll of its own — the dialog around it has.
 */
@Composable
internal fun RecipePartsPicker(selected: Set<RecipePart>, onChange: (Set<RecipePart>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        ) {
            Text(
                text = stringResource(Res.string.editor_parts_title),
                style = MaterialTheme.typography.labelLarge,
                color = ImagoColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onChange(RecipePart.entries.toSet()) }) { Text(stringResource(Res.string.editor_parts_all)) }
            TextButton(onClick = { onChange(emptySet()) }) { Text(stringResource(Res.string.editor_parts_none)) }
        }
        RECIPE_PART_GROUPS.forEach { group -> PartGroup(group, selected, onChange) }
    }
}

@Composable
private fun PartGroup(group: RecipePartGroup, selected: Set<RecipePart>, onChange: (Set<RecipePart>) -> Unit) {
    val title = stringResource(group.title)
    val parts = group.parts.map { it.first }
    val ticked = parts.count { it in selected }
    val state = when (ticked) {
        0 -> ToggleableState.Off
        parts.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val expandable = parts.size > 1
    var expanded by rememberSaveable(group.title.key) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = ImagoSizes.TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(ImagoRadii.Small))
                .triStateToggleable(state = state, role = Role.Checkbox) {
                    onChange(if (state == ToggleableState.On) selected - parts.toSet() else selected + parts)
                }
                .sizeIn(minHeight = ImagoSizes.TouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(state = state, onClick = null)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = ImagoSpacing.Sm).weight(1f),
            )
            if (expandable) {
                Text(
                    text = stringResource(Res.string.editor_parts_count, ticked, parts.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextSecondary,
                )
            }
        }
        if (expandable) {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) Res.string.editor_parts_collapse else Res.string.editor_parts_expand,
                        title,
                    ),
                )
            }
        }
    }
    if (expandable && expanded) {
        group.parts.forEach { (part, label) ->
            val checked = part in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ImagoSpacing.Xl)
                    .clip(RoundedCornerShape(ImagoRadii.Small))
                    .toggleable(value = checked, role = Role.Checkbox) { onChange(if (it) selected + part else selected - part) }
                    .sizeIn(minHeight = ImagoSizes.TouchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = ImagoSpacing.Sm),
                )
            }
        }
    }
}

/** Lightroom's Copy Settings: what is ticked is what pasting writes over the other photo. */
@Composable
internal fun CopyRecipeDialog(initial: Set<RecipePart>, onDismiss: () -> Unit, onCopy: (Set<RecipePart>) -> Unit) {
    var selected by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_copy_recipe)) },
        text = {
            RecipePartsPicker(selected, { selected = it }, Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(onClick = { onCopy(selected) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(Res.string.editor_copy))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.editor_cancel)) } },
    )
}
