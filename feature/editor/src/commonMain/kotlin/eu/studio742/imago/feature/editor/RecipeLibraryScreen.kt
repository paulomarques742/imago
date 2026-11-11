@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.resolve
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoTabRow
import eu.studio742.imago.core.model.BUILT_IN_RECIPES
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.label
import eu.studio742.imago.core.model.withPartsFrom

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
 * Where a recipe comes from — the person's own, or the app's — and then the two views that mix both:
 * a heart and a last use mean the same on either.
 */
enum class RecipeTab { MINE, IMAGO, FAVORITES, RECENT }

/** The "all" chip is not a group: it is the absence of a filter. A collection never has an empty name. */
private const val ALL_GROUPS = ""

@Composable
fun builtInCards(marks: List<BuiltInRecipeMark>): List<RecipeCard> = BUILT_IN_RECIPES.map {
    val mark = marks.firstOrNull { mark -> mark.id == it.id }
    RecipeCard(
        id = it.id,
        name = it.nameText().resolve(),
        description = it.descriptionText().resolve(),
        group = stringResource(it.category.labelRes()),
        recipe = it.recipe,
        isBuiltIn = true,
        isFavorite = mark?.isFavorite == true,
        usedAt = mark?.usedAt,
    )
}

fun List<SavedRecipe>.toCards(): List<RecipeCard> = map {
    RecipeCard(
        id = it.id,
        name = it.name,
        description = it.collection,
        group = it.collection,
        // The preview shows what applying it does, and a recipe never takes along what it leaves out.
        recipe = EditRecipe(
            assetId = it.recipe.assetId,
            originalChecksum = it.recipe.originalChecksum,
            processVersion = it.recipe.processVersion,
            createdAt = it.recipe.createdAt,
            updatedAt = it.recipe.updatedAt,
        ).withPartsFrom(it.recipe, it.appliedParts),
        isBuiltIn = false,
        isFavorite = it.isFavorite,
        usedAt = it.usedAt,
    )
}

/**
 * The recipe library.
 *
 * It serves two contexts: opened from the editor it shows each recipe already applied to the photo
 * at hand, and [onSelect] applies it; opened from the bottom bar there is no photo at all, and
 * [onSelect] opens the recipe itself for editing. The previews exist in both cases: without an open
 * photo, [assetId] carries a sample.
 */
@Composable
fun RecipeLibraryScreen(
    saved: List<RecipeCard>,
    builtIn: List<RecipeCard>,
    isLoading: Boolean,
    assetId: String?,
    onBack: () -> Unit,
    onSelect: (RecipeCard) -> Unit,
    onToggleFavorite: (RecipeCard) -> Unit,
    onRename: (RecipeCard) -> Unit,
    onDelete: (RecipeCard) -> Unit,
    onCreate: (() -> Unit)?,
    onDuplicate: ((RecipeCard) -> Unit)? = null,
    createLabel: String = stringResource(Res.string.editor_create_recipe),
    createHint: String = stringResource(Res.string.editor_create_recipe_hint),
    /** Null where the system has no share menu. With [onSaveRecipes], what lets the person's recipes be selected. */
    onShareRecipes: ((List<RecipeCard>) -> Unit)? = null,
    onSaveRecipes: ((List<RecipeCard>) -> Unit)? = null,
    /** One recipe's «Share», which has the link too. Without it, the same as [onShareRecipes]. */
    onShareRecipe: ((RecipeCard) -> Unit)? = null,
    onSaveXmp: ((RecipeCard) -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    /** A new value shows the person's own recipes, which is where an import just went. */
    showMineSignal: Int = 0,
    bottomBar: @Composable () -> Unit = {},
) {
    // Saveable, so that coming back from editing a recipe finds the same tab and the same filter.
    var tab by rememberSaveable { mutableStateOf(if (saved.isEmpty()) RecipeTab.IMAGO else RecipeTab.MINE) }
    var group by rememberSaveable(tab) { mutableStateOf(ALL_GROUPS) }
    // Compared with the last one seen and not keyed on: the grid leaves the composition while a recipe is
    // edited, and coming back from that must not move it off the tab it was on.
    var seenMineSignal by rememberSaveable { mutableStateOf(0) }
    if (showMineSignal != seenMineSignal) {
        seenMineSignal = showMineSignal
        tab = RecipeTab.MINE
        group = ALL_GROUPS
    }

    val selectable = onShareRecipes != null || onSaveRecipes != null
    // An ArrayList because that is what saved state can hold.
    var selectedIds by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    // A recipe deleted on another device while selected simply stops counting.
    val selection = saved.filter { it.id in selectedIds }
    val selecting = selection.isNotEmpty()
    fun toggle(card: RecipeCard) {
        selectedIds = ArrayList(if (card.id in selectedIds) selectedIds - card.id else selectedIds + card.id)
    }
    fun clearSelection() {
        selectedIds = ArrayList()
    }
    BackHandler(enabled = selecting, onBack = ::clearSelection)

    val visible = when (tab) {
        RecipeTab.MINE -> saved
        RecipeTab.IMAGO -> builtIn
        RecipeTab.FAVORITES -> (saved + builtIn).filter { it.isFavorite }
        // Without `usedAt` the recipe was never applied and has no place in "Recent".
        RecipeTab.RECENT -> (saved + builtIn).filter { it.usedAt != null }.sortedByDescending { it.usedAt }
    }
    val groups = listOf(ALL_GROUPS) + visible.map(RecipeCard::group).distinct().sorted()
    val filtered = if (group == ALL_GROUPS) visible else visible.filter { it.group == group }

    Scaffold(containerColor = ImagoUi.colors.background, bottomBar = bottomBar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = ImagoSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    IconButton(onClick = ::clearSelection) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(Res.string.editor_recipe_clear_selection),
                            tint = ImagoUi.colors.text,
                        )
                    }
                    Text(
                        text = pluralStringResource(Res.plurals.editor_recipes_selected, selection.size, selection.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = ImagoUi.colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    onShareRecipes?.let { share ->
                        IconButton(onClick = { share(selection); clearSelection() }) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = stringResource(Res.string.editor_recipe_share),
                                tint = ImagoUi.colors.text,
                            )
                        }
                    }
                    onSaveRecipes?.let { save ->
                        IconButton(onClick = { save(selection); clearSelection() }) {
                            Icon(
                                Icons.Outlined.SaveAlt,
                                contentDescription = stringResource(Res.string.editor_save_as),
                                tint = ImagoUi.colors.text,
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(Res.string.editor_back),
                            tint = ImagoUi.colors.text,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.editor_recipes_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ImagoUi.colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    onImport?.let { import ->
                        IconButton(onClick = import) {
                            Icon(
                                Icons.Outlined.FileOpen,
                                contentDescription = stringResource(Res.string.editor_recipe_import),
                                tint = ImagoUi.colors.text,
                            )
                        }
                    }
                }
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
                ) { CircularProgressIndicator(color = ImagoUi.colors.emphasis) }

                filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(ImagoSpacing.Xxxl),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = tab.emptyMessage(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ImagoUi.colors.secondaryText,
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
                            selecting = selecting,
                            selected = card.id in selectedIds,
                            onSelect = {
                                when {
                                    !selecting -> onSelect(card)
                                    !card.isBuiltIn -> toggle(card)
                                }
                            },
                            onStartSelecting = if (selectable && !card.isBuiltIn) { { toggle(card) } } else null,
                            onToggleFavorite = { onToggleFavorite(card) },
                            onRename = { onRename(card) },
                            onDuplicate = onDuplicate?.let { { it(card) } },
                            onShare = onShareRecipe?.let { { it(card) } } ?: onShareRecipes?.let { { it(listOf(card)) } },
                            onSaveAs = onSaveRecipes?.let { { it(listOf(card)) } },
                            onSaveXmp = onSaveXmp?.let { { it(card) } },
                            onDelete = { onDelete(card) },
                        )
                    }
                }
            }
            if (!selecting) onCreate?.let { CreateRecipeRow(label = createLabel, hint = createHint, onClick = it) }
        }
    }
}

