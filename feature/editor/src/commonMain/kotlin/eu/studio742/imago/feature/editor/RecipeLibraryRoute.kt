@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.editor

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.feature.library.LibraryNavBar
import eu.studio742.imago.feature.library.LibraryNavDestination
import java.time.Instant

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
 * There is no open photo here, and applying makes no sense — it is a screen for organising, and the
 * same screen gains the tap to apply when opened from the editor. The previews are not left blank: a
 * sample photo serves as the source, so every tile shows the effect instead of a black square.
 */
@Composable
fun RecipeLibraryRoute(
    onBack: () -> Unit,
    onNavigate: (LibraryNavDestination) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RecipeLibraryViewModel = recipeLibraryViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SavedRecipe?>(null) }
    var deleting by remember { mutableStateOf<SavedRecipe?>(null) }
    val sampleAssetId = rememberSampleRecipeSource()
    BackHandler(onBack = onBack)

    RecipeLibraryScreen(
        saved = state.recipes.toCards(),
        builtIn = builtInCards(),
        isLoading = state.isLoading,
        assetId = sampleAssetId,
        onBack = onBack,
        onApply = null,
        onToggleFavorite = { viewModel.toggleFavorite(it.id) },
        onRename = { card -> renaming = state.recipes.firstOrNull { it.id == card.id } },
        onDelete = { card -> deleting = state.recipes.firstOrNull { it.id == card.id } },
        onCreate = null,
        bottomBar = {
            LibraryNavBar(
                selected = LibraryNavDestination.RECIPES,
                onSelect = onNavigate,
                onOpenSettings = onOpenSettings,
            )
        },
    )

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
