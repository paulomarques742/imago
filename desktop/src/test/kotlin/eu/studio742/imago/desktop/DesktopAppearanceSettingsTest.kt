package eu.studio742.imago.desktop

import eu.studio742.imago.core.designsystem.AppearancePreference
import eu.studio742.imago.desktop.platform.DesktopAppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopAppearanceSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = folder.root.toPath().resolve("data").resolve("appearance")

    @Test
    fun `with nothing stored the app follows the system`() {
        assertEquals(AppearancePreference.SYSTEM, DesktopAppearanceSettings(file).preference.value)
    }

    @Test
    fun `a choice survives closing the app`() {
        DesktopAppearanceSettings(file).choose(AppearancePreference.LIGHT)

        assertEquals(AppearancePreference.LIGHT, DesktopAppearanceSettings(file).preference.value)
    }

    @Test
    fun `a value this version does not know follows the system`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "SEPIA")

        assertEquals(AppearancePreference.SYSTEM, DesktopAppearanceSettings(file).preference.value)
    }
}
