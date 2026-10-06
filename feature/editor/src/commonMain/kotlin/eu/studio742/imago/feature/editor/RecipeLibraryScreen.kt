package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.resolve
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoChipRow
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoTabRow
import eu.studio742.imago.core.model.BUILT_IN_RECIPES
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.label

/** A recipe as the grid shows it, whether it comes from the app or from the user's library. */
data class RecipeCard(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
    val recipe: EditRecipe,
    val isBuiltIn: Boolean,
    val isFavorite: Boolean,
    val usedAt: String?,
)

/**
 * The mockup's tabs.
 *
 * "Filters" are the presets that come with the app; "Recipes" the ones the user saved. It is the
 * distinction the two names already suggest, and the only one the data supports.
 */
enum class RecipeTab { SAVED, BUILT_IN, FAVORITES, RECENT }

/** The "all" chip is not a group: it is the absence of a filter. A collection never has an empty name. */
private const val ALL_GROUPS = ""

@Composable
fun builtInCards(): List<RecipeCard> = BUILT_IN_RECIPES.map {
    RecipeCard(
        id = it.id,
        name = it.nameText().resolve(),
        description = it.descriptionText().resolve(),
        group = stringResource(it.category.labelRes()),
        recipe = it.recipe,
        isBuiltIn = true,
        isFavorite = false,
        usedAt = null,
    )
}

fun List<SavedRecipe>.toCards(): List<RecipeCard> = map {
    RecipeCard(
        id = it.id,
        name = it.name,
        description = it.collection,
        group = it.collection,
        recipe = it.recipe,
        isBuiltIn = false,
        isFavorite = it.isFavorite,
        usedAt = it.usedAt,
    )
}

/**
 * The recipe library.
 *
 * It serves two contexts: opened from the editor it shows each recipe already applied to the photo
 * at hand, and applying it is one tap; opened from the bottom bar there is no photo at all, and so it
 * is only for management — hence [onApply] being optional. The previews exist in both cases: without
 * an open photo, [assetId] carries a sample.
 */
@Composable
fun RecipeLibraryScreen(
    saved: List<RecipeCard>,
    builtIn: List<RecipeCard>,
    isLoading: Boolean,
    assetId: String?,
    onBack: () -> Unit,
    onApply: ((RecipeCard) -> Unit)?,
    onToggleFavorite: (RecipeCard) -> Unit,
    onRename: (RecipeCard) -> Unit,
    onDelete: (RecipeCard) -> Unit,
    onCreate: (() -> Unit)?,
    bottomBar: @Composable () -> Unit = {},
) {
    var tab by remember { mutableStateOf(if (saved.isEmpty()) RecipeTab.BUILT_IN else RecipeTab.SAVED) }
    var group by remember(tab) { mutableStateOf(ALL_GROUPS) }

    val visible = when (tab) {
        RecipeTab.SAVED -> saved
        RecipeTab.BUILT_IN -> builtIn
        RecipeTab.FAVORITES -> saved.filter { it.isFavorite }
        // Without `usedAt` the recipe was never applied and has no place in "Recent".
        RecipeTab.RECENT -> saved.filter { it.usedAt != null }.sortedByDescending { it.usedAt }
    }
    val groups = listOf(ALL_GROUPS) + visible.map(RecipeCard::group).distinct().sorted()
    val filtered = if (group == ALL_GROUPS) visible else visible.filter { it.group == group }

    Scaffold(containerColor = ImagoColors.Background, bottomBar = bottomBar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = ImagoSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(Res.string.editor_back),
                        tint = ImagoColors.TextPrimary,
                    )
                }
                Text(
                    text = stringResource(Res.string.editor_recipes_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = ImagoColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
            ImagoTabRow(
                options = RecipeTab.entries,
                selected = tab,
                label = RecipeTab::label,
                onSelect = { tab = it },
                modifier = Modifier.padding(bottom = ImagoSpacing.Md),
            )
            // A row with a single chip filters nothing — it would only take up space.
            if (groups.size > 1) {
                ImagoChipRow(
                    options = groups,
                    selected = group,
                    label = { if (it == ALL_GROUPS) stringResource(Res.string.editor_recipes_all_groups) else it },
                    onSelect = { group = it },
                    modifier = Modifier.padding(bottom = ImagoSpacing.Md),
                )
            }
            when {
                isLoading && filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = ImagoColors.Ivory) }

                filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(ImagoSpacing.Xxxl),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = tab.emptyMessage(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ImagoColors.TextSecondary,
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = ImagoSpacing.Md),
                    horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                ) {
                    items(filtered, key = RecipeCard::id) { card ->
                        RecipeTile(
                            card = card,
                            assetId = assetId,
                            onApply = onApply,
                            onToggleFavorite = { onToggleFavorite(card) },
                            onRename = { onRename(card) },
                            onDelete = { onDelete(card) },
                        )
                    }
                }
            }
            onCreate?.let { CreateRecipeRow(onClick = it) }
        }
    }
}

