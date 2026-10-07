@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.editor

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.feature.library.LibraryNavBar
import eu.studio742.imago.feature.library.LibraryNavDestination
import java.time.Instant
import java.util.UUID

data class RecipeLibraryUiState(
    val recipes: List<SavedRecipe> = emptyList(),
    val isLoading: Boolean = true,
)

open class RecipeLibraryViewModel(
    private val savedRecipes: SavedRecipeRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecipeLibraryUiState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        mutableState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val items = runCatching { savedRecipes.list() }.getOrDefault(emptyList())
            mutableState.update { it.copy(recipes = items, isLoading = false) }
        }
    }

    fun toggleFavorite(id: String) {
        val recipe = mutableState.value.recipes.firstOrNull { it.id == id } ?: return
        persist(recipe.copy(isFavorite = !recipe.isFavorite, updatedAt = Instant.now().toString()))
    }

    fun rename(id: String, name: String, collection: String) {
        val recipe = mutableState.value.recipes.firstOrNull { it.id == id } ?: return
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        persist(
            recipe.copy(
                name = cleanName,
                collection = collection.trim(),
                updatedAt = Instant.now().toString(),
            ),
        )
    }

    /** A copy is a new recipe of its own: not a favourite and never applied, whatever the original was. */
    fun duplicate(id: String) {
        val recipe = mutableState.value.recipes.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val now = Instant.now().toString()
            runCatching {
                savedRecipes.save(
                    recipe.copy(
                        id = UUID.randomUUID().toString(),
                        name = appString(Res.string.editor_recipe_copy_name, recipe.name),
                        recipe = recipe.recipe.copy(updatedAt = now),
                        createdAt = now,
                        updatedAt = now,
                        isFavorite = false,
                        usedAt = null,
                    ),
                )
            }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { savedRecipes.delete(id) }
            refresh()
        }
    }

    private fun persist(recipe: SavedRecipe) {
        viewModelScope.launch {
            runCatching { savedRecipes.save(recipe) }
            refresh()
        }
    }
}

/**
 * The recipe library opened from the bottom bar.
 *
 * There is no open photo here, so a recipe is not applied: tapping one opens it in the editor, over a
 * sample photo, and the edits go to the recipe itself. The previews are not left blank either: the
 * same sample serves as their source, so every tile shows the effect instead of a black square.
 */
@Composable
fun RecipeLibraryRoute(
    onBack: () -> Unit,
    onNavigate: (LibraryNavDestination) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RecipeLibraryViewModel = recipeLibraryViewModel(),
    editor: EditorViewModel = editorViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SavedRecipe?>(null) }
    var deleting by remember { mutableStateOf<SavedRecipe?>(null) }
    var sampleIndex by rememberSampleIndex()
    val sampleAssetId = rememberSampleRecipeSource(sampleIndex)
    // The key, and not the source: it survives a rotation, and the source is rebuilt from it.
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The grid stays where it was — tab, filter, scroll — while the editor is open over it.
    val gridState = rememberSaveableStateHolder()

    val editingSource = editingKey?.let { RecipeSource.fromKey(it, state.recipes) }
    if (editingKey != null) {
        if (editingSource == null) {
            // A saved recipe that is not in the list: still loading, or deleted on another device.
            if (!state.isLoading) LaunchedEffect(editingKey) { editingKey = null }
            return
        }
        EditorRoute(
            asset = sampleEditorAsset(sampleIndex),
            target = PhotoEditTarget.Recipe(sampleEditorAsset(sampleIndex), editingSource),
            assets = (0 until SAMPLE_COUNT).map(::sampleEditorAsset),
            selectedIndex = sampleIndex,
            onSelectIndex = { sampleIndex = it },
            onBack = {
                editingKey = null
                editor.closeRecipe()
                viewModel.refresh()
            },
            viewModel = editor,
        )
        return
    }

    BackHandler(onBack = onBack)
    gridState.SaveableStateProvider("grid") {
        RecipeLibraryScreen(
            saved = state.recipes.toCards(),
            builtIn = builtInCards(),
            isLoading = state.isLoading,
            assetId = sampleAssetId,
            onBack = onBack,
            onSelect = { card ->
                editingKey = if (card.isBuiltIn) RecipeSource.builtInKey(card.id) else RecipeSource.savedKey(card.id)
            },
            onToggleFavorite = { viewModel.toggleFavorite(it.id) },
            onRename = { card -> renaming = state.recipes.firstOrNull { it.id == card.id } },
            onDuplicate = { viewModel.duplicate(it.id) },
            onDelete = { card -> deleting = state.recipes.firstOrNull { it.id == card.id } },
            onCreate = { editingKey = RecipeSource.New().key },
            createLabel = stringResource(Res.string.editor_new_recipe),
            createHint = stringResource(Res.string.editor_new_recipe_hint),
            bottomBar = {
                LibraryNavBar(
                    selected = LibraryNavDestination.RECIPES,
                    onSelect = onNavigate,
                    onOpenSettings = onOpenSettings,
                )
            },
        )
    }

    renaming?.let { recipe ->
        RecipeDetailsDialog(
            title = stringResource(Res.string.editor_organize_recipe),
            initialName = recipe.name,
            initialCollection = recipe.collection,
            confirmLabel = stringResource(Res.string.editor_save_changes),
            onDismiss = { renaming = null },
            onConfirm = { name, collection ->
                viewModel.rename(recipe.id, name, collection)
                renaming = null
            },
        )
    }
    deleting?.let { recipe ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.editor_delete_recipe_question)) },
            text = {
                Text(
                    stringResource(Res.string.editor_recipe_delete_body, recipe.name),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(recipe.id)
                        deleting = null
                    },
                ) { Text(stringResource(Res.string.editor_delete), color = ImagoColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.editor_cancel)) } },
        )
    }
}
