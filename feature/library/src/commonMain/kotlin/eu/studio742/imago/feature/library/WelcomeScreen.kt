@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.studio742.imago.core.designsystem.ImagoBrand
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The first launch: this device only, or an Immich server.
 *
 * It only appears once — the choice is saved in `ConfigurationRepository.welcomeCompleted` when one of
 * the two comes through, and not when it is tapped: whoever opens the Immich form and leaves the app
 * halfway sees both options again.
 *
 * [accountPrompt] is the account invitation, put in by whoever assembles the app: this feature does
 * not know the account.
 */
@Composable
fun WelcomeRoute(
    onFinished: () -> Unit,
    accountPrompt: @Composable () -> Unit = {},
    viewModel: ConfigurationViewModel = configurationViewModel(),
    settings: LibrarySettingsViewModel = librarySettingsViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var connectingImmich by rememberSaveable { mutableStateOf(false) }
    val mediaManagement = rememberMediaManagement()
    var offeringManagement by rememberSaveable { mutableStateOf(false) }
    // Access is asked for before finishing: the library only opens after the dialog closes, whatever
    // the answer — refusing is choosing too, and Settings allow asking again.
    val requestAccess = rememberRequestMediaAccess(settings) {
        viewModel.chooseDevice()
        // With the photos open, the phone can also stop asking for every move and delete: offered
        // here, once, and kept in Settings. Without access to the photos there is nothing to manage.
        val hasPhotos = settings.device.accessSummary().message != eu.studio742.imago.core.model.UserMessage.DEVICE_ACCESS_NONE
        if (mediaManagement != null && !mediaManagement.granted && hasPhotos) offeringManagement = true else onFinished()
    }
    BackHandler(enabled = connectingImmich && !state.isChecking) { connectingImmich = false }
    BackHandler(enabled = offeringManagement, onBack = onFinished)
    Surface(color = ImagoColors.Background, modifier = Modifier.fillMaxSize()) {
        // Without scroll or imePadding, the keyboard covered the connect button on a small screen and
        // there was no way to reach it. The Box centres the block when the screen is wider than 480 dp.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // The limit comes before the fill: the other way round, the width was already fixed by
                // the window and the block stretched from edge to edge on a wide screen.
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(horizontal = ImagoSpacing.Xxl, vertical = ImagoSpacing.Xxxl),
                horizontalAlignment = Alignment.Start,
            ) {
                if (offeringManagement && mediaManagement != null) {
                    MediaManagementOffer(
                        onAllow = { mediaManagement.request(onDone = onFinished) },
                        onSkip = onFinished,
                    )
                } else if (connectingImmich) {
                    ImmichConnectForm(
                        state = state,
                        onBack = { connectingImmich = false },
                        onServerUrlChange = viewModel::updateServerUrl,
                        onApiKeyChange = viewModel::updateApiKey,
                        onConnect = { viewModel.connect(onConnected = onFinished) },
                    )
                } else {
                    WelcomeChoice(
                        onChooseDevice = requestAccess,
                        onChooseImmich = { connectingImmich = true },
                        accountPrompt = accountPrompt,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeChoice(
    onChooseDevice: () -> Unit,
    onChooseImmich: () -> Unit,
    accountPrompt: @Composable () -> Unit,
) {
    Image(
        painter = rememberVectorPainter(ImagoBrand.Symbol),
        contentDescription = null,
        modifier = Modifier.size(52.dp),
    )
    Text(
        text = "IMAGO",
        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Normal, letterSpacing = 5.sp),
        color = ImagoColors.Ivory,
        modifier = Modifier.padding(top = ImagoSpacing.Lg),
    )
    Text(
        text = stringResource(Res.string.library_tagline),
        style = MaterialTheme.typography.titleMedium,
        color = ImagoColors.TextSecondary,
        modifier = Modifier.padding(top = ImagoSpacing.Xs),
    )
    Text(
        text = stringResource(Res.string.library_welcome_question),
        style = MaterialTheme.typography.titleLarge,
        color = ImagoColors.TextPrimary,
        modifier = Modifier.padding(top = ImagoSpacing.Xxxl, bottom = ImagoSpacing.Lg),
    )
    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
        WelcomeOption(
            icon = DeviceLibraryIcon,
            title = stringResource(WelcomeDeviceTitle),
            body = stringResource(WelcomeDeviceBody),
            onClick = onChooseDevice,
        )
        WelcomeOption(
            icon = Icons.Outlined.CloudQueue,
            title = stringResource(Res.string.library_welcome_immich_title),
            body = stringResource(Res.string.library_welcome_immich_body),
            onClick = onChooseImmich,
        )
    }
    Text(
        text = stringResource(Res.string.library_welcome_later),
        style = MaterialTheme.typography.bodySmall,
        color = ImagoColors.TextTertiary,
        modifier = Modifier.padding(top = ImagoSpacing.Md),
    )
    Box(Modifier.padding(top = ImagoSpacing.Xxxl)) { accountPrompt() }
}

/** The second step of "this device only": moving and deleting without a confirmation each time. */
@Composable
private fun MediaManagementOffer(onAllow: () -> Unit, onSkip: () -> Unit) {
    Text(
        text = stringResource(Res.string.library_media_management_offer_title),
        style = MaterialTheme.typography.titleLarge,
        color = ImagoColors.TextPrimary,
    )
    Text(
        text = stringResource(Res.string.library_media_management_offer_body),
        style = MaterialTheme.typography.bodyMedium,
        color = ImagoColors.TextSecondary,
        modifier = Modifier.padding(top = ImagoSpacing.Md),
    )
    Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().padding(top = ImagoSpacing.Xxl)) {
        Text(stringResource(Res.string.library_media_management_allow))
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().padding(top = ImagoSpacing.Sm)) {
        Text(stringResource(Res.string.library_media_management_skip))
    }
    Text(
        text = stringResource(Res.string.library_media_management_later),
        style = MaterialTheme.typography.bodySmall,
        color = ImagoColors.TextTertiary,
        modifier = Modifier.padding(top = ImagoSpacing.Md),
    )
}

@Composable
private fun WelcomeOption(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = ImagoColors.SurfaceElevated,
        shape = RoundedCornerShape(ImagoRadii.Medium),
        border = BorderStroke(1.dp, ImagoColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(ImagoSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
        ) {
            Icon(icon, null, tint = ImagoColors.Ivory)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = ImagoColors.TextTertiary)
        }
    }
}

