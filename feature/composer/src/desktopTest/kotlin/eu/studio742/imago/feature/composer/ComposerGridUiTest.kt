package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ComposerGridUiTest {
    private val members = List(4) { i -> CompositionElement.Shape("shape-$i", ElementTransform(NormalizedRect(.1f + i * .15f, .25f, .12f, .2f)),
        i, ShapeKind.RECTANGLE, fillArgb = if (i == 0) 0xFFAD6E44 else 0xFF477C85, strokeWidth = 0f) }
    private fun model(wide: Boolean = false): ComposerEditorViewModel {
        val elements = if (wide) members.mapIndexed { index, shape -> if (index < 2) shape else shape.copy(transform = shape.transform.copy(
            bounds = shape.transform.bounds.copy(x = shape.transform.bounds.x + 1f))) } else members
        val project = CompositionProject(id = "grid-ui", name = "Grid sample", format = PageFormatPreset.SQUARE_1_1.format,
            pages = if (wide) listOf(CompositionPage("page", 0), CompositionPage("second", 1)) else listOf(CompositionPage("page", 0)),
            elements = elements, createdAt = "", updatedAt = "")
        val projects = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(project))
            override suspend fun get(id: String) = project
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val f = FakeComposerRepos
        return ComposerEditorViewModel(projects, f.templates, f.media, f.recipes, f.exports, f.brandKits).also { vm ->
            vm.open(project.id); vm.beginMultiSelection(members.first().id); members.drop(1).forEach { vm.toggleElementInSelection(it.id) }
            vm.setArrangementReference(CompositionArrangementReference.PAGE)
        }
    }
    private var create = ""; private var cancel = ""; private var undo = ""; private var edit = ""
    @Composable private fun screen(vm: ComposerEditorViewModel, width: Int = 420, height: Int = 800, palette: ImagoUiPalette = ImagoUiPalette()) {
        create = stringResource(Res.string.composer_create_grid); cancel = stringResource(Res.string.composer_cancel)
        undo = stringResource(Res.string.composer_undo); edit = stringResource(Res.string.composer_edit_grid)
        ImagoTheme(palette) {
            CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width.dp), width.dp, height.dp)) {
                Box(Modifier.size(width.dp, height.dp).testTag("grid-ui-review")) {
                    ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                }
            }
        }
    }
    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-grid-review").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, name).writeBytes(data.bytes) }
        }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test(timeout = 30_000) fun `selection toolbar opens a cancellable grid preview with functional variants`() = runComposeUiTest {
        val vm = model(); val original = vm.state.value.project
        setContent { screen(vm) }
        onNodeWithText(create).performClick()
        onNodeWithTag("composer-grid-dialog").assertIsDisplayed()
        onNodeWithTag("grid-kind-FEATURE_LEFT").performScrollTo().performClick()
        assertEquals(CompositionLayoutKind.FEATURE_LEFT, vm.state.value.gridEditor!!.layout.kind)
        onNodeWithTag("grid-preview").assertIsDisplayed()
        capture(onNodeWithTag("composer-grid-dialog"), "grid-feature-preview-phone.png")
        onNodeWithText(cancel).performClick()
        assertEquals(original, vm.state.value.project); assertFalse(vm.state.value.canUndo)
    }

    @Test(timeout = 30_000) fun `thin separators join on the stage even when the media gutter is wider`() = runComposeUiTest {
        val vm = model(); vm.beginGrid()
        val draft = vm.state.value.gridEditor!!.layout.copy(margin = .05f, gap = .12f, separators = LayoutStroke(width = .01f))
        vm.updateGridLayout(draft); vm.confirmGrid(); vm.openGrid(draft.groupId)
        setContent { screen(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val stage = onNodeWithTag("composer_stage").fetchSemanticsNode().boundsInRoot
        val geometry = draft.geometry(1f)
        val upper = geometry.dividers.single { it.splitId == "row-0" }
        val middle = geometry.separators.single { it.splitId == "rows" }
        val x = (page.left - stage.left + upper.x1 * page.width).toInt()
        val y = (page.top - stage.top + (upper.y2 + middle.y1) / 2f * page.height).toInt()
        val pixel = onNodeWithTag("composer_stage").captureToImage().toPixelMap()[x, y]
        assertTrue("The stage separator broke at its media gutter", pixel.red > .8f && pixel.green > .8f && pixel.blue > .8f)
        capture(onNodeWithTag("composer_stage"), "grid-continuous-separators-phone.png")
    }

    @Test(timeout = 30_000) fun `confirming the preview exposes editable grid handles and undo restores free elements`() = runComposeUiTest {
        val vm = model(); vm.beginGrid()
        setContent { screen(vm) }
        onNodeWithTag("grid-confirm").performClick()
        onNodeWithText(edit).assertIsDisplayed()
        onNodeWithTag("grid-divider-rows-0").assertIsDisplayed()
        capture(onNodeWithTag("grid-ui-review"), "grid-selected-phone.png")
        onNodeWithContentDescription(undo).performClick()
        assertTrue(vm.state.value.project!!.layouts.isEmpty())
        assertTrue(vm.state.value.project!!.elements.all { it.groupId == null })
    }

    @Test(timeout = 30_000) fun `a stage divider drag changes the adjacent cells as one undo step`() = runComposeUiTest {
        val vm = model(); vm.beginGrid(); vm.confirmGrid()
        val original = vm.state.value.project!!
        setContent { screen(vm) }
        onNodeWithTag("grid-divider-rows-0").performTouchInput { swipe(center, center + Offset(0f, 45f), 300) }
        assertNotEquals(original.layouts, vm.state.value.project!!.layouts)
        onNodeWithContentDescription(undo).performClick()
        assertEquals(original.layouts, vm.state.value.project!!.layouts)
        assertEquals(original.elements, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `small windows keep cancel and apply reachable when the grid controls scroll`() = runComposeUiTest {
        val vm = model(); vm.beginGrid()
        setContent { screen(vm, width = 356, height = 560) }
        onNodeWithTag("grid-outer-width").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.025f) }
        onNodeWithTag("grid-confirm").assertIsDisplayed()
        onNodeWithText(cancel).assertIsDisplayed()
        capture(onNodeWithTag("composer-grid-dialog"), "grid-controls-small-window.png")
        onNodeWithTag("grid-confirm").performClick()
        assertEquals(.025f, vm.state.value.project!!.layouts.single().outline.width, .001f)
    }

    @Test(timeout = 30_000) fun `tablet and alternate theme follow UI roles while exported layout pixels stay unchanged`() = runComposeUiTest {
        val vm = model(); vm.beginGrid(); vm.setGridKind(CompositionLayoutKind.FEATURE_TOP); vm.confirmGrid()
        val project = vm.state.value.project!!; val before = CompositionRenderer().render(project, 0, emptyMap(), 240)
        val palette = ImagoUiPalette(background = Color(0xFFEEEAE3), surface = Color(0xFFF7F4EE), elevated = Color(0xFFE2DCD2),
            text = Color(0xFF20262B), secondaryText = Color(0xFF535E66), accent = Color(0xFF275F7C))
        setContent { screen(vm, width = 1000, palette = palette) }
        capture(onNodeWithTag("grid-ui-review"), "grid-selected-tablet-alternate.png")
        runOnIdle { vm.beginGrid() }
        onNodeWithTag("composer-grid-dialog").assertDoesNotExist()
        onNodeWithTag("grid-stage-settings").assertIsDisplayed()
        capture(onNodeWithTag("grid-ui-review"), "grid-preview-tablet-alternate.png")
        assertArrayEquals(before, CompositionRenderer().render(vm.state.value.project!!, 0, emptyMap(), 240))
    }

    @Test(timeout = 30_000) fun `divider controls leave other elements available to stage hit testing`() = runComposeUiTest {
        val vm = model(); vm.beginGrid(); vm.confirmGrid()
        val group = vm.state.value.project!!.layouts.single().groupId
        vm.addText("Sample")
        val id = vm.state.value.selectedElementId!!
        vm.selectGroup(group)
        setContent { screen(vm) }
        onNodeWithTag("composer_element_$id").performTouchInput { click(Offset(20f, center.y)) }
        assertEquals(id, vm.state.value.selectedElementId)
        assertEquals(setOf(id), vm.state.value.selectedElementIds)
    }

    @Test(timeout = 30_000) fun `selection area preview includes all intersected pages before confirmation`() = runComposeUiTest {
        val vm = model(wide = true); vm.setArrangementReference(CompositionArrangementReference.SELECTION); vm.beginGrid()
        setContent { screen(vm) }
        onNodeWithTag("grid-preview-page-0").assertIsDisplayed()
        onNodeWithTag("grid-preview-page-1").assertIsDisplayed()
        capture(onNodeWithTag("composer-grid-dialog"), "grid-multi-page-selection.png")
        onNodeWithTag("grid-confirm").performClick()
        assertEquals(4, vm.state.value.project!!.layouts.single().root.memberIds().size)
    }
}
