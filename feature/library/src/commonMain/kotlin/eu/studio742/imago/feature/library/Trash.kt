@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.daysLeft
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A trashed photo as the screen shows it; [daysLeft] when the library dates each one. */
data class TrashItem(val asset: AssetUiModel, val daysLeft: Int?)

data class TrashUiState(
    val items: List<TrashItem> = emptyList(),
    /** How long a server keeps its trash; null on the phone, which dates each item. */
    val keptDays: Int? = null,
    val isLoading: Boolean = true,
    val working: Boolean = false,
    val selection: Set<String> = emptySet(),
    val error: UiText? = null,
    val message: UiText? = null,
)

/** The open library's trash: what is in it, and restoring or deleting for good. */
open class TrashViewModel(private val library: LibraryRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = mutableState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            runCatching { library.trash() }
                .onSuccess { contents ->
                    mutableState.update { current ->
                        current.copy(
                            items = contents.items.map { TrashItem(it.asset.toAssetUiModel(library, null), it.expiresAt?.let(::daysLeft)) },
                            keptDays = contents.keptDays,
                            isLoading = false,
                            selection = current.selection.intersect(contents.items.map { it.asset.id }.toSet()),
                        )
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(isLoading = false, error = error.toUiText(Res.string.library_trash_failed)) } }
        }
    }

    fun toggle(id: String) = mutableState.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    fun clearSelection() = mutableState.update { it.copy(selection = emptySet()) }

    fun restore(ids: List<String>) = act {
        library.restoreFromTrash(ids)
        uiPlural(Res.plurals.library_trash_restored, ids.size, ids.size)
    }

    fun deleteForever(ids: List<String>) = act {
        library.deleteForever(ids)
        null
    }

    fun empty() = act {
        library.emptyTrash()
        null
    }

    /** One at a time; a refused system confirmation is not an error and says nothing. */
    private fun act(block: suspend () -> UiText?) {
        if (mutableState.value.working) return
        mutableState.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                val message = block()
                mutableState.update { it.copy(working = false, selection = emptySet(), message = message) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(working = false) }
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(working = false, error = error.toUiText(Res.string.library_trash_failed)) }
            }
            load()
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null, error = null) }
}

@Composable
fun TrashRoute(onBack: () -> Unit, viewModel: TrashViewModel = trashViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    TrashScreen(
        state = state,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onClearSelection = viewModel::clearSelection,
        onRestore = viewModel::restore,
        onDeleteForever = viewModel::deleteForever,
        onEmpty = viewModel::empty,
        onConsumeMessage = viewModel::consumeMessage,
    )
}

/** What is asked before something leaves the trash for good. */
private sealed interface TrashConfirmation {
    data class Delete(val ids: List<String>) : TrashConfirmation
    data object Empty : TrashConfirmation
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashScreen(
    state: TrashUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRestore: (List<String>) -> Unit,
    onDeleteForever: (List<String>) -> Unit,
    onEmpty: () -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirming by remember { mutableStateOf<TrashConfirmation?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val selecting = state.selection.isNotEmpty()
    LaunchedEffect(state.message, state.error) {
        val message = state.error ?: state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message.resolveNow())
        onConsumeMessage()
    }
    androidx.compose.ui.backhandler.BackHandler(enabled = selecting && viewing == null, onBack = onClearSelection)
    androidx.compose.ui.backhandler.BackHandler(enabled = viewing != null) { viewing = null }
    androidx.compose.ui.backhandler.BackHandler(enabled = !selecting && viewing == null, onBack = onBack)
    Scaffold(
        containerColor = ImagoColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = ImagoSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = if (selecting) onClearSelection else onBack) {
                    Icon(
                        if (selecting) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(if (selecting) Res.string.library_clear_selection else Res.string.library_back_to_library),
                        tint = ImagoColors.TextPrimary,
                    )
                }
                Text(
                    if (selecting) pluralStringResource(Res.plurals.library_selected, state.selection.size, state.selection.size)
                    else stringResource(Res.string.library_trash),
                    style = MaterialTheme.typography.titleMedium,
                    color = ImagoColors.TextPrimary,
                    modifier = Modifier.weight(1f).padding(start = ImagoSpacing.Xs),
                )
                if (!selecting && state.items.isNotEmpty()) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(Res.string.library_trash_more), tint = ImagoColors.TextPrimary)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.library_trash_empty_action)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
                                onClick = { menu = false; confirming = TrashConfirmation.Empty },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (selecting || state.working) {
                TrashActions(
                    working = state.working,
                    onRestore = { onRestore(state.selection.toList()) },
                    onDelete = { confirming = TrashConfirmation.Delete(state.selection.toList()) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // A server keeps the whole trash for a number of days; the phone dates each item.
            val note = when {
                state.items.isEmpty() -> null
                state.keptDays != null -> pluralStringResource(Res.plurals.library_trash_server_kept, state.keptDays, state.keptDays)
                state.items.any { it.daysLeft != null } -> stringResource(Res.string.library_trash_device_note)
                else -> null
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Sm),
                )
            }
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ImagoColors.Ivory)
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.library_trash_empty_view), color = ImagoColors.TextSecondary)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = ImagoSpacing.Xs),
                    horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
                ) {
                    items(state.items, key = { it.asset.id }) { item ->
                        val index = state.items.indexOf(item)
                        TrashTile(
                            item = item,
                            selected = item.asset.id in state.selection,
                            modifier = Modifier.combinedClickable(
                                onClick = { if (selecting) onToggle(item.asset.id) else viewing = index },
                                onLongClick = { onToggle(item.asset.id) },
                            ),
                        )
                    }
                }
            }
        }
    }
    viewing?.let { start ->
        TrashViewer(
            items = state.items,
            start = start.coerceIn(0, (state.items.size - 1).coerceAtLeast(0)),
            working = state.working,
            onClose = { viewing = null },
            onRestore = { id -> onRestore(listOf(id)) },
            onDelete = { id -> confirming = TrashConfirmation.Delete(listOf(id)) },
        )
        // What the viewer showed may have just left: with nothing left, there is nothing to view.
        if (state.items.isEmpty()) viewing = null
    }
    confirming?.let { confirmation ->
        val count = (confirmation as? TrashConfirmation.Delete)?.ids?.size ?: state.items.size
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    if (confirmation is TrashConfirmation.Empty) stringResource(Res.string.library_trash_empty_question)
                    else pluralStringResource(Res.plurals.library_trash_delete_question, count, count),
                )
            },
            text = {
                Text(
                    if (confirmation is TrashConfirmation.Empty) pluralStringResource(Res.plurals.library_trash_empty_body, count, count)
                    else stringResource(Res.string.library_trash_delete_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    when (confirmation) {
                        is TrashConfirmation.Delete -> onDeleteForever(confirmation.ids)
                        TrashConfirmation.Empty -> onEmpty()
                    }
                }) {
                    Text(
                        stringResource(if (confirmation is TrashConfirmation.Empty) Res.string.library_trash_empty_action else Res.string.library_trash_delete_forever),
                        color = ImagoColors.Danger,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.library_cancel)) } },
        )
    }
}

