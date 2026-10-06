package eu.studio742.imago.feature.account

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.feature.account.resources.*
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The share menu, with the ZIP served by the app's FileProvider (the cache's `share` folder). */
@Composable
actual fun rememberAccountExportDelivery(): suspend (String, ByteArray) -> UiText? {
    val context = LocalContext.current
    return remember(context) {
        { fileName, bytes ->
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "share").apply { mkdirs() }.resolve(fileName).apply { writeBytes(bytes) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    appString(Res.string.account_export_chooser),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        }
    }
}
