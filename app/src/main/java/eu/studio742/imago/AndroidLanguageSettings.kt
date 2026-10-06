package eu.studio742.imago

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.studio742.imago.core.designsystem.i18n.AppLanguage
import eu.studio742.imago.core.designsystem.i18n.LanguagePreference
import eu.studio742.imago.core.designsystem.i18n.LanguageSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The language chosen for IMAGO, on Android.
 *
 * On Android 13+ the system itself stores the choice, through the per-app language
 * (`LocaleManager`): that way the Android settings and IMAGO's always agree, and changing one
 * changes the other. The system recreates the activity with the new configuration, and that is
 * where the app reads the language from. Android 12 has no per-app language, and the choice is
 * kept in a preference of our own.
 */
@Singleton
class AndroidLanguageSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : LanguageSettings {
    private val preferences = context.getSharedPreferences("imago_language", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    override val preference: StateFlow<LanguagePreference> = state.asStateFlow()

    override fun choose(preference: LanguagePreference) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                preference.language?.let { LocaleList.forLanguageTags(it.localeTag) } ?: LocaleList.getEmptyLocaleList()
        } else {
            preferences.edit().putString(KEY, preference.name).apply()
        }
        state.value = preference
    }

    /** Reads again: the choice may have changed in the Android settings while the app was asleep. */
    fun refresh() {
        state.value = read()
    }

    private fun read(): LanguagePreference {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val chosen = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (chosen.isEmpty) return LanguagePreference.SYSTEM
            return when (AppLanguage.of(chosen[0])) {
                AppLanguage.ENGLISH -> LanguagePreference.ENGLISH
                AppLanguage.PORTUGUESE -> LanguagePreference.PORTUGUESE
            }
        }
        return preferences.getString(KEY, null)
            ?.let { name -> LanguagePreference.entries.firstOrNull { it.name == name } }
            ?: LanguagePreference.SYSTEM
    }

    private companion object {
        const val KEY = "language"
    }
}