@Composable
private fun TrashTile(item: TrashItem, selected: Boolean, modifier: Modifier) {
    val context = LocalPlatformContext.current
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(ImagoRadii.Small)).background(ImagoColors.SurfaceElevated)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(item.asset.thumbnailUrl).libraryAuth(item.asset.apiKey).crossfade(true).build(),
            contentDescription = item.asset.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.asset.isVideo) {
            Icon(Icons.Filled.PlayArrow, null, tint = ImagoColors.BrandWhite, modifier = Modifier.align(Alignment.Center).size(ImagoSizes.IconDefault))
        }
        item.daysLeft?.let { days ->
            Text(
                pluralStringResource(Res.plurals.library_trash_days_left, days, days),
                style = MaterialTheme.typography.labelSmall,
                color = ImagoColors.BrandWhite,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(ImagoSpacing.Xs)
                    .clip(RoundedCornerShape(ImagoRadii.Pill))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = ImagoSpacing.Sm, vertical = 2.dp),
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = stringResource(Res.string.library_asset_selected, item.asset.fileName),
                tint = ImagoColors.Ivory,
                modifier = Modifier.align(Alignment.TopStart).padding(ImagoSpacing.Xs).size(ImagoSizes.IconDefault),
            )
        }
    }
}

@Composable
private fun TrashActions(working: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ImagoColors.SurfaceElevated).navigationBarsPadding().height(ImagoSizes.TouchTarget + ImagoSpacing.Xl),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) {
            CircularProgressIndicator(color = ImagoColors.Ivory, modifier = Modifier.size(ImagoSizes.IconDefault))
            return@Row
        }
        TextButton(onClick = onRestore) {
            Icon(Icons.Outlined.RestoreFromTrash, null, tint = ImagoColors.Ivory)
            Text(stringResource(Res.string.library_trash_restore), color = ImagoColors.Ivory, modifier = Modifier.padding(start = ImagoSpacing.Sm))
        }
        TextButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteForever, null, tint = ImagoColors.Danger)
            Text(stringResource(Res.string.library_trash_delete_forever), color = ImagoColors.Danger, modifier = Modifier.padding(start = ImagoSpacing.Sm))
        }
    }
}

/** A trashed photo opened over the grid: seen whole, restored or deleted for good, nothing else. */
@Composable
private fun TrashViewer(
    items: List<TrashItem>,
    start: Int,
    working: Boolean,
    onClose: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (items.isEmpty()) return
    val context = LocalPlatformContext.current
    val pager = rememberPagerState(initialPage = start) { items.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val asset = items[page].asset
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(asset.previewUrl).libraryAuth(asset.apiKey).crossfade(true).build(),
                    contentDescription = asset.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (asset.isVideo) Icon(Icons.Filled.PlayArrow, null, tint = ImagoColors.BrandWhite, modifier = Modifier.size(ImagoSizes.TouchTarget))
            }
        }
        val current = items.getOrNull(pager.currentPage) ?: return@Box
        Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(ImagoSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.library_trash), tint = ImagoColors.BrandWhite)
            }
            current.daysLeft?.let { days ->
                Text(pluralStringResource(Res.plurals.library_trash_days_left, days, days), color = ImagoColors.TextSecondary, textAlign = TextAlign.Start)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            TrashActions(working = working, onRestore = { onRestore(current.asset.id) }, onDelete = { onDelete(current.asset.id) })
        }
    }
}
