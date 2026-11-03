package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ComposerSelectionTest {
    private val left = CompositionElement.Shape("left", ElementTransform(NormalizedRect(.1f, .35f, .25f, .3f)), 0, ShapeKind.RECTANGLE)
    private val right = CompositionElement.Shape("right", ElementTransform(NormalizedRect(.65f, .35f, .25f, .3f)), 1, ShapeKind.RECTANGLE)
    private fun project(elements: List<CompositionElement>) = CompositionProject(id = "selection", name = "Selection",
        format = PageFormatPreset.SQUARE_1_1.format, pages = listOf(CompositionPage("page", 0)),
        elements = elements, createdAt = "", updatedAt = "")
    private fun model(elements: List<CompositionElement>): ComposerEditorViewModel {
        val source = project(elements)
        val repository = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(source))
            override suspend fun get(id: String) = source
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val f = FakeComposerRepos
        return ComposerEditorViewModel(repository, f.templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open(source.id) }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `locked commands cannot select or accidentally edit the previous selection`() {
        val vm = model(listOf(left, right.copy(locked = true)))
        vm.selectElement(left.id)
        vm.selectElement(right.id)
        vm.toggleElementInSelection(right.id)
        vm.deleteElement(right.id)
        vm.moveElement(right.id, .2f, .2f)
        assertEquals(left.id, vm.state.value.selectedElementId)
        assertEquals(listOf(left, right.copy(locked = true)), vm.state.value.project!!.elements)
        vm.toggleElementLocked(right.id)
        assertEquals(left.id, vm.state.value.selectedElementId)
        assertFalse(vm.state.value.project!!.elements.last().locked)
    }

    @Test fun `a locked group member prevents indirect edits and locking clears all selected members`() {
        val vm = model(listOf(left.copy(groupId = "g"), right.copy(groupId = "g", locked = true)))
        vm.selectElement(left.id)
        val before = vm.state.value.project
        vm.setShapeFill(0xFF00FF00)
        vm.moveSelected(.1f, .1f)
        vm.deleteSelected()
        vm.duplicateSelected()
        assertEquals(before, vm.state.value.project)
        vm.toggleElementLocked(right.id)
        vm.beginMultiSelection(left.id)
        vm.toggleElementInSelection(right.id)
        vm.toggleLocked()
        assertTrue(vm.state.value.project!!.elements.all { it.locked })
        assertTrue(vm.state.value.selectedElementIds.isEmpty())
        assertFalse(vm.state.value.multiSelect)
    }

    @Test(timeout = 30_000) fun `long press selects several and the selection frame does not cover other hit targets`() = runComposeUiTest {
        val vm = model(listOf(left, right))
        setContent {
            val state = vm.state.collectAsState().value
            ComposerCanvas(state.project!!, state, drawing = false, drawingKind = StrokeKind.PEN,
                multiSelect = state.multiSelect, positioningBackground = false, positioningMedia = false,
                previewUrl = { "" }, thumbnailUrl = { "" }, videoPlaybackUrl = { "" }, apiKey = { "" },
                actions = vm, onDrawing = {}, onPickMediaFor = {}, onFrameMedia = {},
                stageInsets = ComposerStageInsets(), modifier = Modifier.size(600.dp, 800.dp).testTag("stage"))
        }
        onNodeWithTag("stage").performTouchInput { click(Offset(width * .25f, centerY)) }
        assertEquals(left.id, vm.state.value.selectedElementId)
        onNodeWithTag("stage").performTouchInput { click(Offset(width * .75f, centerY)) }
        assertEquals(right.id, vm.state.value.selectedElementId)
        onNodeWithTag("stage").performTouchInput { longClick(Offset(width * .25f, centerY)) }
        assertTrue(vm.state.value.multiSelect)
        onNodeWithTag("stage").performTouchInput { click(Offset(width * .75f, centerY)) }
        assertEquals(setOf(left.id, right.id), vm.state.value.selectedElementIds)
        onNodeWithTag("stage").performTouchInput { click(center) }
        assertTrue(vm.state.value.selectedElementIds.isEmpty())
        assertFalse(vm.state.value.backgroundSelected)
    }

    @Test(timeout = 30_000) fun `a locked foreground lets the touch select the element underneath`() = runComposeUiTest {
        val cover = right.copy(transform = ElementTransform(NormalizedRect(0f, 0f, 1f, 1f)), locked = true)
        val vm = model(listOf(left, cover))
        setContent {
            val state = vm.state.collectAsState().value
            ComposerCanvas(state.project!!, state, drawing = false, drawingKind = StrokeKind.PEN, multiSelect = false,
                positioningBackground = false, positioningMedia = false, previewUrl = { "" }, thumbnailUrl = { "" },
                videoPlaybackUrl = { "" }, apiKey = { "" }, actions = vm, onDrawing = {}, onPickMediaFor = {},
                onFrameMedia = {}, stageInsets = ComposerStageInsets(), modifier = Modifier.size(600.dp, 800.dp).testTag("stage"))
        }
        onNodeWithTag("stage").performTouchInput { click(Offset(width * .25f, centerY)) }
        assertEquals(left.id, vm.state.value.selectedElementId)
    }
}
