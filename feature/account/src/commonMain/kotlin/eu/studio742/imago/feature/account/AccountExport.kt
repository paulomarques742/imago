package eu.studio742.imago.feature.account

import eu.studio742.imago.core.designsystem.i18n.UiText
import androidx.compose.runtime.Composable

/**
 * Where the account data ZIP goes: on the phone the share menu, on the computer the Windows save
 * dialog. Returns the message to show, or null if there is nothing to say.
 */
@Composable
expect fun rememberAccountExportDelivery(): suspend (fileName: String, bytes: ByteArray) -> UiText?
