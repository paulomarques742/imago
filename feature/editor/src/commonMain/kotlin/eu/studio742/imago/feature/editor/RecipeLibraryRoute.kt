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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.interchange.ImagoFileContent
import eu.studio742.imago.core.interchange.ReadImagoFile
import eu.studio742.imago.core.interchange.RecipeImportPlan
import eu.studio742.imago.core.interchange.decodeImagoFile
import eu.studio742.imago.core.interchange.RecipeLinkError
import eu.studio742.imago.core.interchange.decodeRecipeFile
import eu.studio742.imago.core.interchange.planRecipeImport
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.storedForParts
import eu.studio742.imago.feature.library.LibraryNavBar
import eu.studio742.imago.feature.library.LibraryNavDestination
import java.time.Instant
import java.util.UUID

data class RecipeLibraryUiState(
    val recipes: List<SavedRecipe> = emptyList(),
    val builtInMarks: List<BuiltInRecipeMark> = emptyList(),
    val isLoading: Boolean = true,
    /** What the files being imported would add; null when none are. */
    val importPlan: RecipeImportPlan? = null,
    val isAddingImport: Boolean = false,
    val importAddFailed: Boolean = false,
    /** Goes up with each import that lands, so that the grid can show where the recipes went. */
    val importsAdded: Int = 0,
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
            val marks = runCatching { savedRecipes.builtInMarks() }.getOrDefault(emptyList())
            mutableState.update { it.copy(recipes = items, builtInMarks = marks, isLoading = false) }
        }
    }

    fun toggleFavorite(id: String) {
        val recipe = mutableState.value.recipes.firstOrNull { it.id == id } ?: return
        persist(recipe.copy(isFavorite = !recipe.isFavorite, updatedAt = Instant.now().toString()))
    }

    fun toggleBuiltInFavorite(id: String) {
        val mark = mutableState.value.builtInMarks.firstOrNull { it.id == id } ?: BuiltInRecipeMark(id)
        viewModelScope.launch {
            runCatching { savedRecipes.saveBuiltInMark(mark.copy(isFavorite = !mark.isFavorite)) }
            refresh()
        }
    }

    fun rename(id: String, name: String, collection: String, parts: Set<RecipePart>? = null) {
        val recipe = mutableState.value.recipes.firstOrNull { it.id == id } ?: return
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val chosen = parts?.let(recipe::partsAfterChoosing) ?: recipe.parts
        persist(
            recipe.copy(
                name = cleanName,
                collection = collection.trim(),
                recipe = if (parts != null) recipe.recipe.storedForParts(chosen) else recipe.recipe,
                updatedAt = Instant.now().toString(),
                parts = chosen,
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

    /** A recipe copied from the community gallery; a failure is the caller's to say. */
    suspend fun addCopied(recipe: SavedRecipe) {
        savedRecipes.save(recipe)
        refresh()
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { savedRecipes.delete(id) }
            refresh()
        }
    }

    /** Reads the files and says what adding them would do; nothing is saved until [addImported]. */
    fun openFiles(files: List<RecipeFile>, importedCollection: String = "") {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val library = runCatching { savedRecipes.list() }.getOrDefault(mutableState.value.recipes)
            val plan = withContext(Dispatchers.Default) {
                planRecipeImport(
                    files = files.map { file ->
                        ReadImagoFile(file.name, readFile(file))
                    },
                    library = library,
                    now = Instant.now().toString(),
                    newId = { UUID.randomUUID().toString() },
                    importedCollection = importedCollection,
                )
            }
            mutableState.update { it.copy(importPlan = plan, importAddFailed = false) }
        }
    }

    /** Pressing it again after a failure is safe: the ids are the plan's, so what already landed is rewritten. */
    fun addImported() {
        val plan = mutableState.value.importPlan ?: return
        if (mutableState.value.isAddingImport) return
        mutableState.update { it.copy(isAddingImport = true, importAddFailed = false) }
        viewModelScope.launch {
            val failed = plan.recipes.count { recipe -> runCatching { savedRecipes.save(recipe) }.isFailure }
            mutableState.update {
                if (failed == 0) {
                    it.copy(importPlan = null, isAddingImport = false, importsAdded = it.importsAdded + 1)
                } else {
                    it.copy(isAddingImport = false, importAddFailed = true)
                }
            }
            refresh()
        }
    }

    fun dismissImport() = mutableState.update { it.copy(importPlan = null, importAddFailed = false) }

    private fun readFile(file: RecipeFile): ImagoFileContent = when {
        file.linkError == RecipeLinkError.GONE -> ImagoFileContent.LinkGone
        file.linkError != null -> ImagoFileContent.LinkFailed
        else -> file.bytes?.let { decodeRecipeFile(file.name, it) } ?: ImagoFileContent.Unreadable
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
    onOpenAccount: () -> Unit = onOpenSettings,
    /** Files another app or the window handed over, waiting to be looked at. */
    incomingFiles: List<RecipeFile>? = null,
    onIncomingFilesTaken: () -> Unit = {},
    viewModel: RecipeLibraryViewModel = recipeLibraryViewModel(),
    editor: EditorViewModel = editorViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SavedRecipe?>(null) }
    var deleting by remember { mutableStateOf<SavedRecipe?>(null) }
    var fileFailed by remember { mutableStateOf(false) }
    var sampleIndex by rememberSampleIndex()
    val sampleAssetId = rememberSampleRecipeSource(sampleIndex)
    val importedCollection = stringResource(Res.string.editor_recipe_imported_collection)
    val recipeFiles = rememberRecipeFileActions(onOpened = { viewModel.openFiles(it, importedCollection) }, onFailed = { fileFailed = true })
    val links = rememberRecipeLinkController()
    val exporter = rememberRecipeExporter(recipeFiles, onFailed = { fileFailed = true }, onLink = links::publish)
    val community = rememberCommunityRecipesController()

    LaunchedEffect(incomingFiles) {
        if (incomingFiles != null) {
            viewModel.openFiles(incomingFiles, importedCollection)
            onIncomingFilesTaken()
        }
    }
    if (fileFailed) {
        AlertDialog(
            onDismissRequest = { fileFailed = false },
            text = { Text(stringResource(Res.string.editor_recipe_file_failed)) },
            confirmButton = { TextButton(onClick = { fileFailed = false }) { Text(stringResource(Res.string.editor_ok)) } },
        )
    }

    // Over the editor as well: a file that arrives while a recipe is open is looked at, and then back to it.
    state.importPlan?.let { plan ->
        BackHandler(onBack = viewModel::dismissImport)
        RecipeImportScreen(
            plan = plan,
            assetId = sampleAssetId,
            sampleIndex = sampleIndex,
            onSampleIndex = { sampleIndex = it },
            isAdding = state.isAddingImport,
            addFailed = state.importAddFailed,
            onBack = viewModel::dismissImport,
            onAdd = viewModel::addImported,
        )
        return
    }

    fun savedOf(cards: List<RecipeCard>) = cards.mapNotNull { card -> state.recipes.firstOrNull { it.id == card.id } }

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
            builtIn = builtInCards(state.builtInMarks),
            isLoading = state.isLoading,
            assetId = sampleAssetId,
            onBack = onBack,
            onSelect = { card ->
                editingKey = if (card.isBuiltIn) RecipeSource.builtInKey(card.id) else RecipeSource.savedKey(card.id)
            },
            onToggleFavorite = { card ->
                if (card.isBuiltIn) viewModel.toggleBuiltInFavorite(card.id) else viewModel.toggleFavorite(card.id)
            },
            onRename = { card -> renaming = state.recipes.firstOrNull { it.id == card.id } },
            onDuplicate = { viewModel.duplicate(it.id) },
            onDelete = { card -> deleting = state.recipes.firstOrNull { it.id == card.id } },
            onShareRecipes = if (exporter.canShare) { cards -> exporter.start(savedOf(cards), RecipeDelivery.SHARE) } else null,
            onShareRecipe = if (links.available) { card -> savedOf(listOf(card)).singleOrNull()?.let(links::open) } else null,
            onSaveRecipes = { cards -> exporter.start(savedOf(cards), RecipeDelivery.SAVE) },
            onSaveXmp = { card -> savedOf(listOf(card)).singleOrNull()?.let(exporter::startXmp) },
            onSaveCube = { card -> savedOf(listOf(card)).singleOrNull()?.let(exporter::startCube) },
            onImport = recipeFiles.open,
            showMineSignal = state.importsAdded,
            onCreate = { editingKey = RecipeSource.New().key },
            createLabel = stringResource(Res.string.editor_new_recipe),
            createHint = stringResource(Res.string.editor_new_recipe_hint),
            community = if (community.available) { modifier -> CommunityRecipesTab(community, modifier) } else null,
            bottomBar = {
                LibraryNavBar(
                    selected = LibraryNavDestination.RECIPES,
                    onSelect = onNavigate,
                    onOpenSettings = onOpenSettings,
                )
            },
        )
    }

    RecipeLinkDialog(
        links = links,
        onPublish = { links.target?.let { exporter.start(listOf(it), RecipeDelivery.LINK) } },
        onSendFile = if (exporter.canShare) { { links.target?.let { exporter.start(listOf(it), RecipeDelivery.SHARE) } } } else null,
        onSendLink = recipeFiles.shareText,
        onOpenAccount = onOpenAccount,
        onSaveImage = recipeFiles.saveAs,
        onShareImage = recipeFiles.shareImage,
        sampleIndex = sampleIndex,
        onSampleIndex = { sampleIndex = it },
    )
    RecipeExportDialog(exporter)
    CommunityRecipeDialog(community, sampleAssetId, onOwnPhoto = false, library = state.recipes, save = viewModel::addCopied)
    renaming?.let { recipe ->
        RecipeDetailsDialog(
            title = stringResource(Res.string.editor_organize_recipe),
            initialName = recipe.name,
            initialCollection = recipe.collection,
            collections = state.recipes.collections(),
            confirmLabel = stringResource(Res.string.editor_save_changes),
            onDismiss = { renaming = null },
            onConfirm = { name, collection, parts ->
                viewModel.rename(recipe.id, name, collection, parts)
                renaming = null
            },
            initialParts = recipe.appliedParts,
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
                ) { Text(stringResource(Res.string.editor_delete), color = ImagoUi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.editor_cancel)) } },
        )
    }
}
