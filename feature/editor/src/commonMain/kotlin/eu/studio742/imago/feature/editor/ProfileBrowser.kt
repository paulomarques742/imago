package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A RAW's profile, first in the Light panel: the photo in it, small, and its name. A touch opens the
 * browser — as Lightroom's Profile Browser — with the photo shown in every profile, edits included,
 * since a profile is chosen by eye.
 */
@Composable
internal fun ProfileRow(
    assetId: String?,
    recipe: EditRecipe,
    onSelect: (RawProfileChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var browsing by remember { mutableStateOf(false) }
    val current = RawProfileChoice.of(recipe.profile)
    val preview by rememberRecipePreview(assetId, PROFILE_PREVIEW_PREFIX + current.id, recipe)
    val description = stringResource(Res.string.editor_profile_choose)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = ImagoSpacing.Sm)
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.Surface)
            .clickable(role = Role.Button) { browsing = true }
            .semantics { contentDescription = description }
            .padding(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(ImagoRadii.Small))
                .background(ImagoColors.SurfaceElevated),
        ) {
            preview?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.editor_profile), style = MaterialTheme.typography.labelMedium, color = ImagoColors.Gold)
            Text(stringResource(current.labelRes), style = MaterialTheme.typography.titleSmall, color = ImagoColors.TextPrimary)
        }
        Icon(Icons.Outlined.GridView, contentDescription = null, tint = ImagoColors.TextSecondary)
    }
    if (browsing) {
        ProfileBrowser(
            assetId = assetId,
            recipe = recipe,
            current = current,
            onDismiss = { browsing = false },
            onSelect = { choice ->
                browsing = false
                onSelect(choice)
            },
        )
    }
}

@Composable
private fun ProfileBrowser(
    assetId: String?,
    recipe: EditRecipe,
    current: RawProfileChoice,
    onDismiss: () -> Unit,
    onSelect: (RawProfileChoice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_profiles)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                RawProfileChoice.entries.chunked(PROFILE_COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                        row.forEach { choice ->
                            ProfileTile(
                                assetId = assetId,
                                recipe = recipe.copy(profile = choice.id),
                                choice = choice,
                                selected = choice == current,
                                onClick = { onSelect(choice) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(PROFILE_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.editor_cancel)) } },
    )
}

@Composable
private fun ProfileTile(
    assetId: String?,
    recipe: EditRecipe,
    choice: RawProfileChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview by rememberRecipePreview(assetId, PROFILE_PREVIEW_PREFIX + choice.id, recipe)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(ImagoRadii.Medium))
                .background(ImagoColors.SurfaceElevated)
                .then(if (selected) Modifier.border(2.dp, ImagoColors.Gold, RoundedCornerShape(ImagoRadii.Medium)) else Modifier),
        ) {
            preview?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            text = stringResource(choice.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) ImagoColors.Gold else ImagoColors.TextPrimary,
        )
    }
}

private const val PROFILE_COLUMNS = 2

/** The previews' ids in the recipe preview cache, apart from the recipes' own. */
private const val PROFILE_PREVIEW_PREFIX = "profile:"

/**
 * Offered on a RAW kept under an older development: one tap moves it to the current one, with the
 * Exposure compensated so the photo does not get brighter or darker. It is a history step, so it can be undone.
 */
@Composable
internal fun DevelopmentUpgradeCard(
    isWorking: Boolean,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = ImagoSpacing.Sm)
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.Surface)
            .padding(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.editor_development_card_title), style = MaterialTheme.typography.labelMedium, color = ImagoColors.Gold)
            Text(stringResource(Res.string.editor_development_card_body), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        }
        if (isWorking) {
            CircularProgressIndicator(color = ImagoColors.Gold, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onUpgrade) { Text(stringResource(Res.string.editor_development_card_action)) }
        }
    }
}
