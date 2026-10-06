package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.pluralStringResource
import eu.studio742.imago.core.designsystem.i18n.resolve
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.SheetHandle

/** The height at which the project list stops growing and starts scrolling. */
private val PROJECT_LIST_MAX_HEIGHT = 320.dp

/**
 * Choosing where the photos just selected go.
 *
 * It lives in the composer, and not in the library, because it decides about compositions: the caller
 * only needs to say which media it wants there and where to go once they are in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCompositionSheet(
    media: List<ComposerMedia>,
    onDismiss: () -> Unit,
    onOpenProject: (String) -> Unit,
    viewModel: AddToCompositionViewModel = addToCompositionViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.reset() }
    LaunchedEffect(state.openProjectId) {
        state.openProjectId?.let {
            viewModel.consumeOpen()
            onOpenProject(it)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ImagoColors.Surface,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ImagoSpacing.Lg)
                .padding(bottom = ImagoSpacing.Lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
        ) {
            Text(stringResource(Res.string.composer_add_to_composition), style = MaterialTheme.typography.titleLarge)
            Text(
                text = media.countLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
            )
            state.error?.let {
                Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Danger)
            }
            // What came in was saved, but what did not fit has to be said before the screen changes —
            // once the composer opens nobody notices what is missing.
            state.warning?.let { warning ->
                Text(warning.message.resolve(), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Gold)
                TextButton(onClick = viewModel::openWarned) { Text(stringResource(Res.string.composer_open_composition)) }
            }
            when {
                state.isWorking -> Box(
                    Modifier.fillMaxWidth().padding(ImagoSpacing.Xl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.warning != null -> Unit

                else -> {
                    NewProjectRow(onClick = { creating = true })
                    if (state.projects.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.composer_recent_projects),
                            style = MaterialTheme.typography.titleSmall,
                            color = ImagoColors.TextSecondary,
                        )
                        LazyColumn(
                            modifier = Modifier.heightIn(max = PROJECT_LIST_MAX_HEIGHT),
                            verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                        ) {
                            items(state.projects, key = CompositionProject::id) { project ->
                                ProjectRow(
                                    name = project.name,
                                    format = project.format,
                                    pages = project.pages.size,
                                    onClick = { viewModel.addTo(project.id, media) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewCompositionDialog(
            onDismiss = { creating = false },
            onCreate = { format, layout ->
                creating = false
                viewModel.createWith(format, layout, media)
            },
        )
    }
}

/**
 * A project on one row.
 *
 * The sheet chooses between existing projects and does not show them off: a thumbnail per row would
 * make it a second compose screen, when what is done here is only pointing to where the media goes.
 */
@Composable
private fun ProjectRow(
    name: String,
    format: PageFormat,
    pages: Int,
    onClick: () -> Unit,
) {
    Surface(
        color = ImagoColors.SurfaceElevated,
        shape = RoundedCornerShape(ImagoRadii.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(ImagoSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = ImagoColors.Graphite, shape = RoundedCornerShape(ImagoRadii.Small)) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GridView, null) }
            }
            Column(Modifier.weight(1f).padding(horizontal = ImagoSpacing.Md)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(Res.plurals.composer_format_pages, pages, format.displayLabel(), pages),
                    color = ImagoColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NewProjectRow(onClick: () -> Unit) {
    Surface(
        color = ImagoColors.SurfaceElevated,
        shape = RoundedCornerShape(ImagoRadii.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(ImagoSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = ImagoColors.Graphite, shape = RoundedCornerShape(ImagoRadii.Small)) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(ImagoSizes.IconLarge))
                }
            }
            Column(Modifier.padding(horizontal = ImagoSpacing.Md)) {
                Text(stringResource(Res.string.composer_new_composition), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(Res.string.composer_choose_format_layout),
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun List<ComposerMedia>.countLabel(): String =
    if (any(ComposerMedia::isVideo)) {
        pluralStringResource(Res.plurals.composer_count_items, size, size)
    } else {
        pluralStringResource(Res.plurals.composer_count_photos, size, size)
    }
