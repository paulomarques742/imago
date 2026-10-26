package eu.studio742.imago.feature.library

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.util.Base64

@Composable
internal actual fun rememberSupportEmail(): suspend (SupportDraft) -> Unit = { draft ->
    withContext(Dispatchers.IO) {
        val encode = Base64.getMimeEncoder(76, "\r\n".toByteArray())
        val message = draft.attachment.resolveSibling(draft.attachment.nameWithoutExtension + ".eml")
        val boundary = "imago-support-mime"
        message.writeText(buildString {
            append("To: hello@742studio.eu\r\n")
            append("Subject: =?UTF-8?B?${Base64.getEncoder().encodeToString(draft.subject.toByteArray(Charsets.UTF_8))}?=\r\n")
            append("X-Unsent: 1\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
            append("--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(encode.encodeToString(draft.body.toByteArray(Charsets.UTF_8))).append("\r\n")
            append("--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Disposition: attachment; filename=\"${draft.attachment.name}\"\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(encode.encodeToString(draft.attachment.readBytes())).append("\r\n--$boundary--\r\n")
        }, Charsets.UTF_8)
        eu.studio742.imago.core.model.diagnostics.LocalDiagnostics.store?.cleanup()
        check(Desktop.isDesktopSupported())
        Desktop.getDesktop().open(message)
    }
}
