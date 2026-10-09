package eu.studio742.imago.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.stringResource

/** The collections the person already has, each once, in the order a list of names is read. */
fun List<SavedRecipe>.collections(): List<String> =
    map { it.collection.trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

/**
 * The collection a dialog ends with: a new name that only differs in case from an existing one is
 * that one, so "travel" does not open a second "Travel".
 */
internal fun chosenCollection(typed: String, existing: List<String>): String {
    val clean = typed.trim()
    return existing.firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
}

/**
 * Names a recipe and puts it in a collection. The collections that exist are chips to pick from,
 * and the last chip opens a field for a new one — typing the same name twice by hand was how a
 * library ended up with "Travel" and "Travels".
 */
@Composable
internal fun RecipeDetailsDialog(
    title: String,
    initialName: String,
    initialCollection: String,
    collections: List<String>,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    val defaultCollection = stringResource(Res.string.editor_default_collection)
    val start = initialCollection.trim().ifBlank { defaultCollection }
    // The suggestion is a choice even before any recipe is in it: a first recipe still has somewhere to go.
    val choices = remember(collections, start) {
        (collections + start).distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    var name by remember(initialName) { mutableStateOf(initialName) }
    var selected by remember(start) { mutableStateOf(chosenCollection(start, choices)) }
    var creating by remember { mutableStateOf(false) }
    var newCollection by remember { mutableStateOf("") }
    val result = if (creating) chosenCollection(newCollection, choices) else selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.editor_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.editor_collection),
                    style = MaterialTheme.typography.labelLarge,
                    color = ImagoColors.TextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                ) {
                    choices.forEach { collection ->
                        CollectionChip(
                            label = collection,
                            selected = !creating && collection == selected,
                            onClick = {
                                creating = false
                                selected = collection
                            },
                        )
                    }
                    CollectionChip(
                        label = stringResource(Res.string.editor_new_collection),
                        selected = creating,
                        icon = Icons.Outlined.Add,
                        onClick = { creating = true },
                    )
                }
                if (creating) {
                    val focus = remember { FocusRequester() }
                    OutlinedTextField(
                        value = newCollection,
                        onValueChange = { newCollection = it },
                        label = { Text(stringResource(Res.string.editor_new_collection_name)) },
                        supportingText = { Text(stringResource(Res.string.editor_collection_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), result) },
                enabled = name.isNotBlank() && result.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.editor_cancel)) } },
    )
}

/** The library's chip: the chosen one is the inverse of the others, as in [eu.studio742.imago.core.designsystem.ImagoChipRow]. */
@Composable
private fun CollectionChip(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val color = if (selected) ImagoColors.BrandBlack else ImagoColors.TextSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Pill))
            .background(if (selected) ImagoColors.BrandWhite else ImagoColors.Charcoal)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
        Text(text = label, style = MaterialTheme.typography.titleSmall, color = color)
    }
}