@Composable
private fun ImmichConnectForm(
    state: ConfigurationUiState,
    onBack: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    // Inset by the button's own margin, so the arrow lines up with the title below.
    IconButton(onClick = onBack, enabled = !state.isChecking, modifier = Modifier.offset(x = -ImagoSpacing.Md).padding(bottom = ImagoSpacing.Sm)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.library_back), tint = ImagoColors.TextPrimary)
    }
    Text(
        text = stringResource(Res.string.library_welcome_immich_title),
        style = MaterialTheme.typography.headlineSmall,
        color = ImagoColors.TextPrimary,
    )
    Text(
        text = stringResource(Res.string.library_connect_intro),
        style = MaterialTheme.typography.bodyLarge,
        color = ImagoColors.TextSecondary,
        modifier = Modifier.padding(top = ImagoSpacing.Md, bottom = ImagoSpacing.Xxl),
    )
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(Res.string.library_server_url)) },
        placeholder = { Text("https://photos.example.com") },
        singleLine = true,
        enabled = !state.isChecking,
        shape = RoundedCornerShape(ImagoRadii.Medium),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(ImagoSpacing.Md))
    OutlinedTextField(
        value = state.apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(Res.string.library_api_key)) },
        singleLine = true,
        enabled = !state.isChecking,
        shape = RoundedCornerShape(ImagoRadii.Medium),
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (showKey) stringResource(Res.string.library_hide_key) else stringResource(Res.string.library_show_key),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    ImmichKeyPermissions(Modifier.padding(top = ImagoSpacing.Sm))
    state.error?.let {
        Text(
            text = it.resolve(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(top = ImagoSpacing.Md),
        )
    }
    Button(
        onClick = onConnect,
        enabled = !state.isChecking,
        shape = RoundedCornerShape(ImagoRadii.Medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = ImagoColors.Ivory,
            contentColor = ImagoColors.BrandBlack,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = ImagoSpacing.Xl).height(52.dp),
    ) {
        if (state.isChecking) {
            CircularProgressIndicator(
                color = ImagoColors.BrandBlack,
                strokeWidth = 2.dp,
                modifier = Modifier.height(24.dp),
            )
        } else {
            Text(stringResource(Res.string.library_connect_immich))
        }
    }
    Text(
        text = stringResource(Res.string.library_key_encrypted),
        style = MaterialTheme.typography.bodySmall,
        color = ImagoColors.TextTertiary,
        modifier = Modifier.padding(top = ImagoSpacing.Md),
    )
}
