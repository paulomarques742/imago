package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.interchange.ImportGap
import eu.studio742.imago.core.interchange.ImportGapReason
import eu.studio742.imago.core.interchange.ImportedSetting
import eu.studio742.imago.core.interchange.RecipeImportPlan
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A single recipe is shown large; beyond this the preview's own resolution would only blur. */
private val SINGLE_PREVIEW_MAX_WIDTH = 360.dp

/**
 * What a file would add, drawn over the sample photos, and what it would leave out. Nothing is saved
 * until the button is pressed: a file received from someone is looked at before it is kept.
 */
@Composable
internal fun RecipeImportScreen(
    plan: RecipeImportPlan,
    assetId: String?,
    sampleIndex: Int,
    onSampleIndex: (Int) -> Unit,
    isAdding: Boolean,
    addFailed: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    val cards = remember(plan) { plan.recipes.toCards() }
    val single = cards.size == 1
    Scaffold(containerColor = ImagoUi.colors.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = ImagoSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(Res.string.editor_back),
                        tint = ImagoUi.colors.text,
                    )
                }
                Text(
                    text = stringResource(Res.string.editor_recipe_import_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = ImagoUi.colors.text,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (single) 1 else 3),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = ImagoSpacing.Md),
                horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { ImportReport(plan, addFailed) }
                items(cards, key = RecipeCard::id) { card ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        ImportTile(
                            card = card,
                            assetId = assetId,
                            modifier = if (single) Modifier.widthIn(max = SINGLE_PREVIEW_MAX_WIDTH) else Modifier,
                        )
                    }
                }
            }
            if (cards.isNotEmpty()) {
                SampleSwitcher(sampleIndex, onSampleIndex)
                Button(
                    onClick = onAdd,
                    enabled = !isAdding,
                    shape = RoundedCornerShape(ImagoRadii.Medium),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ImagoUi.colors.emphasis,
                        contentColor = ImagoUi.colors.onEmphasis,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(ImagoSpacing.Md).height(52.dp),
                ) {
                    Text(pluralStringResource(Res.plurals.editor_recipe_import_add, cards.size, cards.size))
                }
            }
        }
    }
}

@Composable
private fun SampleSwitcher(index: Int, onIndex: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = { onIndex((index - 1 + SAMPLE_COUNT) % SAMPLE_COUNT) }) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(Res.string.editor_recipe_import_previous_sample),
                tint = ImagoUi.colors.secondaryText,
            )
        }
        Text(
            text = stringResource(Res.string.editor_recipe_import_sample, index + 1, SAMPLE_COUNT),
            style = MaterialTheme.typography.bodySmall,
            color = ImagoUi.colors.secondaryText,
        )
        IconButton(onClick = { onIndex((index + 1) % SAMPLE_COUNT) }) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(Res.string.editor_recipe_import_next_sample),
                tint = ImagoUi.colors.secondaryText,
            )
        }
    }
}

