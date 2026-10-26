package eu.studio742.imago.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.i18n.LanguagePreference
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalTestApi::class)
class ImmichExportActionTest {
    @Test fun checkingPrecedesSendingAndTheExternalWarningFitsANarrowPhone() = runComposeUiTest {
        val origin = mutableStateOf(ImmichExportOrigin.CHECKING)
        var sends = 0
        setContent {
            ProvideAppLanguage(LanguagePreference.ENGLISH, Locale.ENGLISH) {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) { ImmichExportAction(origin.value, true) { sends++ } }
                }
            }
        }
        val send = runBlocking { getString(Res.string.editor_send_to_immich) }
        val warning = runBlocking { getString(Res.string.editor_export_external_library) }
        onNodeWithText(send).assertIsNotEnabled()
        assertEquals(0, sends)
        runOnIdle { origin.value = ImmichExportOrigin.EXTERNAL }
        onNodeWithText(warning).assertIsDisplayed()
        onNodeWithText(send).assertIsEnabled().assertIsDisplayed().performClick()
        assertEquals(1, sends)
    }

    @Test fun aFailedCheckExplainsTheDestinationAndInternalPhotosHaveNoWarning() = runComposeUiTest {
        val origin = mutableStateOf(ImmichExportOrigin.UNAVAILABLE)
        setContent {
            ProvideAppLanguage(LanguagePreference.PORTUGUESE, Locale.ENGLISH) {
                MaterialTheme { ImmichExportAction(origin.value, true) {} }
            }
        }
        val fallback = runBlocking { getString(Res.string.editor_export_origin_unavailable) }
        val warning = runBlocking { getString(Res.string.editor_export_external_library) }
        val send = runBlocking { getString(Res.string.editor_send_to_immich) }
        onNodeWithText(fallback).assertIsDisplayed()
        onNodeWithText(send).assertIsEnabled()
        runOnIdle { origin.value = ImmichExportOrigin.OTHER }
        onNodeWithText(fallback).assertDoesNotExist()
        onNodeWithText(warning).assertDoesNotExist()
        onNodeWithText(send).assertIsEnabled()
    }
}
