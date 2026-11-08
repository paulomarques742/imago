package eu.studio742.imago.desktop.platform

import eu.studio742.imago.core.designsystem.AppearancePreference
import eu.studio742.imago.core.designsystem.AppearanceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

/** The appearance chosen for IMAGO on Windows, in a one-line file in the data folder, as the language is. */
class DesktopAppearanceSettings(private val file: Path) : AppearanceSettings {
    private val state = MutableStateFlow(AppearancePreference.of(runCatching { Files.readString(file).trim() }.getOrNull()))

    override val preference: StateFlow<AppearancePreference> = state.asStateFlow()

    override fun choose(preference: AppearancePreference) {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, preference.name)
        }.onFailure { System.err.println("[Imago] Appearance not saved: ${it.message}") }
        state.value = preference
    }
}
