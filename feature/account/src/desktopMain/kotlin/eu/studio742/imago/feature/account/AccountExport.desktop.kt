package eu.studio742.imago.feature.account

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.account.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.designsystem.NativeDialogs
import java.nio.file.Files

/** The Windows save dialog. */
@Composable
actual fun rememberAccountExportDelivery(): suspend (String, ByteArray) -> UiText? = remember {
    { fileName, bytes ->
        val target = NativeDialogs.saveFile(fileName, listOf(NativeDialogs.Filter(appString(Res.string.account_zip_filter), listOf("zip"))))
        if (target == null) null else {
            withContext(Dispatchers.IO) { Files.write(target, bytes) }
            uiText(Res.string.account_exported_to, target.fileName.toString())
        }
    }
}
