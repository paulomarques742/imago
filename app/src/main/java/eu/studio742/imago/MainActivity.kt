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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import eu.studio742.imago.core.interchange.recipeLinkId
import eu.studio742.imago.feature.editor.LocalRecipeLinks
import eu.studio742.imago.feature.editor.LocalRecipeGallery
import eu.studio742.imago.feature.account.LocalSupportStore
import eu.studio742.imago.feature.account.PlaySupportStore
import eu.studio742.imago.feature.editor.readRecipeFiles
import eu.studio742.imago.feature.editor.readRecipeLink
import eu.studio742.imago.feature.editor.recipeFileUris
import kotlinx.coroutines.launch
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.LocalAppearanceSettings
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
    @Inject lateinit var appearanceSettings: AndroidAppearanceSettings
    private val shell: HiltShellViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthLink(intent)
        handleRecipeFiles(intent)
        handleRecipeLink(intent)
    }

    private fun handleRecipeLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val links = account.recipeLinks
        val site = links.baseUrl?.let { android.net.Uri.parse(it).host }
        val id = recipeLinkId(data.scheme, data.host, data.path, site) ?: return
        lifecycleScope.launch { shell.openRecipeFiles(listOf(readRecipeLink(links, id))) }
    }

    /** The read permission lasts while this activity does, so the files are read now and handed over as bytes. */
    private fun handleRecipeFiles(intent: Intent?) {
        val uris = intent?.recipeFileUris(this).orEmpty()
        if (uris.isEmpty()) return
        lifecycleScope.launch { shell.openRecipeFiles(readRecipeFiles(this@MainActivity, uris)) }
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
        // The support subscription is sold only with the programme open and the product configured.
        val supportStore = if (BuildConfig.SUPPORTERS_OPEN && BuildConfig.PLAY_SUPPORTER_PRODUCT_ID.isNotBlank()) {
            PlaySupportStore(this, BuildConfig.PLAY_SUPPORTER_PRODUCT_ID).also { store ->
                store.start()
                lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = store.close()
                })
            }
        } else null
        setContent {
            val preference by languageSettings.preference.collectAsState()
            // The system language comes from the configuration, which on Android 13+ already carries the per-app language.
            val systemLocale = LocalConfiguration.current.locales[0]
            CompositionLocalProvider(LocalRecipeLinks provides account.recipeLinks, LocalRecipeGallery provides account.recipeGallery, LocalSupportStore provides supportStore, LocalLanguageSettings provides languageSettings, LocalAppearanceSettings provides appearanceSettings) {
                ProvideAppLanguage(preference, systemLocale) {
                    ImagoTheme { ImagoApp(shell, syncIndicator = indicator) }
                }
            }
        }
        if (savedInstanceState == null) {
            handleAuthLink(intent)
            handleRecipeFiles(intent)
            handleRecipeLink(intent)
        }
    }
}
