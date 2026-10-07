package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.library.resources.*
import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import eu.studio742.imago.core.designsystem.i18n.UiText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DeviceLibrary
import eu.studio742.imago.core.data.DeviceLibraryRepository
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.PendingMediaAction
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.model.FolderTransfer
import javax.inject.Inject

@HiltViewModel
class HiltLibraryViewModel @Inject constructor(
    library: LibraryRepository,
    recipes: RecipeRepository,
    configuration: ConfigurationRepository,
    device: DeviceLibrary,
    deviceCopies: DeviceCopies,
) : LibraryViewModel(library, recipes, configuration, device, deviceCopies)

@HiltViewModel
class HiltLibrarySettingsViewModel @Inject constructor(
    configuration: ConfigurationRepository,
    device: DeviceLibrary,
) : LibrarySettingsViewModel(configuration, device)

@HiltViewModel
class HiltAlbumPickerViewModel @Inject constructor(library: LibraryRepository, device: DeviceLibrary) :
    AlbumPickerViewModel(library, device)

@HiltViewModel
class HiltTrashViewModel @Inject constructor(library: LibraryRepository) : TrashViewModel(library)

@HiltViewModel
class HiltConfigurationViewModel @Inject constructor(repository: ConfigurationRepository) : ConfigurationViewModel(repository)

@Composable
actual fun libraryViewModel(key: String?): LibraryViewModel = hiltViewModel<HiltLibraryViewModel>(key = key)

@Composable
actual fun librarySettingsViewModel(): LibrarySettingsViewModel = hiltViewModel<HiltLibrarySettingsViewModel>()

@Composable
actual fun albumPickerViewModel(): AlbumPickerViewModel = hiltViewModel<HiltAlbumPickerViewModel>()

@Composable
actual fun trashViewModel(): TrashViewModel = hiltViewModel<HiltTrashViewModel>()

@Composable
actual fun configurationViewModel(): ConfigurationViewModel = hiltViewModel<HiltConfigurationViewModel>()

/** Shared by the source menu and settings, including partial-access reselection. */
@Composable
actual fun rememberRequestMediaAccess(viewModel: LibrarySettingsViewModel, onResult: () -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.device.refreshAccess()
        currentOnResult()
    }
    return {
        permissions.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }
}

/** Hosted once above navigation, so the operation survives the Android consent activity. */
@Composable
actual fun DeviceMediaActionHost(viewModel: LibrarySettingsViewModel) {
    val device = viewModel.device as DeviceLibraryRepository
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) device.refreshAccess()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var pending by remember { mutableStateOf<PendingMediaAction?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pending?.result?.complete(result.resultCode == Activity.RESULT_OK)
        pending = null
        device.refreshAccess()
    }
    LaunchedEffect(viewModel) {
        device.actions.collect { action ->
            pending = action
            try { launcher.launch(IntentSenderRequest.Builder(action.intent.intentSender).build()) }
            catch (error: Exception) { action.result.completeExceptionally(error); pending = null }
            runCatching { action.result.await() }
        }
    }
}

actual val DeviceFolderAlbums: Boolean get() = true

/**
 * Read again on every return to the app: it is given in the system's settings, outside it. The
 * media location goes first — without it a write request still shows the dialog. Android gives it
 * without asking to an app that already has the photos.
 */
@Composable
actual fun rememberMediaManagement(): MediaManagement? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(android.provider.MediaStore.canManageMedia(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = android.provider.MediaStore.canManageMedia(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var pendingDone by remember { mutableStateOf<() -> Unit>({}) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        granted = android.provider.MediaStore.canManageMedia(context)
        pendingDone()
    }
    val openSettings = {
        settings.launch(
            android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA,
                android.net.Uri.parse("package:${context.packageName}"),
            ),
        )
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { openSettings() }
    return remember(granted) {
        object : MediaManagement {
            override val granted = granted

            override fun request(onDone: () -> Unit) {
                pendingDone = onDone
                if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    openSettings()
                } else {
                    location.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }
            }
        }
    }
}

actual val DeviceLibraryIcon: ImageVector get() = Icons.Outlined.PhoneAndroid

actual val DeviceLibraryName: StringResource get() = Res.string.library_device_name

actual val WelcomeDeviceTitle: StringResource get() = Res.string.library_welcome_device_title

actual val WelcomeDeviceBody: StringResource get() = Res.string.library_welcome_device_body

@Composable
actual fun ColumnScope.DeviceLibraryAccess(viewModel: LibrarySettingsViewModel, revision: Int, requestAccess: () -> Unit) {
    Text(remember(revision) { viewModel.device.accessSummary() }.toUiText().resolve(),
        style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
    OutlinedButton(onClick = requestAccess) { Text(stringResource(Res.string.library_manage_access)) }
    rememberMediaManagement()?.let { management ->
        Text(stringResource(Res.string.library_media_management_title), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
        Text(
            stringResource(if (management.granted) Res.string.library_media_management_on else Res.string.library_media_management_off),
            style = MaterialTheme.typography.bodySmall,
            color = ImagoColors.TextSecondary,
        )
        OutlinedButton(onClick = { management.request() }) {
            Text(stringResource(if (management.granted) Res.string.library_media_management_change else Res.string.library_media_management_allow))
        }
    }
    // Where "Always do this" in the move-or-copy question is undone.
    val transfer by viewModel.device.rememberedTransfer.collectAsState()
    Text(stringResource(Res.string.library_folder_transfer_setting), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(eu.studio742.imago.core.designsystem.ImagoSpacing.Sm)) {
        listOf(
            null to Res.string.library_folder_transfer_ask,
            FolderTransfer.MOVE to Res.string.library_folder_transfer_move,
            FolderTransfer.COPY to Res.string.library_folder_transfer_copy,
        ).forEach { (option, label) ->
            androidx.compose.material3.FilterChip(
                selected = transfer == option,
                onClick = { viewModel.device.rememberTransfer(option) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.TextButton(onClick = {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")))
    }) { Text(stringResource(Res.string.library_open_android_permissions)) }
}

/** The cache folder the FileProvider serves (`imago_share_paths.xml`); emptied on every share. */
@Composable
actual fun rememberSelectionShare(): SelectionShare {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        object : SelectionShare {
            override suspend fun prepare(): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Nothing here deserves to outlive the Intent; originals left in cache would pile up with no owner.
                java.io.File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            }

            override fun deliver(files: List<java.io.File>, mimeType: String): UiText? {
                val uris = files.map { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", it) }
                val intent = if (uris.size == 1) {
                    android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris.single())
                } else {
                    android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
                        .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                }
                intent.type = mimeType
                // The read grant travels with the ClipData; EXTRA_STREAM alone only grants the first URI on some receivers.
                intent.clipData = android.content.ClipData.newRawUri(null, uris.first()).apply {
                    uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                }
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(android.content.Intent.createChooser(intent, null))
                return null
            }
        }
    }
}

actual val SelectionShareLabel: StringResource get() = Res.string.library_selection_share

actual val SelectionShareIcon: ImageVector get() = Icons.Outlined.Share

actual val SelectionDeleteDeviceBody: StringResource get() = Res.string.library_selection_delete_device_body

/**
 * The same sheet as sharing from another app, addressed to this app only: it asks for the library,
 * copies, and sends in the background with a notification.
 */
@Composable
actual fun rememberSendToImmich(): ((List<String>, String) -> Unit)? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        { assetIds, mimeType ->
            val uris = assetIds.map(android.net.Uri::parse)
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
                    .setPackage(context.packageName)
                    .setType(mimeType)
                    .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris)),
            )
        }
    }
}
