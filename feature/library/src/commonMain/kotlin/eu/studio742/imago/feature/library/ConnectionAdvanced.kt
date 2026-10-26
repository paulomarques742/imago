package eu.studio742.imago.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.model.ClientCertificate
import eu.studio742.imago.core.model.ClientCertificateInfo
import eu.studio742.imago.core.model.ConnectionHeader
import eu.studio742.imago.core.model.ConnectionHeaders
import eu.studio742.imago.core.model.ConnectionProfile
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.stringResource

/** The attempt failed on something in front of the server — a proxy, a certificate it asks for — and not on the server itself. */
fun Throwable.isProtection(): Boolean = (this as? eu.studio742.imago.core.model.UserMessageException)?.userMessage in setOf(
    eu.studio742.imago.core.model.UserMessage.IMMICH_PROTECTED,
    eu.studio742.imago.core.model.UserMessage.IMMICH_CERTIFICATE_REQUIRED,
)

/** What the person typed, without the rows left empty. */
fun List<ConnectionHeader>.toProfile(certificate: ClientCertificate? = null) =
    ConnectionProfile(filterNot { it.name.isBlank() && it.value.isBlank() }, certificate)

/** Reading a certificate file and describing it: the repository's job, handed to the form as two functions. */
class CertificateSupport(
    val import: (file: ByteArray, password: String) -> ClientCertificate,
    val describe: (ClientCertificate) -> ClientCertificateInfo?,
)

/**
 * The «Advanced» part of a server form: what an address needs besides the API key, for servers
 * behind a proxy that checks something first — headers, and a client certificate. It is closed
 * unless the address already has some, since most people connect without it.
 *
 * The rows live in the caller, with the rest of the form, and may include blank ones: they only
 * count once something is typed in them. The certificate section shows only where [certificates]
 * is given.
 */
@Composable
fun ConnectionAdvanced(
    headers: List<ConnectionHeader>,
    onHeadersChange: (List<ConnectionHeader>) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = headers.isNotEmpty(),
    /** The last attempt failed because of something in front of the server: show where it is set. */
    highlight: Boolean = false,
    certificate: ClientCertificate? = null,
    onCertificateChange: (ClientCertificate?) -> Unit = {},
    certificates: CertificateSupport? = null,
) {
    var open by remember { mutableStateOf(initiallyOpen || certificate != null || highlight) }
    LaunchedEffect(highlight) { if (highlight) open = true }
    var reveal by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
        TextButton(onClick = { open = !open }) {
            Text(stringResource(Res.string.connection_advanced))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = ImagoSpacing.Xs).size(18.dp).rotate(if (open) 180f else 0f))
        }
        if (!open) return@Column
        // New and tried on few setups so far: said where the options are, not only in the release notes.
        Text(stringResource(Res.string.connection_advanced_testing), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Gold)
        Text(stringResource(Res.string.connection_advanced_hint), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            Text(stringResource(Res.string.connection_headers), style = MaterialTheme.typography.titleSmall,
                color = ImagoColors.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (reveal) Res.string.connection_hide_values else Res.string.connection_show_values))
            }
        }
        headers.forEachIndexed { index, header ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                OutlinedTextField(header.name, { onHeadersChange(headers.replaced(index, header.copy(name = it))) },
                    label = { Text(stringResource(Res.string.connection_header_name)) }, enabled = enabled, singleLine = true,
                    modifier = Modifier.weight(1f))
                OutlinedTextField(header.value, { onHeadersChange(headers.replaced(index, header.copy(value = it))) },
                    label = { Text(stringResource(Res.string.connection_header_value)) }, enabled = enabled, singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f))
                IconButton(enabled = enabled, onClick = { onHeadersChange(headers.filterIndexed { i, _ -> i != index }) }) {
                    Icon(Icons.Outlined.Close, stringResource(Res.string.connection_remove_header))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = enabled, onClick = { onHeadersChange(headers + ConnectionHeader("", "")) }) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Text(stringResource(Res.string.connection_add_header), Modifier.padding(start = ImagoSpacing.Sm))
            }
            // Fills the two headers Cloudflare Access reads from a service token, keeping any others.
            val missing = ConnectionHeaders.CLOUDFLARE_ACCESS.filter { name -> headers.none { it.name.equals(name, ignoreCase = true) } }
            if (missing.isNotEmpty()) TextButton(enabled = enabled, onClick = {
                val blanks = headers.filterNot { it.name.isBlank() && it.value.isBlank() }
                onHeadersChange(blanks + missing.map { ConnectionHeader(it, "") })
            }) { Text(stringResource(Res.string.connection_cloudflare_access)) }
        }
        if (certificates != null) ClientCertificateSection(certificate, onCertificateChange, certificates, enabled)
    }
}

@Composable
private fun ClientCertificateSection(
    certificate: ClientCertificate?,
    onCertificateChange: (ClientCertificate?) -> Unit,
    support: CertificateSupport,
    enabled: Boolean,
) {
    // The file picked and not yet opened with its password; it lives here and goes nowhere else.
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    val pick = rememberCertificateFilePicker { bytes ->
        if (bytes != null) { picked = bytes; password = ""; error = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
        Text(stringResource(Res.string.connection_certificate), style = MaterialTheme.typography.titleSmall, color = ImagoColors.TextPrimary)
        Text(stringResource(Res.string.connection_certificate_hint), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        val info = certificate?.let { remember(it) { support.describe(it) } }
        val file = picked
        when {
            certificate != null -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                    Column(Modifier.weight(1f)) {
                        if (info != null) {
                            Text(stringResource(Res.string.connection_certificate_for, info.subject, info.issuer), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
                            val until = validUntil(info)
                            Text(stringResource(Res.string.connection_certificate_valid_until, until), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
                            if (info.expired) Text(stringResource(Res.string.connection_certificate_expired_on, until), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Error)
                            else if (info.expiresSoon()) Text(stringResource(Res.string.connection_certificate_days_left, info.daysLeft()), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Error)
                        } else Text(stringResource(Res.string.connection_certificate_failed), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.Error)
                    }
                    IconButton(enabled = enabled, onClick = { onCertificateChange(null) }) {
                        Icon(Icons.Outlined.Close, stringResource(Res.string.connection_certificate_remove))
                    }
                }
            }
            file != null -> {
                OutlinedTextField(password, { password = it; error = null }, label = { Text(stringResource(Res.string.connection_certificate_password)) },
                    enabled = enabled, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                    Button(enabled = enabled, onClick = {
                        runCatching { support.import(file, password) }
                            .onSuccess { onCertificateChange(it); picked = null; password = ""; error = null }
                            .onFailure { error = it.toUiText(Res.string.connection_certificate_failed) }
                    }) { Text(stringResource(Res.string.connection_certificate_use)) }
                    TextButton(enabled = enabled, onClick = { picked = null; password = ""; error = null }) { Text(stringResource(Res.string.library_cancel)) }
                }
            }
            else -> OutlinedButton(enabled = enabled, onClick = pick) { Text(stringResource(Res.string.connection_certificate_import)) }
        }
        error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Error) }
    }
}

private fun validUntil(info: ClientCertificateInfo): String =
    java.time.Instant.ofEpochMilli(info.notAfterMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))

private fun <T> List<T>.replaced(index: Int, value: T) = mapIndexed { i, old -> if (i == index) value else old }
