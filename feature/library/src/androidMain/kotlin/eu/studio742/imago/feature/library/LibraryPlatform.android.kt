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
import javax.inject.Inject

@HiltViewModel
class HiltLibraryViewModel @Inject constructor(
    library: LibraryRepository,
    recipes: RecipeRepository,
    configuration: ConfigurationRepository,
    device: DeviceLibrary,
) : LibraryViewModel(library, recipes, configuration, device)

@HiltViewModel
class HiltLibrarySettingsViewModel @Inject constructor(
    configuration: ConfigurationRepository,
    device: DeviceLibrary,
) : LibrarySettingsViewModel(configuration, device)

@HiltViewModel
class HiltConfigurationViewModel @Inject constructor(repository: ConfigurationRepository) : ConfigurationViewModel(repository)

@Composable
actual fun libraryViewModel(key: String?): LibraryViewModel = hiltViewModel<HiltLibraryViewModel>(key = key)

@Composable
actual fun librarySettingsViewModel(): LibrarySettingsViewModel = hiltViewModel<HiltLibrarySettingsViewModel>()

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

actual val DeviceLibraryIcon: ImageVector get() = Icons.Outlined.PhoneAndroid

actual val DeviceLibraryName: StringResource get() = Res.string.library_device_name

actual val WelcomeDeviceTitle: StringResource get() = Res.string.library_welcome_device_title

actual val WelcomeDeviceBody: StringResource get() = Res.string.library_welcome_device_body

@Composable
actual fun ColumnScope.DeviceLibraryAccess(viewModel: LibrarySettingsViewModel, revision: Int, requestAccess: () -> Unit) {
    Text(remember(revision) { viewModel.device.accessSummary() }.toUiText().resolve(),
        style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
    OutlinedButton(onClick = requestAccess) { Text(stringResource(Res.string.library_manage_access)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.TextButton(onClick = {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")))
    }) { Text(stringResource(Res.string.library_open_android_permissions)) }
}