@Composable
private fun RecipeTile(
    card: RecipeCard,
    assetId: String?,
    onApply: ((RecipeCard) -> Unit)?,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val preview by rememberRecipePreview(assetId, card.id, card.recipe)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.Surface)
            .then(
                if (onApply != null) {
                    Modifier.clickable(role = Role.Button) { onApply(card) }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .background(ImagoColors.SurfaceElevated),
        ) {
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The heart only exists on the user's recipes: the app's are not theirs to mark.
            if (!card.isBuiltIn) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(ImagoSizes.TouchTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Checkbox, onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (card.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (card.isFavorite) {
                            stringResource(Res.string.editor_recipe_unfavorite, card.name)
                        } else {
                            stringResource(Res.string.editor_recipe_favorite, card.name)
                        },
                        tint = ImagoColors.BrandWhite,
                        modifier = Modifier.size(ImagoSizes.IconDefault),
                    )
                }
            }
            // The gradient holds the name over the preview, which may be light or dark.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ImagoSpacing.Md, top = ImagoSpacing.Sm, bottom = ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = ImagoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!card.isBuiltIn) {
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(Res.string.editor_recipe_options, card.name),
                            tint = ImagoColors.TextTertiary,
                            modifier = Modifier.size(ImagoSizes.IconDefault),
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.editor_organize)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.editor_delete), color = ImagoColors.Danger) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = ImagoColors.Danger)
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateRecipeRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ImagoSpacing.Md)
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(ImagoSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Layers,
            contentDescription = null,
            tint = ImagoColors.TextSecondary,
            modifier = Modifier.size(ImagoSizes.IconLarge),
        )
        Column(Modifier.weight(1f).padding(horizontal = ImagoSpacing.Md)) {
            Text(
                text = stringResource(Res.string.editor_create_recipe),
                style = MaterialTheme.typography.titleSmall,
                color = ImagoColors.TextPrimary,
            )
            Text(
                text = stringResource(Res.string.editor_create_recipe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = ImagoColors.TextTertiary,
        )
    }
}

@Composable
private fun RecipeTab.label() = when (this) {
    RecipeTab.SAVED -> stringResource(Res.string.editor_tab_recipes)
    RecipeTab.BUILT_IN -> stringResource(Res.string.editor_tab_filters)
    RecipeTab.FAVORITES -> stringResource(Res.string.editor_tab_favorites)
    RecipeTab.RECENT -> stringResource(Res.string.editor_tab_recent)
}

@Composable
private fun RecipeTab.emptyMessage() = when (this) {
    RecipeTab.SAVED -> stringResource(Res.string.editor_empty_saved)
    RecipeTab.BUILT_IN -> stringResource(Res.string.editor_empty_filters)
    RecipeTab.FAVORITES -> stringResource(Res.string.editor_empty_favorites)
    RecipeTab.RECENT -> stringResource(Res.string.editor_empty_recent)
}
