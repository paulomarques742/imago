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
    private var gridLabel = ""; private var addLabel = ""; private var gridAddLabel = ""; private var adjustLabel = ""
    private var pickerTarget: MediaPickerTarget? = null
    private var picked: ((List<ComposerMedia>) -> Unit)? = null
    private var dismiss: (() -> Unit)? = null
    @Composable private fun screen(vm: ComposerEditorViewModel, width: Int = 420, height: Int = 800) {
        gridLabel = stringResource(Res.string.composer_grid); addLabel = stringResource(Res.string.composer_add_elements)
        gridAddLabel = stringResource(Res.string.composer_grid_add_elements)
        adjustLabel = stringResource(Res.string.composer_adjust)
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
    private fun ComposeUiTest.stage() = onNodeWithTag("composer_stage")
    private fun ComposeUiTest.onStage(tag: String): Offset = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center -
        stage().fetchSemanticsNode().boundsInRoot.topLeft
    private fun ComposerEditorViewModel.members() = state.value.project!!.layouts.single().root.memberIds()
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

    @Test(timeout = 30_000) fun `double tap on an unselected grid edits it on the normal stage without a modal`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.clearSelection()
        setContent { screen(vm) }
        onNodeWithTag("composer_element_a").performTouchInput { doubleClick() }
        onNodeWithTag("composer-grid-dialog").assertDoesNotExist()
        onNodeWithTag("composer-multiselection-hint").assertIsDisplayed()
        onNodeWithTag("composer-grid-tools").assertIsDisplayed()
        assertEquals(group, vm.state.value.editingGroupId)
        assertNull(vm.state.value.gridEditor); assertNull(vm.state.value.selectedElementId)
        assertFalse(vm.state.value.multiSelect)
        onNodeWithTag("composer-finish-group").performClick()
        assertNull(vm.state.value.editingGroupId)
        assertEquals(setOf("a", "b"), vm.state.value.selectedElementIds)
    }

    @Test(timeout = 30_000) fun `a tap selects the cell with its tools and another tap returns to the grid tools`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); val before = vm.state.value.project
        setContent { screen(vm) }
        val cell = onStage("grid-cell-a")
        stage().performTouchInput { click(cell) }
        assertEquals("a", vm.state.value.selectedElementId)
        onNodeWithTag("grid-selected-cell").assertExists()
        onNodeWithTag("composer-grid-tools").assertDoesNotExist()
        Thread.sleep(600) // The double tap window is measured on the wall clock.
        stage().performTouchInput { click(cell) }
        assertNull(vm.state.value.selectedElementId)
        assertEquals(group, vm.state.value.editingGroupId)
        onNodeWithTag("composer-grid-tools").assertIsDisplayed()
        assertEquals(before, vm.state.value.project)
    }

    @Test(timeout = 30_000) fun `a tap outside the grid leaves the mode`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group)
        setContent { screen(vm) }
        val above = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot.topCenter -
            stage().fetchSemanticsNode().boundsInRoot.topLeft - Offset(0f, 12f)
        stage().performTouchInput { click(above) }
        assertNull(vm.state.value.editingGroupId)
    }

    @Test(timeout = 30_000) fun `holding and dragging between cells swaps at once with haptic feedback and never pans the stage`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); val before = vm.state.value.project!!; vm.openGrid(group)
        setContent { screen(vm) }
        val from = onStage("grid-cell-a"); val to = onStage("grid-cell-b")
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        stage().performTouchInput { down(from); advanceEventTime(650); moveTo((from + to) / 2f) }
        onNodeWithTag("grid-swap-feedback").assertExists()
        assertFalse(vm.state.value.multiSelect)
        capture(onNodeWithTag("grid-interaction-review"), "grid-photo-swap-drag.png")
        stage().performTouchInput { moveTo(to); up() }
        assertTrue(haptics.contains(HapticFeedbackType.LongPress))
        assertEquals(before.layouts.single().root.memberIds().reversed(), vm.members())
        assertEquals(group, vm.state.value.editingGroupId)
        assertEquals(page, onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot)
        vm.undo(); assertEquals(before.elements, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `grid tools add chosen media at once and picker cancellation changes nothing`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); val before = vm.state.value.project!!; vm.openGrid(group)
        setContent { screen(vm) }
        onNodeWithText(gridAddLabel).performClick()
        onNodeWithTag("sample-media-picker").assertIsDisplayed()
        assertEquals(MediaPickerTarget.Grid(group), pickerTarget)
        runOnIdle { dismiss!!.invoke() }; assertEquals(before, vm.state.value.project)
        onNodeWithText(gridAddLabel).performClick()
        runOnIdle { picked!!.invoke(listOf(incoming("c"))) }
        assertEquals(3, vm.members().size)
        assertEquals(group, vm.state.value.editingGroupId)
        vm.undo(); assertEquals(before.elements, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `cancelling a held drag or dropping outside all cells changes nothing`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); val before = vm.state.value.project
        setContent { screen(vm) }
        val from = onStage("grid-cell-a")
        stage().performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(20f, 0f)); cancel() }
        assertEquals(before, vm.state.value.project)
        stage().performTouchInput { down(from); advanceEventTime(650); moveTo(Offset(-10f, -10f)); up() }
        assertEquals(before, vm.state.value.project)
        onNodeWithTag("grid-swap-feedback").assertDoesNotExist()
    }

    @Test(timeout = 30_000) fun `holding a cell without dragging never enters multi selection and pinch only navigates`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); val before = vm.state.value.project
        setContent { screen(vm) }
        val cell = onStage("grid-cell-a")
        stage().performTouchInput { down(cell); advanceEventTime(650); up() }
        assertFalse(vm.state.value.multiSelect); assertEquals(group, vm.state.value.editingGroupId)
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        stage().performTouchInput {
            down(0, center - Offset(20f, 0f)); down(1, center + Offset(20f, 0f))
            moveBy(0, Offset(-15f, 0f)); moveBy(1, Offset(15f, 0f)); move()
            moveBy(0, Offset(-10f, 0f)); moveBy(1, Offset(10f, 0f)); move(); up(0); up(1)
        }
        assertNotEquals(page, onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot)
        assertEquals(before, vm.state.value.project)
    }

    @Test(timeout = 30_000) fun `grid settings apply at once from the stage panel in a small window`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); val before = vm.state.value.project!!
        setContent { screen(vm, width = 356, height = 560) }
        onNodeWithText(adjustLabel).performClick()
        onNodeWithTag("grid-stage-settings").assertIsDisplayed()
        onNodeWithTag("grid-kind-COLUMN").performScrollTo().performClick()
        assertEquals(CompositionLayoutKind.COLUMN, vm.state.value.project!!.layouts.single().kind)
        capture(onNodeWithTag("grid-interaction-review"), "grid-settings-small-window.png")
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

    @Test(timeout = 30_000) fun `dragging a cell to the bin removes it at once with feedback and the grid closes the gap`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.addMediaToGrid(listOf(incoming("c")), group)
        val before = vm.state.value.project!!; val survivors = vm.members() - "a"; vm.openGrid(group)
        setContent { screen(vm) }
        onNodeWithTag("grid-trash").assertDoesNotExist()
        val from = onStage("grid-cell-a")
        stage().performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(20f, 0f)) }
        onNodeWithTag("grid-trash").assertIsDisplayed()
        val trash = onStage("grid-trash")
        stage().performTouchInput { moveTo(trash) }
        mainClock.advanceTimeBy(400)
        capture(onNodeWithTag("grid-interaction-review"), "grid-trash-drag.png")
        stage().performTouchInput { up() }
        assertEquals(2, haptics.count { it == HapticFeedbackType.LongPress })
        onNodeWithTag("grid-trash").assertDoesNotExist()
        assertEquals(survivors, vm.members())
        assertEquals(group, vm.state.value.editingGroupId)
        vm.undo()
        assertEquals(before.elements, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `the bin takes the second to last cell and the survivor is left in hand`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.openGrid(group)
        setContent { screen(vm) }
        val from = onStage("grid-cell-a")
        stage().performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(20f, 0f)) }
        val trash = onStage("grid-trash")
        stage().performTouchInput { moveTo(trash); up() }
        assertTrue(vm.state.value.project!!.layouts.isEmpty())
        assertEquals(listOf("b"), vm.state.value.project!!.elements.map { it.id })
        assertNull(vm.state.value.editingGroupId)
        assertEquals("b", vm.state.value.selectedElementId)
    }

    @Test(timeout = 30_000) fun `leaving the bin before letting go keeps the element and the swap still works`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); val members = vm.members(); vm.openGrid(group)
        setContent { screen(vm) }
        val from = onStage("grid-cell-a"); val to = onStage("grid-cell-b")
        stage().performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(20f, 0f)) }
        val trash = onStage("grid-trash")
        stage().performTouchInput { moveTo(trash); moveTo(to); up() }
        assertEquals(members.reversed(), vm.members())
    }

    @Test(timeout = 30_000) fun `each cell offers selection and removal to accessibility services`() = runComposeUiTest {
        val vm = model(); val group = vm.grid(); vm.addMediaToGrid(listOf(incoming("c")), group); vm.openGrid(group)
        var label = ""
        setContent { label = stringResource(Res.string.composer_grid_remove_element); screen(vm) }
        onNodeWithTag("grid-cell-a").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("a", vm.state.value.selectedElementId)
        val actions = onNodeWithTag("grid-cell-a").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        runOnIdle { actions.single { it.label == label }.action() }
        assertTrue("a" !in vm.members())
    }
}
