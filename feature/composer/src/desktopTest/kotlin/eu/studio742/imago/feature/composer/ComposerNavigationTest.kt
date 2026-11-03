package eu.studio742.imago.feature.composer

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.asSkiaBitmap
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ComposerNavigationTest {
    private val original = CompositionProject(id = "project", name = "My composition", format = PageFormatPreset.SQUARE_1_1.format,
        pages = listOf(CompositionPage("page", 0)), createdAt = "", updatedAt = "")

    private class Projects(var stored: CompositionProject) : CompositionRepository {
        var fail = false
        var writes = 0
        override fun observeAll() = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored.takeIf { it.id == id }
        override suspend fun save(project: CompositionProject) {
            if (fail) error("disk unavailable")
            writes++
            stored = project
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
    }

    private fun viewModel(projects: Projects): ComposerEditorViewModel {
        val fakes = FakeComposerRepos
        return ComposerEditorViewModel(projects, fakes.templates, fakes.media, fakes.recipes, fakes.exports, fakes.brandKits)
            .also { it.open(original.id) }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `add opens insertion while save offers draft and export and models live in more`() = runComposeUiTest {
        val vm = viewModel(Projects(original))
        var add = ""; var media = ""; var save = ""; var draft = ""; var export = ""; var more = ""; var template = ""
        setContent {
            add = stringResource(Res.string.composer_add_elements)
            media = stringResource(Res.string.composer_media)
            save = stringResource(Res.string.composer_save)
            draft = stringResource(Res.string.composer_save_draft)
            export = stringResource(Res.string.composer_export)
            more = stringResource(Res.string.composer_more)
            template = stringResource(Res.string.composer_save_as_template)
            ImagoTheme { ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {}) }
        }
        onNodeWithText(original.name).assertExists()
        onNodeWithContentDescription(media).assertDoesNotExist()
        onNodeWithContentDescription(template).assertDoesNotExist()
        onNodeWithContentDescription(add).performClick()
        onNodeWithContentDescription(media).assertExists()
        val card = onNodeWithTag("composer-context-card").fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag("composer-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(bar.center.x, card.center.x, .5f)
        assertTrue(card.width < bar.width)
        val output = File(System.getProperty("java.io.tmpdir"), "imago-composer-review").apply { mkdirs() }
        File(output, "navigation-tablet-open.png").writeBytes(
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        onNodeWithContentDescription(add).performClick()
        onNodeWithContentDescription(save).performClick()
        onNodeWithText(draft).assertExists()
        onNodeWithText(export).assertExists()
        onNodeWithText(draft).performClick()
        onNodeWithContentDescription(more).performClick()
        onNodeWithText(template).assertExists()
    }

    @Test fun `failed draft save keeps edits and retry updates the same project`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val projects = Projects(original)
        val vm = viewModel(projects)
        projects.fail = true
        vm.addText("A change")
        vm.saveDraft()
        advanceUntilIdle()
        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.isSaving)
        assertEquals(1, vm.state.value.project!!.elements.size)
        assertEquals(0, projects.writes)
        projects.fail = false
        vm.saveDraft()
        advanceUntilIdle()
        assertFalse(vm.state.value.saveFailed)
        assertEquals(original.id, projects.stored.id)
        assertEquals(1, projects.stored.elements.size)
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(1, projects.writes)
    }
}
