package eu.studio742.imago.core.designsystem.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.util.Locale

/**
 * The languages the app is translated into.
 *
 * English is the base of the resources (`composeResources/values`): whoever has the device in a
 * language that is not here sees the app in English. Portuguese is European Portuguese (`values-pt`).
 */
enum class AppLanguage(val tag: String, private val defaultLocale: Locale) {
    ENGLISH("en", Locale.ENGLISH),
    // `Locale.of` only exists from Java 19; the project compiles for 17.
    @Suppress("DEPRECATION")
    PORTUGUESE("pt", Locale("pt", "PT"));

    /** The BCP 47 tag to give the system: Portuguese is European Portuguese, and the system has to know. */
    val localeTag: String get() = defaultLocale.toLanguageTag()

    /**
     * The locale for formatting dates and numbers: the system's, if it is in this language — a UK
     * English user still sees the day before the month —, otherwise this language's.
     */
    fun formattingLocale(system: Locale): Locale = if (system.language == tag) system else defaultLocale

    companion object {
        /** The app language for a system locale: its own, if translated, otherwise English. */
        fun of(locale: Locale): AppLanguage = entries.firstOrNull { it.tag == locale.language } ?: ENGLISH
    }
}

/** What the person chose in Settings. [SYSTEM] follows the device language. */
enum class LanguagePreference(val language: AppLanguage?) {
    SYSTEM(null),
    ENGLISH(AppLanguage.ENGLISH),
    PORTUGUESE(AppLanguage.PORTUGUESE),
}

/**
 * Where each platform stores the choice: on Android 13+ the system's own per-app language, on
 * Android 12 and on desktop an app preference.
 */
interface LanguageSettings {
    val preference: StateFlow<LanguagePreference>
    fun choose(preference: LanguagePreference)
}

/** The platform's language settings, given by the app root. Null in tests and previews. */
val LocalLanguageSettings = staticCompositionLocalOf<LanguageSettings?> { null }

/** The locale the interface is being written in, for formatting dates and numbers. */
val LocalAppLocale = staticCompositionLocalOf { Locale.getDefault() }

/**
 * The app language outside the composition: for ViewModels, notifications and everything that writes
 * text without being inside a screen. Updated by [ProvideAppLanguage].
 */
object AppLocale {
    private val state = MutableStateFlow(Locale.getDefault())

    val current: StateFlow<Locale> = state.asStateFlow()

    val language: AppLanguage get() = AppLanguage.of(state.value)

    internal fun update(locale: Locale) {
        if (state.value == locale) return
        // Compose Multiplatform resources read the language from the process locale, inside and
        // outside the composition (on Android through Compose's `Locale.current`, which follows it).
        // It is the supported way to change the language without restarting the app.
        Locale.setDefault(locale)
        state.value = locale
    }
}

/**
 * The app language for everything inside: `stringResource` reads from this language instead of the
 * system's, and [AppLocale] learns which one it is.
 *
 * Changing the language recreates the content (`key`): resources are remembered by the composition,
 * and without this only the screens opened afterwards would change. The state that matters lives in
 * the ViewModels and survives.
 *
 * @param systemLocale the system locale, read before any app choice (on Android, the configuration's,
 *   which on 13+ already carries the per-app language).
 */
@Composable
fun ProvideAppLanguage(
    preference: LanguagePreference,
    systemLocale: Locale,
    content: @Composable () -> Unit,
) {
    val language = preference.language ?: AppLanguage.of(systemLocale)
    val locale = language.formattingLocale(systemLocale)
    AppLocale.update(locale)
    key(locale) {
        CompositionLocalProvider(LocalAppLocale provides locale, content = content)
    }
}

/** A text in the app language, outside the composition. */
suspend fun appString(resource: StringResource, vararg args: Any): String = getString(resource, *args)
