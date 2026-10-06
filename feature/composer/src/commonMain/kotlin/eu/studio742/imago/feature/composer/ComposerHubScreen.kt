package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.pluralStringResource
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoTabRow
import eu.studio742.imago.feature.library.LibraryNavBar
import eu.studio742.imago.feature.library.LibraryNavDestination

@Composable
fun ComposerHubRoute(
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNavigate: (LibraryNavDestination) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ComposerHubViewModel = composerHubViewModel(),
    brandKit: BrandKitViewModel = brandKitViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.openProjectId) {
        state.openProjectId?.let {
            viewModel.consumeOpen()
            onOpenProject(it)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it.resolveNow()); viewModel.consumeError() }
    }
    ComposerHubScreen(state, snackbar, onBack, onOpenProject, onNavigate, onOpenSettings, viewModel, brandKit)
}

/**
 * The list's tabs.
 *
 * Projects are what one came for; templates are where another one starts from. They used to be in the
 * same column, and the templates pushed the projects off screen every time a new one was saved. The
 * brand is what the compositions have in common: the palette, the fonts and the logos the composer's
 * brand panel draws on.
 */
private enum class ComposerTab { PROJECTS, TEMPLATES, BRAND }

@Composable
private fun ComposerHubScreen(
    state: ComposerHubUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNavigate: (LibraryNavDestination) -> Unit,
    onOpenSettings: () -> Unit,
    actions: ComposerHubViewModel,
    brandKit: BrandKitViewModel,
) {
    var tab by remember { mutableStateOf(ComposerTab.PROJECTS) }
    var pickingLogos by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CompositionProject?>(null) }
    var deleting by remember { mutableStateOf<CompositionProject?>(null) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ImagoColors.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                LibraryNavBar(
                    selected = LibraryNavDestination.COMPOSER,
                    onSelect = onNavigate,
                    onOpenSettings = onOpenSettings,
                )
            },
        ) { padding ->
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
                            contentDescription = stringResource(Res.string.composer_back),
                            tint = ImagoColors.TextPrimary,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.composer_compose),
                        style = MaterialTheme.typography.titleMedium,
                        color = ImagoColors.TextPrimary,
                    )
                    eu.studio742.imago.core.designsystem.SyncIndicator(Modifier.padding(start = ImagoSpacing.Sm))
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Button(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text(stringResource(Res.string.composer_new), Modifier.padding(start = 6.dp))
                    }
                }
                // Without saved templates their tab would have nothing behind it — and an empty tab
                // invites a tap that leads nowhere. The brand always exists.
                val tabs = ComposerTab.entries.filter { it != ComposerTab.TEMPLATES || state.templates.isNotEmpty() }
                ImagoTabRow(
                    options = tabs,
                    selected = tab.takeIf { it in tabs } ?: ComposerTab.PROJECTS,
                    label = { it.label() },
                    onSelect = { tab = it },
                    modifier = Modifier.padding(bottom = ImagoSpacing.Md),
                )
                when {
                    tab == ComposerTab.BRAND -> BrandKitEditor(brandKit, onPickLogos = { pickingLogos = true })

                    state.isLoading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = ImagoColors.Ivory) }

                    tab == ComposerTab.TEMPLATES && state.templates.isNotEmpty() -> CompositionGrid {
                        items(state.templates, key = { "template-${it.id}" }) { template ->
                            CompositionTile(
                                project = template.project,
                                name = template.name,
                                subtitle = template.project.subtitle(),
                                actions = actions,
                                onClick = { actions.createFromTemplate(template) },
                            )
                        }
                    }

                    state.projects.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(ImagoSpacing.Xxxl),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            text = stringResource(Res.string.composer_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ImagoColors.TextSecondary,
                        )
                    }

                    else -> CompositionGrid {
                        items(state.projects, key = CompositionProject::id) { project ->
                            CompositionTile(
                                project = project,
                                name = project.name,
                                subtitle = project.subtitle(),
                                actions = actions,
                                onClick = { onOpenProject(project.id) },
                                menu = { dismiss ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.composer_rename)) },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        onClick = { dismiss(); renaming = project },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.composer_duplicate)) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                        onClick = { dismiss(); actions.duplicate(project.id) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.composer_delete), color = ImagoColors.Danger) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ImagoColors.Danger) },
                                        onClick = { dismiss(); deleting = project },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (pickingLogos) {
            BrandLogoPicker(
                onDismiss = { pickingLogos = false },
                onPicked = { brandKit.addLogos(it); pickingLogos = false },
            )
        }
    }
    if (creating) {
        NewCompositionDialog(
            onDismiss = { creating = false },
            onCreate = { format, layout ->
                creating = false
                actions.create(format, layout)
            },
        )
    }
    renaming?.let { project ->
        var name by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text(stringResource(Res.string.composer_rename_composition)) },
            text = { TextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { actions.rename(project, name); renaming = null }) { Text(stringResource(Res.string.composer_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(Res.string.composer_cancel)) } },
        )
    }
    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text(stringResource(Res.string.composer_delete_composition_question)) },
            text = { Text(stringResource(Res.string.composer_delete_composition_body, project.name)) },
            confirmButton = { TextButton(onClick = { actions.delete(project.id); deleting = null }) { Text(stringResource(Res.string.composer_delete), color = ImagoColors.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.composer_cancel)) } },
        )
    }
}

/** The grid is the same as the recipe library's: three columns, the same spacing, the same tiles. */
@Composable
private fun CompositionGrid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ImagoSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        content = content,
    )
}

/**
 * A project — or a template — as the grid shows it.
 *
 * The preview is the first page drawn for real, and not a grid icon: among five compositions with
 * automatic names, it is the page that says which is which.
 */
@Composable
private fun CompositionTile(
    project: CompositionProject,
    name: String,
    subtitle: String,
    actions: ComposerHubViewModel,
    onClick: () -> Unit,
    menu: (@Composable ((() -> Unit) -> Unit))? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.Surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .background(ImagoColors.SurfaceElevated),
        ) {
            project.pages.firstOrNull()?.let { page ->
                CompositionPagePreview(
                    project = project,
                    page = page,
                    thumbnailUrl = actions::thumbnailUrl,
                    apiKey = actions::apiKey,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The same gradient as the recipe library: the page underneath may be light.
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
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = ImagoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (menu != null) {
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(Res.string.composer_options_for, name),
                            tint = ImagoColors.TextTertiary,
                            modifier = Modifier.size(ImagoSizes.IconDefault),
                        )
                    }
                    DropdownMenu(menuExpanded, { menuExpanded = false }) { menu { menuExpanded = false } }
                }
            }
        }
    }
}

@Composable
private fun CompositionProject.subtitle(): String =
    pluralStringResource(Res.plurals.composer_format_pages, pages.size, format.displayLabel(), pages.size)

@Composable
private fun ComposerTab.label(): String = stringResource(
    when (this) {
        ComposerTab.PROJECTS -> Res.string.composer_projects
        ComposerTab.TEMPLATES -> Res.string.composer_templates
        ComposerTab.BRAND -> Res.string.composer_brand
    },
)
