package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ComposerGridInteractionUiTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val haptics = mutableListOf<HapticFeedbackType>()
    private val feedback = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
    }
    private val fixtures = mutableMapOf<String, File>()
    private fun sample(id: String): String = fixtures.getOrPut(id) {
        File(System.getProperty("java.io.tmpdir"), "imago-grid-interaction-$id.png").also { target ->
            val image = BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB)
            val color = if (id == "a") 0xD87B42 else 0x367C9E
            for (y in 0 until 160) for (x in 0 until 240) image.setRGB(x, y, if ((x / 30 + y / 30) % 2 == 0) color else color - 0x101010)
            ImageIO.write(image, "png", target)
        }
    }.toPath().toUri().toString()
    private fun model(): ComposerEditorViewModel {
        val elements = listOf("a", "b").mapIndexed { index, id -> CompositionElement.Photo(id,
            ElementTransform(NormalizedRect(.08f + index * .5f, .25f, .34f, .45f)), index,
            MediaReference(id, "hash-$id", "$id.png", width = 240, height = 160),
            PhotoRecipeSnapshot(EditRecipe(assetId = id, originalChecksum = "hash-$id", createdAt = "", updatedAt = ""))) }
        val project = CompositionProject(id = "grid-gestures", name = "Grid", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = elements, createdAt = "", updatedAt = "")
        val projects = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(project))
            override suspend fun get(id: String) = project
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val f = FakeComposerRepos
        val media = object : CompositionMediaRepository by f.media {
            override fun thumbnailUrl(assetId: String) = sample(assetId)
            override fun previewUrl(assetId: String) = sample(assetId)
        }
        return ComposerEditorViewModel(projects, f.templates, media, f.recipes, f.exports, f.brandKits).also { it.open(project.id) }
    }
    private fun ComposerEditorViewModel.grid(): String {
        beginMultiSelection("a"); toggleElementInSelection("b"); setArrangementReference(CompositionArrangementReference.PAGE)
        beginGrid(); confirmGrid(); return state.value.project!!.layouts.single().groupId
    }
    private var gridLabel = ""; private var addLabel = ""; private var cancelLabel = ""; private var gridAddLabel = ""
    private var pickerTarget: MediaPickerTarget? = null
    private var picked: ((List<ComposerMedia>) -> Unit)? = null
    private var dismiss: (() -> Unit)? = null
    @Composable private fun screen(vm: ComposerEditorViewModel, width: Int = 420, height: Int = 800) {
        gridLabel = stringResource(Res.string.composer_grid); addLabel = stringResource(Res.string.composer_add_elements)
        cancelLabel = stringResource(Res.string.composer_cancel)
        gridAddLabel = stringResource(Res.string.composer_grid_add_elements)
        ImagoTheme {
            CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width.dp), width.dp, height.dp),
                LocalHapticFeedback provides feedback, LocalComposerMediaPicker provides { target, onDismiss, onPicked ->
                    pickerTarget = target; picked = onPicked; dismiss = onDismiss
                    Surface(Modifier.fillMaxSize().testTag("sample-media-picker")) {}
                }) {
                Box(Modifier.size(width.dp, height.dp).testTag("grid-interaction-review")) {
                    ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                }
            }
        }
    }
    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-grid-interaction-review").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, name).writeBytes(data.bytes) }
        }
    }
    private fun incoming(id: String) = ComposerMedia(id, "hash-$id", "$id.png", width = 240, height = 160)
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test(timeout = 30_000) fun `double tap enters photo framing and pinch changes only its crop in one undo step`() = runComposeUiTest {
        val vm = model(); val before = vm.state.value.project!!
        setContent { screen(vm) }
        onNodeWithTag("composer_element_a").performTouchInput { doubleClick() }
        val pageBefore = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer_element_a").performTouchInput {
            down(0, center - Offset(20f, 0f)); down(1, center + Offset(20f, 0f))
            moveBy(0, Offset(-15f, 0f)); moveBy(1, Offset(15f, 0f)); move()
            moveBy(0, Offset(-10f, 0f)); moveBy(1, Offset(10f, 0f)); move(); up(0); up(1)
        }
        val after = vm.state.value.project!!.elements.first { it.id == "a" } as CompositionElement.Photo
        assertTrue("The pinch did not reach the photo", after.crop.scale > 1f)
        assertEquals(before.elements.first().transform, after.transform)
        assertEquals(pageBefore, onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot)
        vm.undo(); assertEquals(before.elements, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `double tap on an unselected grid opens a full screen stage without the modal or group selection`() = runComposeUiTest {
        val vm = model(); vm.grid(); vm.clearSelection()
        setContent { screen(vm) }
        onNodeWithTag("composer_element_a").performTouchInput { doubleClick() }
        onNodeWithTag("composer-grid-editor").assertIsDisplayed()
        onNodeWithTag("composer-grid-dialog").assertDoesNotExist()
        onNodeWithTag("composer-multiselection-hint").assertDoesNotExist()
        assertEquals(onNodeWithTag("grid-interaction-review").fetchSemanticsNode().boundsInRoot,
            onNodeWithTag("composer-grid-editor").fetchSemanticsNode().boundsInRoot)
        assertTrue(onNodeWithTag("grid-preview").fetchSemanticsNode().boundsInRoot.height > 550f)
        assertFalse(vm.state.value.multiSelect)
        assertNull(vm.state.value.editingGroupId)
        assertNotNull(vm.state.value.gridEditor)
    }

    @Test(timeout = 30_000) fun `holding and dragging between preview cells swaps their photos with haptic feedback and cancel restores them`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); val before = vm.state.value.project!!; vm.openGrid(group)
        setContent { screen(vm) }
        val preview = onNodeWithTag("grid-preview").fetchSemanticsNode().boundsInRoot
        val from = onNodeWithTag("grid-cell-a").fetchSemanticsNode().boundsInRoot.center - preview.topLeft
        val to = onNodeWithTag("grid-cell-b").fetchSemanticsNode().boundsInRoot.center - preview.topLeft
        onNodeWithTag("grid-preview").performTouchInput { down(from); advanceEventTime(650); moveTo((from + to) / 2f) }
        onNodeWithTag("grid-swap-feedback").assertIsDisplayed()
        onNodeWithTag("composer-multiselection-hint").assertDoesNotExist()
        assertFalse(vm.state.value.multiSelect)
        assertNull(vm.state.value.editingGroupId)
        capture(onNodeWithTag("composer-grid-editor"), "grid-photo-swap-drag.png")
        onNodeWithTag("grid-preview").performTouchInput { moveTo(to); up() }
        assertTrue(haptics.contains(HapticFeedbackType.LongPress))
        assertEquals(before.layouts.single().root.memberIds().reversed(), vm.state.value.gridEditor!!.layout.root.memberIds())
        assertEquals(before, vm.state.value.project)
        onNodeWithText(cancelLabel).performClick(); assertEquals(before, vm.state.value.project)
    }

    @Test(timeout = 30_000) fun `grid editor adds chosen media provisionally and picker cancellation retains the draft`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); val before = vm.state.value.project!!; vm.openGrid(group)
        setContent { screen(vm) }
        onNodeWithTag("grid-add-elements").performClick()
        onNodeWithTag("sample-media-picker").assertIsDisplayed()
        assertEquals(MediaPickerTarget.Grid(group, draft = true), pickerTarget)
        runOnIdle { dismiss!!.invoke() }; onNodeWithTag("composer-grid-editor").assertIsDisplayed()
        onNodeWithTag("grid-add-elements").performClick()
        runOnIdle { picked!!.invoke(listOf(incoming("c"))) }
        onNodeWithTag("composer-grid-editor").assertIsDisplayed()
        assertEquals(3, vm.state.value.gridEditor!!.layout.root.memberIds().size)
        assertEquals(before, vm.state.value.project)
        onNodeWithTag("grid-confirm").performClick()
        assertEquals(3, vm.state.value.project!!.elements.size)
        vm.undo(); assertEquals(before.elements, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `cancelling a held drag or dropping outside all cells keeps the draft unchanged`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); val before = vm.state.value.gridEditor
        setContent { screen(vm) }
        val preview = onNodeWithTag("grid-preview").fetchSemanticsNode().boundsInRoot
        val from = onNodeWithTag("grid-cell-a").fetchSemanticsNode().boundsInRoot.center - preview.topLeft
        onNodeWithTag("grid-preview").performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(20f, 0f)); cancel() }
        assertEquals(before, vm.state.value.gridEditor)
        onNodeWithTag("grid-preview").performTouchInput { down(from); advanceEventTime(650); moveTo(Offset(-10f, -10f)); up() }
        assertEquals(before, vm.state.value.gridEditor)
        onNodeWithTag("grid-swap-feedback").assertDoesNotExist()
    }

    @Test(timeout = 30_000) fun `holding a cell without dragging never enters multi selection and pinch navigates only the editing stage`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group)
        val before = vm.state.value.project; val draft = vm.state.value.gridEditor
        setContent { screen(vm) }
        val bounds = onNodeWithTag("grid-preview").fetchSemanticsNode().boundsInRoot
        val cell = onNodeWithTag("grid-cell-a").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        onNodeWithTag("grid-preview").performTouchInput { down(cell); advanceEventTime(650); up() }
        assertFalse(vm.state.value.multiSelect); assertNull(vm.state.value.editingGroupId)
        onNodeWithTag("composer-multiselection-hint").assertDoesNotExist()
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("grid-preview").performTouchInput {
            down(0, center - Offset(20f, 0f)); down(1, center + Offset(20f, 0f))
            moveBy(0, Offset(-15f, 0f)); moveBy(1, Offset(15f, 0f)); move()
            moveBy(0, Offset(-10f, 0f)); moveBy(1, Offset(10f, 0f)); move(); up(0); up(1)
        }
        assertNotEquals(page, onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot)
        assertEquals(before, vm.state.value.project); assertEquals(draft, vm.state.value.gridEditor)
    }

    @Test(timeout = 30_000) fun `full screen settings keep the stage and confirmation reachable in a small window`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); val before = vm.state.value.project!!
        setContent { screen(vm, width = 356, height = 560) }
        onNodeWithTag("grid-toggle-settings").performClick()
        onNodeWithTag("grid-kind-COLUMN").performScrollTo().performClick()
        onNodeWithTag("grid-preview").assertIsDisplayed()
        onNodeWithTag("grid-confirm").assertIsDisplayed()
        onNodeWithText(cancelLabel).assertIsDisplayed()
        capture(onNodeWithTag("composer-grid-editor"), "grid-fullscreen-small-window.png")
        assertEquals(before, vm.state.value.project)
        onNodeWithTag("grid-confirm").performClick()
        assertNull(vm.state.value.gridEditor)
        assertEquals(CompositionLayoutKind.COLUMN, vm.state.value.project!!.layouts.single().kind)
        vm.undo(); assertEquals(before.elements, vm.state.value.project!!.elements)
        assertEquals(before.layouts, vm.state.value.project!!.layouts)
    }

    @Test(timeout = 30_000) fun `selected grid submenu adds media directly without opening a draft`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); val before = vm.state.value.project!!
        setContent { screen(vm) }
        onNodeWithText(gridAddLabel).performScrollTo().performClick()
        onNodeWithTag("sample-media-picker").assertIsDisplayed(); assertEquals(MediaPickerTarget.Grid(group), pickerTarget)
        runOnIdle { picked!!.invoke(listOf(incoming("c"))) }
        assertNull(vm.state.value.gridEditor); assertEquals(3, vm.state.value.project!!.layouts.single().root.memberIds().size)
        vm.undo(); assertEquals(before.elements, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `central add grid opens multiple media selection and creates the complete grid as one edit`() = runComposeUiTest {
        val vm = model(); val before = vm.state.value.project!!
        setContent { screen(vm) }
        onNodeWithContentDescription(addLabel).performClick()
        onNodeWithText(gridLabel).performClick()
        onNodeWithTag("sample-media-picker").assertIsDisplayed(); assertEquals(MediaPickerTarget.NewGrid(0), pickerTarget)
        runOnIdle { dismiss!!.invoke() }; assertEquals(before, vm.state.value.project)
        onNodeWithText(gridLabel).performClick()
        runOnIdle { picked!!.invoke(listOf(incoming("c"), incoming("d"), incoming("e"))) }
        assertEquals(3, vm.state.value.project!!.layouts.single().root.memberIds().size)
        assertEquals(5, vm.state.value.project!!.elements.size)
        assertNull(vm.state.value.gridEditor)
        vm.undo(); assertEquals(before.elements, vm.state.value.project!!.elements); assertTrue(vm.state.value.project!!.layouts.isEmpty())
    }
}
