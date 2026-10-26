package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import org.junit.Test
import eu.studio742.imago.core.designsystem.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class CollapsibleSettingsCardTest {
    @Test fun categoriesStartCollapsedAndExpandIndependentlyFromTheirHeaders() = runComposeUiTest {
        val collapsedLabel = runBlocking { getString(Res.string.settings_category_collapsed) }
        val expandedLabel = runBlocking { getString(Res.string.settings_category_expanded) }
        val preferenceBody = "Preference content"
        val libraryBody = "Library content"
        setContent {
            ImagoTheme {
                var preferences by remember { mutableStateOf(false) }
                var libraries by remember { mutableStateOf(false) }
                Column {
                    CollapsibleSettingsCard("Preferences", ImagoBrand.Wordmark, preferences, { preferences = it }) { Text(preferenceBody) }
                    CollapsibleSettingsCard("Libraries", ImagoBrand.Wordmark, libraries, { libraries = it }) { Text(libraryBody) }
                }
            }
        }
        onNodeWithText(preferenceBody).assertDoesNotExist()
        onNodeWithText(libraryBody).assertDoesNotExist()
        onNodeWithText("Preferences").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsedLabel))
        onNodeWithText("Preferences").performClick()
        onNodeWithText(preferenceBody).assertIsDisplayed()
        onNodeWithText("Libraries").performClick()
        onNodeWithText(libraryBody).assertIsDisplayed()
        onNodeWithText("Preferences").performClick()
        onNodeWithText(preferenceBody).assertDoesNotExist()
        onNodeWithText(libraryBody).assertIsDisplayed()
        onNodeWithText("Libraries").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expandedLabel))
    }
}
