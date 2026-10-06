package eu.studio742.imago.desktop.platform

import eu.studio742.imago.core.designsystem.i18n.LanguagePreference
import eu.studio742.imago.core.designsystem.i18n.LanguageSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

/**
 * The language chosen for IMAGO on Windows, in a one-line file in the data folder.
 *
 * It does not go into the encrypted configuration: it is no secret at all, and it is read before
 * the window opens.
 */
class DesktopLanguageSettings(private val file: Path) : LanguageSettings {
    private val state = MutableStateFlow(read())

    override val preference: StateFlow<LanguagePreference> = state.asStateFlow()

    override fun choose(preference: LanguagePreference) {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, preference.name)
        }.onFailure { System.err.println("[Imago] Language not saved: ${it.message}") }
        state.value = preference
    }

    private fun read(): LanguagePreference = runCatching { Files.readString(file).trim() }.getOrNull()
        ?.let { name -> LanguagePreference.entries.firstOrNull { it.name == name } }
        ?: LanguagePreference.SYSTEM
}