@Composable
private fun ImportTile(card: RecipeCard, assetId: String?, modifier: Modifier = Modifier) {
    val preview by rememberRecipePreview(assetId, card.id, card.recipe)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoUi.colors.surface),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.78f).background(ImagoUi.colors.elevated)) {
            preview?.let {
                Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Sm)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleSmall,
                color = ImagoUi.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = card.description,
                style = MaterialTheme.typography.bodySmall,
                color = ImagoUi.colors.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun gapText(gap: ImportGap): String = when (gap) {
    is ImportGap.Left -> stringResource(
        Res.string.editor_recipe_gap_item,
        stringResource(importedSettingLabel(gap.setting)),
        stringResource(importGapReasonLabel(gap.reason)),
    )
    is ImportGap.Limited -> stringResource(
        Res.string.editor_recipe_gap_limited,
        stringResource(recipePartLabel(gap.part)),
        gap.original.toString().removeSuffix(".0"),
    )
}

private fun importedSettingLabel(setting: ImportedSetting): StringResource = when (setting) {
    ImportedSetting.PARAMETRIC_CURVE -> Res.string.editor_recipe_gap_parametric_curve
    ImportedSetting.TONE_CURVE -> Res.string.editor_recipe_gap_tone_curve
    ImportedSetting.CREATIVE_PROFILE -> Res.string.editor_recipe_gap_creative_profile
    ImportedSetting.CAMERA_PROFILE -> Res.string.editor_recipe_gap_camera_profile
    ImportedSetting.MASKS -> Res.string.editor_recipe_gap_masks
    ImportedSetting.GEOMETRY -> Res.string.editor_recipe_gap_geometry
    ImportedSetting.GRAY_MIXER -> Res.string.editor_recipe_gap_gray_mixer
    ImportedSetting.LENS_PROFILE -> Res.string.editor_recipe_gap_lens_profile
    ImportedSetting.CHROMATIC_ABERRATION -> Res.string.editor_recipe_gap_chromatic_aberration
    ImportedSetting.DEFRINGE_HUE -> Res.string.editor_recipe_gap_defringe_hue
    ImportedSetting.VIGNETTE_STYLE -> Res.string.editor_recipe_gap_vignette_style
    ImportedSetting.AUTO_TONE -> Res.string.editor_recipe_gap_auto_tone
    ImportedSetting.ISO_ADAPTIVE -> Res.string.editor_recipe_gap_iso_adaptive
    ImportedSetting.POINT_COLOR -> Res.string.editor_recipe_gap_point_color
    ImportedSetting.LENS_BLUR -> Res.string.editor_recipe_gap_lens_blur
    ImportedSetting.OLD_TONE_CONTROLS -> Res.string.editor_recipe_gap_old_tone
    ImportedSetting.WHITE_BALANCE_PRESET -> Res.string.editor_recipe_gap_white_balance
    ImportedSetting.OTHER -> Res.string.editor_recipe_gap_other
}

private fun importGapReasonLabel(reason: ImportGapReason): StringResource = when (reason) {
    ImportGapReason.NOT_IN_APP -> Res.string.editor_recipe_gap_reason_not_in_app
    ImportGapReason.NOT_IMPORTED -> Res.string.editor_recipe_gap_reason_not_imported
    ImportGapReason.OLD_PROCESS -> Res.string.editor_recipe_gap_reason_old_process
    ImportGapReason.APPROXIMATED -> Res.string.editor_recipe_gap_reason_approximated
}

/** Everything that will not enter, and why. Above the recipes, so that it is read before adding. */
@Composable
private fun ImportReport(plan: RecipeImportPlan, addFailed: Boolean) {
    val lines = buildList {
        if (addFailed) add(stringResource(Res.string.editor_recipe_import_failed))
        if (plan.recipes.isEmpty()) add(stringResource(Res.string.editor_recipe_import_nothing))
        if (plan.alreadyInLibrary.isNotEmpty()) {
            add(stringResource(Res.string.editor_recipe_import_already, plan.alreadyInLibrary.joinToString()))
        }
        if (plan.needsNewerApp.isNotEmpty()) {
            add(stringResource(Res.string.editor_recipe_import_newer, plan.needsNewerApp.joinToString()))
        }
        if (plan.partlyRead.isNotEmpty()) {
            add(stringResource(Res.string.editor_recipe_import_partly, plan.partlyRead.joinToString()))
        }
        plan.gaps.forEach { recipe ->
            add(stringResource(Res.string.editor_recipe_import_gaps, recipe.name, recipe.gaps.map { gapText(it) }.joinToString("; ")))
        }
        if (plan.skippedFiles > 0) {
            add(pluralStringResource(Res.plurals.editor_recipe_import_skipped, plan.skippedFiles, plan.skippedFiles))
        }
        if (plan.invalidRecipes > 0) {
            add(pluralStringResource(Res.plurals.editor_recipe_import_invalid, plan.invalidRecipes, plan.invalidRecipes))
        }
        if (plan.unreadableFiles > 0) {
            add(pluralStringResource(Res.plurals.editor_recipe_import_unreadable, plan.unreadableFiles, plan.unreadableFiles))
        }
        if (plan.filesNeedingNewerApp > 0) {
            add(pluralStringResource(Res.plurals.editor_recipe_import_newer_files, plan.filesNeedingNewerApp, plan.filesNeedingNewerApp))
        }
    }
    if (lines.isEmpty()) return
    Column(
        modifier = Modifier.padding(bottom = ImagoSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
    ) {
        lines.forEach { line ->
            Text(text = line, style = MaterialTheme.typography.bodyMedium, color = ImagoUi.colors.secondaryText)
        }
    }
}
