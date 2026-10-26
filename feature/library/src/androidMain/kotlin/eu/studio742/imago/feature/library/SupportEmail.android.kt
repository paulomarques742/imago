package eu.studio742.imago.feature.library

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun rememberSupportEmail(): suspend (SupportDraft) -> Unit {
    val context = LocalContext.current
    val chooserTitle = stringResource(Res.string.library_support_email)
    return { draft ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", draft.attachment)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("hello@742studio.eu"))
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("IMAGO diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // SENDTO discovers mail apps; SEND carries the attachment. Do not offer arbitrary share targets.
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:hello@742studio.eu"))
        val candidates = context.packageManager.queryIntentActivities(mail, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.distinct()
            .map { Intent(send).setPackage(it) }
            .filter { it.resolveActivity(context.packageManager) != null }
        check(candidates.isNotEmpty())
        context.startActivity(Intent.createChooser(candidates.first(), chooserTitle).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, candidates.drop(1).toTypedArray())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
