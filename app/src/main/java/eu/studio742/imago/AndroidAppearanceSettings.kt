package eu.studio742.imago

import android.app.UiModeManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.studio742.imago.core.designsystem.AppearancePreference
import eu.studio742.imago.core.designsystem.AppearanceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The appearance chosen for IMAGO, on Android.
 *
 * Besides the preference the app reads, the choice goes to the system as the per-app night mode: the
 * splash, the window behind the first frame and the resources in `values-night` are drawn before any
 * of our code runs, and only the system can have them follow the choice. The activities declare
 * `uiMode` in `configChanges`, so changing it repaints the screen instead of recreating it.
 */
@Singleton
class AndroidAppearanceSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppearanceSettings {
    private val preferences = context.getSharedPreferences("imago_appearance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(AppearancePreference.of(preferences.getString(KEY, null)))

    override val preference: StateFlow<AppearancePreference> = state.asStateFlow()

    override fun choose(preference: AppearancePreference) {
        preferences.edit().putString(KEY, preference.name).apply()
        state.value = preference
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(
            when (preference) {
                AppearancePreference.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                AppearancePreference.LIGHT -> UiModeManager.MODE_NIGHT_NO
                AppearancePreference.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    private companion object {
        const val KEY = "appearance"
    }
}
