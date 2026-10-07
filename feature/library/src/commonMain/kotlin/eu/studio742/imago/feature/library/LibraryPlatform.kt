package eu.studio742.imago.feature.library

import eu.studio742.imago.core.model.LibrarySource
import eu.studio742.imago.feature.library.resources.Res
import eu.studio742.imago.feature.library.resources.library_unnamed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * What the library does differently on each platform. On Android: Hilt, the gallery permissions and
 * MediaStore's confirmation requests. On desktop: the data layer assembled by the app, and the
 * folders chosen in the Windows dialog.
 */

@Composable
expect fun libraryViewModel(key: String? = null): LibraryViewModel

@Composable
expect fun librarySettingsViewModel(): LibrarySettingsViewModel

@Composable
expect fun albumPickerViewModel(): AlbumPickerViewModel

@Composable
expect fun configurationViewModel(): ConfigurationViewModel

/**
 * The request for access to the device's photos: the permissions on Android, choosing folders on
 * desktop.
 *
 * [onResult] runs when the request closes, accepted or refused — the welcome only moves on to the
 * library after that, so the grid does not open empty under the dialog.
 */
@Composable
expect fun rememberRequestMediaAccess(viewModel: LibrarySettingsViewModel, onResult: () -> Unit = {}): () -> Unit

/**
 * Put in once, above navigation: on Android it launches MediaStore's confirmation requests
 * (favourite, delete) and reads access again when coming back to the app. On desktop there is nothing
 * to launch.
 */
@Composable
expect fun DeviceMediaActionHost(viewModel: LibrarySettingsViewModel = librarySettingsViewModel())

/**
 * Android's "media management apps" access: with it, moving and deleting the device's photos stop
 * asking for confirmation each time. Offered in the welcome and kept in Settings.
 */
interface MediaManagement {
    val granted: Boolean

    /** Opens where it is given; [onDone] runs on coming back, given or not. */
    fun request(onDone: () -> Unit = {})
}

/** Null where there is no such access: on desktop, and below Android 12. */
@Composable
expect fun rememberMediaManagement(): MediaManagement?

/** Whether this device's albums — its folders — can be changed from here: on the phone, yes. */
expect val DeviceFolderAlbums: Boolean

/** The icon of this device's library: the phone, the computer. */
expect val DeviceLibraryIcon: ImageVector

/** The name of this device's library. */
expect val DeviceLibraryName: StringResource

/**
 * The name to show for a library. The person names the Immich ones; the device and the libraries the
 * app only knows by id have no saved name, and get one here, in the language the app is in.
 */
@Composable
internal fun LibrarySource.displayName(): String = when {
    isDevice -> stringResource(DeviceLibraryName)
    name.isNotBlank() -> name
    else -> stringResource(Res.string.library_unnamed, id.take(8))
}

/** The "this device only" choice in the welcome: the title and what happens next. */
expect val WelcomeDeviceTitle: StringResource
expect val WelcomeDeviceBody: StringResource

/** The content of the device library's card in Settings. */
@Composable
expect fun ColumnScope.DeviceLibraryAccess(viewModel: LibrarySettingsViewModel, revision: Int, requestAccess: () -> Unit)

/** What the selection's share does: the share menu on Android; on desktop, copies in a folder. */
@Composable
expect fun rememberSelectionShare(): SelectionShare

expect val SelectionShareLabel: StringResource
expect val SelectionShareIcon: ImageVector

/** Where this device's photos go when deleted: the phone's trash, the Recycle Bin. */
expect val SelectionDeleteDeviceBody: StringResource

/**
 * Hands photos of this device — their ids in its library — to the upload to Immich, which asks for
 * the library and sends in the background. Null where the app has no such upload.
 */
@Composable
expect fun rememberSendToImmich(): ((assetIds: List<String>, mimeType: String) -> Unit)?
