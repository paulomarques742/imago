package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.resolve
import org.jetbrains.compose.resources.StringResource
import eu.studio742.imago.core.designsystem.i18n.toUiText
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import eu.studio742.imago.feature.library.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.SaveAlt
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.FolderLibraryRepository
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.NativeDialogs

@Composable
actual fun libraryViewModel(key: String?): LibraryViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel(key = key) { LibraryViewModel(graph.library, graph.recipes, graph.configuration, graph.folders) }
}

@Composable
actual fun librarySettingsViewModel(): LibrarySettingsViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { LibrarySettingsViewModel(graph.configuration, graph.folders) }
}

@Composable
actual fun albumPickerViewModel(): AlbumPickerViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { AlbumPickerViewModel(graph.library, graph.folders) }
}

@Composable
actual fun trashViewModel(): TrashViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { TrashViewModel(graph.library) }
}

@Composable
actual fun configurationViewModel(): ConfigurationViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { ConfigurationViewModel(graph.configuration) }
}

/** On desktop, giving access is choosing a folder in the Windows dialog. */
@Composable
actual fun rememberRequestMediaAccess(viewModel: LibrarySettingsViewModel, onResult: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val folders = viewModel.device as FolderLibraryRepository
    val currentOnResult by rememberUpdatedState(onResult)
    return {
        scope.launch {
            runCatching { NativeDialogs.pickFolder()?.let { folders.addFolder(it) } }
                .onFailure { viewModel.message.value = it.toUiText(Res.string.library_add_folder_failed) }
            currentOnResult()
        }
    }
}

@Composable
actual fun DeviceMediaActionHost(viewModel: LibrarySettingsViewModel) = Unit

@Composable
actual fun folderAlbumDeleteBody(count: Int): String =
    pluralStringResource(Res.plurals.library_album_delete_folder_body_computer, count, count)

@Composable
actual fun rememberMediaManagement(): MediaManagement? = null

actual val DeviceLibraryIcon: ImageVector get() = Icons.Outlined.Computer

actual val DeviceLibraryName: StringResource get() = Res.string.library_device_name_desktop

actual val WelcomeDeviceTitle: StringResource get() = Res.string.library_welcome_computer_title

actual val WelcomeDeviceBody: StringResource get() = Res.string.library_welcome_computer_body

/** The folders that make up this computer's library, each with its own way out. */
@Composable
actual fun ColumnScope.DeviceLibraryAccess(viewModel: LibrarySettingsViewModel, revision: Int, requestAccess: () -> Unit) {
    val folders = viewModel.device as FolderLibraryRepository
    val paths by folders.folders.collectAsState()
    val scope = rememberCoroutineScope()
    Text(remember(revision, paths) { folders.accessSummary() }.toUiText().resolve(),
        style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
    for (path in paths) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            Text(path, style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextPrimary, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { folders.removeFolder(path) } }) {
                Icon(Icons.Outlined.Close, stringResource(Res.string.library_remove_folder), tint = ImagoColors.TextSecondary)
            }
        }
    }
    OutlinedButton(onClick = requestAccess) {
        Icon(Icons.Outlined.CreateNewFolder, null)
        Text(stringResource(Res.string.library_add_folder))
    }
    FolderTransferSetting(folders)
}

/** On a computer sharing is keeping copies: the originals are downloaded straight into the folder picked. */
@Composable
actual fun rememberSelectionShare(): SelectionShare = remember {
    object : SelectionShare {
        override suspend fun prepare(): java.io.File? = NativeDialogs.pickFolder()?.toFile()

        override fun deliver(files: List<java.io.File>, mimeType: String): UiText =
            uiPlural(Res.plurals.library_selection_copies_saved, files.size, files.size, files.first().parent.orEmpty())
    }
}

actual val SelectionShareLabel: StringResource get() = Res.string.library_selection_save_copies

actual val SelectionShareIcon: ImageVector get() = Icons.Outlined.SaveAlt

actual val SelectionDeleteDeviceBody: StringResource get() = Res.string.library_selection_delete_computer_body

/** The computer has no background upload like the phone's share sheet yet, so the entry does not show. */
@Composable
actual fun rememberSendToImmich(): ((List<String>, String) -> Unit)? = null
