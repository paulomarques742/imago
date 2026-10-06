package eu.studio742.imago

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import eu.studio742.imago.feature.library.ShareUploadSheet
import eu.studio742.imago.feature.library.ShareUploadViewModel
import javax.inject.Inject

/**
 * IMAGO in the Android share sheet: photos and videos from another app go to Immich.
 *
 * It opens on top of the app the share came from, in that app's task, and only shows the upload
 * sheet — it does not open the library. It closes when the person closes the sheet; the upload
 * carries on without it.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var languageSettings: AndroidLanguageSettings
    private val upload: ShareUploadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        languageSettings.refresh()
        upload.receive(sharedUris(intent), intent.type)
        setContent {
            val preference by languageSettings.preference.collectAsState()
            val systemLocale = LocalConfiguration.current.locales[0]
            CompositionLocalProvider(LocalLanguageSettings provides languageSettings) {
                ProvideAppLanguage(preference, systemLocale) {
                    ImagoTheme { ShareUploadSheet(onClose = ::finish, viewModel = upload) }
                }
            }
        }
    }

    /** The files come in `EXTRA_STREAM`; some apps only put them in the `ClipData`, and that counts too. */
    private fun sharedUris(intent: Intent): List<Uri> {
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (streams.isNotEmpty()) return streams
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}