@Composable
private fun RecipeTile(
    card: RecipeCard,
    assetId: String?,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onStartSelecting: (() -> Unit)?,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onSaveAs: (() -> Unit)?,
    onSaveXmp: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val preview by rememberRecipePreview(assetId, card.id, card.recipe)
    val shape = RoundedCornerShape(ImagoRadii.Medium)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(ImagoUi.colors.surface)
            .then(if (selected) Modifier.border(2.dp, ImagoUi.colors.emphasis, shape) else Modifier)
            .combinedClickable(role = Role.Button, onClick = onSelect, onLongClick = onStartSelecting),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .background(ImagoUi.colors.elevated),
        ) {
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ImagoUi.colors.emphasis,
                    modifier = Modifier.align(Alignment.TopStart).padding(ImagoSpacing.Sm).size(ImagoSizes.IconDefault),
                )
            }
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
                    tint = ImagoUi.colors.onImage,
                    modifier = Modifier.size(ImagoSizes.IconDefault),
                )
            }
            // The gradient holds the name over the preview, which may be light or dark.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, ImagoUi.colors.imageShade.copy(alpha = 0.78f))),
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
            if (!card.isBuiltIn && !selecting) {
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(Res.string.editor_recipe_options, card.name),
                            tint = ImagoUi.colors.tertiaryText,
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
                        onDuplicate?.let { duplicate ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_duplicate)) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    duplicate()
                                },
                            )
                        }
                        onShare?.let { share ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_recipe_share)) },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    share()
                                },
                            )
                        }
                        onSaveAs?.let { saveAs ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_save_as)) },
                                leadingIcon = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    saveAs()
                                },
                            )
                        }
                        onSaveXmp?.let { saveXmp ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_save_as_xmp)) },
                                leadingIcon = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    saveXmp()
                                },
                            )
                        }
                        onStartSelecting?.let { select ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_recipe_select)) },
                                leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    select()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.editor_delete), color = ImagoUi.colors.danger) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = ImagoUi.colors.danger)
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
private fun CreateRecipeRow(label: String, hint: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ImagoSpacing.Md)
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoUi.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(ImagoSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Layers,
            contentDescription = null,
            tint = ImagoUi.colors.secondaryText,
            modifier = Modifier.size(ImagoSizes.IconLarge),
        )
        Column(Modifier.weight(1f).padding(horizontal = ImagoSpacing.Md)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = ImagoUi.colors.text,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = ImagoUi.colors.tertiaryText,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = ImagoUi.colors.tertiaryText,
        )
    }
}

@Composable
private fun RecipeTab.label() = when (this) {
    RecipeTab.MINE -> stringResource(Res.string.editor_tab_mine)
    RecipeTab.IMAGO -> stringResource(Res.string.editor_tab_imago)
    RecipeTab.FAVORITES -> stringResource(Res.string.editor_tab_favorites)
    RecipeTab.RECENT -> stringResource(Res.string.editor_tab_recent)
}

@Composable
private fun RecipeTab.emptyMessage() = when (this) {
    RecipeTab.MINE -> stringResource(Res.string.editor_empty_saved)
    RecipeTab.IMAGO -> stringResource(Res.string.editor_empty_filters)
    RecipeTab.FAVORITES -> stringResource(Res.string.editor_empty_favorites)
    RecipeTab.RECENT -> stringResource(Res.string.editor_empty_recent)
}
