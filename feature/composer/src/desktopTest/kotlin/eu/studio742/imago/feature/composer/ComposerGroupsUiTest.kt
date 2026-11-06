package eu.studio742.imago.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
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
class ComposerGroupsUiTest {
    private val a = CompositionElement.Shape("a", ElementTransform(NormalizedRect(.12f, .2f, .2f, .2f)), 0,
        ShapeKind.RECTANGLE, fillArgb = 0xFF3E787A, strokeArgb = 0xFFBB6544, strokeWidth = 8f)
    private val b = a.copy(id = "b", zIndex = 1, transform = a.transform.copy(bounds = NormalizedRect(.44f, .4f, .2f, .2f)))
    private val c = a.copy(id = "c", zIndex = 2, transform = a.transform.copy(bounds = NormalizedRect(.74f, .3f, .16f, .16f)))
    private fun model(elements: List<CompositionElement>): ComposerEditorViewModel {
        val source = CompositionProject(id = "groups-ui", name = "Group sample", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = elements, createdAt = "", updatedAt = "")
        val projects = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(source))
            override suspend fun get(id: String) = source
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val f = FakeComposerRepos
        return ComposerEditorViewModel(projects, f.templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open(source.id) }
    }
    private var group = ""; private var ungroup = ""; private var edit = ""; private var undo = ""; private var cancel = ""
    private var distribute = ""; private var page = ""; private var selection = ""; private var horizontal = ""; private var center = ""
    private var outline = ""; private var lock = ""; private var unlock = ""
    @Composable private fun labels() {
        group = stringResource(Res.string.composer_group); ungroup = stringResource(Res.string.composer_ungroup)
        edit = stringResource(Res.string.composer_edit_group); undo = stringResource(Res.string.composer_undo)
        cancel = stringResource(Res.string.composer_cancel); distribute = stringResource(Res.string.composer_distribute)
        page = stringResource(Res.string.composer_page_n, 1); selection = stringResource(Res.string.composer_reference_selection)
        horizontal = stringResource(Res.string.composer_distribute_horizontally); center = stringResource(Res.string.composer_center_horizontally)
        outline = stringResource(Res.string.composer_outline); lock = stringResource(Res.string.composer_lock); unlock = stringResource(Res.string.composer_unlock)
    }
    @Composable private fun screen(vm: ComposerEditorViewModel, width: Int = 420, palette: ImagoUiPalette = ImagoUiPalette()) {
        labels()
        ImagoTheme(palette) {
            CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width.dp), width.dp, 800.dp)) {
                Box(Modifier.size(width.dp, 800.dp).testTag("groups-review")) {
                    ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                }
            }
        }
    }
    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-groups-review").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, name).writeBytes(data.bytes) }
        }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test(timeout = 30_000) fun `temporary selection exposes the group submenu and undo restores separate elements`() = runComposeUiTest {
        val vm = model(listOf(a, b, c))
        vm.beginMultiSelection(a.id); vm.toggleElementInSelection(b.id)
        setContent { screen(vm) }
        onNodeWithTag("composer-selection-tools").assertIsDisplayed()
        onNodeWithText(group).assertIsDisplayed()
        onNodeWithText(distribute).assertIsDisplayed()
        onNodeWithText(ungroup).assertDoesNotExist()
        assertTrue(vm.state.value.project!!.elements.all { it.groupId == null })
        capture(onNodeWithTag("groups-review"), "temporary-selection-phone.png")
        onNodeWithText(group).performClick()
        onNodeWithText(ungroup).assertIsDisplayed()
        onNodeWithText(edit).assertIsDisplayed()
        assertFalse(vm.state.value.multiSelect)
        capture(onNodeWithTag("groups-review"), "group-selected-phone.png")
        onNodeWithContentDescription(undo).performClick()
        onNodeWithText(group).assertIsDisplayed()
        assertTrue(vm.state.value.project!!.elements.all { it.groupId == null })
    }

    @Test(timeout = 30_000) fun `editing a group lets a member use the existing outline controls independently`() = runComposeUiTest {
        val vm = model(listOf(a.copy(groupId = "g"), b.copy(groupId = "g"), c))
        vm.selectGroup("g")
        setContent { screen(vm) }
        onNodeWithText(edit).performClick()
        onNodeWithTag("composer-finish-group").assertIsDisplayed()
        onNodeWithTag("composer_element_b").performTouchInput { click(center) }
        assertEquals(setOf(b.id), vm.state.value.selectedElementIds)
        onNodeWithText(outline).performClick()
        onNodeWithTag("outline-no-fill").performClick()
        val elements = vm.state.value.project!!.elements
        assertEquals(a.copy(groupId = "g"), elements[0])
        assertEquals(0L, (elements[1] as CompositionElement.Shape).fillArgb ushr 24)
        onNodeWithTag("outline-close").performClick()
        capture(onNodeWithTag("groups-review"), "member-editing-phone.png")
        onNodeWithTag("composer-finish-group").performClick()
        assertNull(vm.state.value.editingGroupId)
        assertEquals(setOf(a.id, b.id), vm.state.value.selectedElementIds)
        onNodeWithText(ungroup).assertIsDisplayed()
    }

    @Test(timeout = 30_000) fun `combining existing groups needs confirmation and cancelling keeps membership`() = runComposeUiTest {
        val original = listOf(a.copy(groupId = "g"), b.copy(groupId = "g"), c)
        val vm = model(original)
        vm.selectGroup("g"); vm.toggleElementInSelection(c.id)
        setContent { screen(vm) }
        onNodeWithText(group).performClick()
        onNodeWithTag("composer-confirm-group-merge").assertIsDisplayed()
        assertEquals(original, vm.state.value.project!!.elements)
        onNodeWithText(cancel).performClick()
        assertEquals(original, vm.state.value.project!!.elements)
        onNodeWithText(group).performClick()
        onNodeWithTag("composer-confirm-group-merge").performClick()
        assertEquals(1, vm.state.value.project!!.elements.map { it.groupId }.distinct().size)
        onNodeWithContentDescription(undo).performClick()
        assertEquals(original, vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `layers expand groups and expose member selection and lock controls`() = runComposeUiTest {
        val vm = model(listOf(a.copy(groupId = "g"), b.copy(groupId = "g"), c))
        setContent {
            labels()
            ImagoTheme {
                Box(Modifier.size(264.dp, 720.dp).background(ImagoUi.colors.surface).testTag("layers-review")) {
                    ComposerLayersList(vm.state.collectAsState().value, vm, Modifier.fillMaxSize())
                }
            }
        }
        onNodeWithTag("composer-group-g").assertIsDisplayed()
        onNodeWithTag("composer-layer-a").assertDoesNotExist()
        onNodeWithTag("composer-expand-group-g").performClick()
        onNodeWithTag("composer-layer-a").performClick()
        assertEquals("g", vm.state.value.editingGroupId)
        assertEquals(a.id, vm.state.value.selectedElementId)
        onNodeWithTag("composer-layer-options-b").performClick()
        onNodeWithText(lock).performClick()
        val locked = vm.state.value.project!!.elements[1]
        assertTrue(locked.locked)
        runOnIdle { vm.moveSelected(.05f, .02f) }
        assertEquals(locked, vm.state.value.project!!.elements[1])
        onNodeWithTag("composer-group-options-g").performClick()
        onNodeWithText(unlock).performClick()
        assertTrue(vm.state.value.project!!.elements.none { it.locked })
        onNodeWithTag("composer-group-g").performClick()
        assertEquals(setOf(a.id, b.id), vm.state.value.selectedElementIds)
        capture(onNodeWithTag("layers-review"), "layers-expanded-phone.png")
    }

    @Test(timeout = 30_000) fun `distribution menu chooses page or selection reference without changing sizes`() = runComposeUiTest {
        val original = listOf(a, b, c)
        val vm = model(original)
        vm.beginMultiSelection(a.id); vm.toggleElementInSelection(b.id); vm.toggleElementInSelection(c.id)
        setContent { screen(vm) }
        onNodeWithText(distribute).performClick()
        onNodeWithText(page).performClick()
        onNodeWithText(horizontal).performClick()
        val boxes = vm.state.value.project!!.elements.map { it.transform.bounds }.sortedBy { it.x }
        assertEquals(0f, boxes.first().x, .00001f)
        assertEquals(1f, boxes.last().right, .00001f)
        original.zip(vm.state.value.project!!.elements).forEach { (x, y) -> assertEquals(x.transform.bounds.width, y.transform.bounds.width, 0f) }
        onNodeWithContentDescription(undo).performClick()
        onNodeWithText(distribute).performClick()
        onNodeWithText(selection).performClick()
        onNodeWithText(center).performClick()
        val aligned = vm.state.value.project!!.elements.map { it.transform.bounds }
        val middle = (original.minOf { it.transform.bounds.x } + original.maxOf { it.transform.bounds.right }) / 2f
        aligned.forEach { assertEquals(middle, it.x + it.width / 2f, .00001f) }
    }

    @Test(timeout = 30_000) fun `group controls follow an alternate tablet theme without changing exported content`() = runComposeUiTest {
        val vm = model(listOf(a.copy(groupId = "g"), b.copy(groupId = "g"), c))
        vm.selectGroup("g")
        var alternate by mutableStateOf(false)
        val palette = ImagoUiPalette(glass = Color(0xFF173E45), selectedSurface = Color(0xFF173E45),
            text = Color(0xFFE2FFFF), accent = Color.Cyan)
        val before = CompositionRenderer().render(vm.state.value.project!!, 0, emptyMap(), 240)
        setContent { screen(vm, 1024, if (alternate) palette else ImagoUiPalette()) }
        capture(onNodeWithTag("groups-review"), "group-selected-tablet.png")
        runOnIdle { alternate = true }
        capture(onNodeWithTag("groups-review"), "group-selected-alternate.png")
        onNodeWithText(edit).performClick()
        onNodeWithTag("composer-finish-group").assertIsDisplayed()
        capture(onNodeWithTag("groups-review"), "member-editing-tablet.png")
        val image = onNodeWithTag("groups-review").captureToImage().asSkiaBitmap()
        assertTrue((0 until image.height).any { y -> (0 until image.width).any { x -> image.getColor(x, y) == palette.accent.toArgb() } })
        assertArrayEquals(before, CompositionRenderer().render(vm.state.value.project!!, 0, emptyMap(), 240))
    }
}
