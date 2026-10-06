package eu.studio742.imago

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import dagger.hilt.android.AndroidEntryPoint
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import kotlinx.coroutines.flow.map
import eu.studio742.imago.core.sync.AccountRepository
import eu.studio742.imago.core.sync.SyncEngine
import eu.studio742.imago.feature.account.toIndicator
import eu.studio742.imago.feature.shell.HiltShellViewModel
import eu.studio742.imago.feature.shell.ImagoApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var account: AccountRepository
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var languageSettings: AndroidLanguageSettings
    private val shell: HiltShellViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthLink(intent)
    }

    /** An account link (confirmation, recovery) opens the account, where the outcome is shown. */
    private fun handleAuthLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        if (account.handleAuthLink(link)) shell.openAccount()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The photo fills the screen to the edges; each screen decides where its chrome steps
        // back. On Android 15 with targetSdk 35 this becomes the enforced behaviour anyway, and
        // the bar colours declared in the theme stop having any effect.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val indicator = syncEngine.status.map { it.toIndicator() }
        languageSettings.refresh()
        setContent {
            val preference by languageSettings.preference.collectAsState()
            // The system language comes from the configuration, which on Android 13+ already carries the per-app language.
            val systemLocale = LocalConfiguration.current.locales[0]
            CompositionLocalProvider(LocalLanguageSettings provides languageSettings) {
                ProvideAppLanguage(preference, systemLocale) {
                    ImagoTheme { ImagoApp(shell, syncIndicator = indicator) }
                }
            }
        }
        if (savedInstanceState == null) handleAuthLink(intent)
    }
}
