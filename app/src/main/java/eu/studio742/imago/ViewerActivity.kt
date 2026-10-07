package eu.studio742.imago

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import eu.studio742.imago.core.data.DeviceLibrary
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.designsystem.ActionGate
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.LocalActionGate
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import eu.studio742.imago.feature.library.OpenFailedScreen
import eu.studio742.imago.feature.library.toAssetUiModels
import eu.studio742.imago.feature.shell.HiltShellViewModel
import eu.studio742.imago.feature.shell.ImagoApp
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * What other apps open with IMAGO: "Open with", the camera's last photo — over the lock screen too —
 * and "Edit with". It is the whole app, started on that photo; going back past it closes this
 * activity, which returns to the app that asked.
 */
@AndroidEntryPoint
class ViewerActivity : ComponentActivity() {
    @Inject lateinit var languageSettings: AndroidLanguageSettings
    @Inject lateinit var device: DeviceLibrary
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var recipes: RecipeRepository
    private val shell: HiltShellViewModel by viewModels()

    private enum class Stage { LOADING, READY, FAILED }
    private val stage = mutableStateOf(Stage.LOADING)

    /** The camera with the phone locked: only what it sent is shown, and acting on it needs unlocking. */
    private val secure get() = intent.action == MediaStore.ACTION_REVIEW_SECURE

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (secure) setShowWhenLocked(true)
        languageSettings.refresh()
        // The shell's model survives a rotation, and with it the photo already open.
        if (shell.showsViewer) stage.value = Stage.READY else open()
        val gate = if (secure) ActionGate(::afterUnlocking) else ActionGate { action -> action() }
        setContent {
            val preference by languageSettings.preference.collectAsState()
            val systemLocale = LocalConfiguration.current.locales[0]
            CompositionLocalProvider(LocalLanguageSettings provides languageSettings, LocalActionGate provides gate) {
                ProvideAppLanguage(preference, systemLocale) {
                    ImagoTheme {
                        when (stage.value) {
                            Stage.LOADING -> Box(Modifier.fillMaxSize().background(Color.Black))
                            Stage.FAILED -> OpenFailedScreen(onClose = ::finish)
                            Stage.READY -> ImagoApp(shell, onLeaveViewer = ::finish)
                        }
                    }
                }
            }
        }
    }

    private fun open() {
        // The file comes in the data; some apps also, or only, put it in the ClipData.
        val uris = buildList {
            intent.data?.let(::add)
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.forEach(::add) }
        }.distinct().map(Uri::toString)
        lifecycleScope.launch {
            // Over the lock screen nothing beyond what the camera sent is shown — not even its album.
            val view = runCatching { device.openFromOtherApp(uris, neighbours = !secure) }.getOrNull()
            if (view == null) {
                stage.value = Stage.FAILED
                return@launch
            }
            val assets = view.toAssetUiModels(library, recipes)
            val editing = intent.action == Intent.ACTION_EDIT && !assets[view.index].isVideo
            shell.openViewer(assets, view.index, editing)
            stage.value = Stage.READY
        }
    }

    private fun afterUnlocking(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return action()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }
}
