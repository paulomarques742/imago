package eu.studio742.imago

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import eu.studio742.imago.feature.library.SharedMediaLocation
import eu.studio742.imago.feature.library.ShareUploadSheet
import eu.studio742.imago.feature.library.ShareUploadViewModel
import javax.inject.Inject

/**
 * IMAGO in the Android share sheet: photos and videos from another app go to Immich.
 *
 * It opens on top of the app the share came from, in that app's task, and only shows the upload
 * sheet — it does not open the library. It closes when the person closes the sheet; the upload
 * carries on without it.
 *
 * The files are only read once the media location permission is settled: without it Android serves
 * the gallery's photos with their GPS wiped (see [SharedMediaLocation]).
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var languageSettings: AndroidLanguageSettings
    private val upload: ShareUploadViewModel by viewModels()

    /** The permission dialog is open; across a recreation its answer comes to the new activity. */
    private var askingForLocation = false
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askingForLocation = false
        upload.receive(sharedUris(intent), intent.type)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        languageSettings.refresh()
        val uris = sharedUris(intent)
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        when {
            savedInstanceState?.getBoolean(ASKING_FOR_LOCATION) == true -> askingForLocation = true
            SharedMediaLocation.shouldAsk(
                authorities = uris.map { it.authority },
                granted = checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED,
                askedBefore = preferences.getBoolean(LOCATION_ASKED, false),
            ) -> {
                preferences.edit().putBoolean(LOCATION_ASKED, true).apply()
                askingForLocation = true
                locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
            else -> upload.receive(uris, intent.type)
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ASKING_FOR_LOCATION, askingForLocation)
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

    private companion object {
        const val PREFERENCES = "imago-share"
        const val LOCATION_ASKED = "media-location-asked"
        const val ASKING_FOR_LOCATION = "asking-for-location"
    }
}
