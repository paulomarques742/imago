package eu.studio742.imago.feature.composer

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

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerRepeatPagesTest {
    private val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(1.2f, .3f, .4f, .3f)),
        0, ShapeKind.RECTANGLE)
    private fun model(vararg elements: CompositionElement): ComposerEditorViewModel {
        val source = CompositionProject(id = "repeat", name = "Repeat", format = PageFormatPreset.SQUARE_1_1.format,
            pages = List(3) { CompositionPage("page-$it", it) }, elements = elements.toList(), createdAt = "", updatedAt = "")
        val repository = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(source))
            override suspend fun get(id: String) = source
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val f = FakeComposerRepos
        return ComposerEditorViewModel(repository, f.templates, f.media, f.recipes, f.exports, f.brandKits)
            .also { it.open(source.id); it.selectElement(shape.id) }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `repeat on all pages can be undone and redone without moving the original`() {
        val vm = model(shape)
        vm.repeatOnAllPages()
        val repeated = vm.state.value.project!!.elements.single()
        assertEquals(setOf(0, 1, 2), repeated.repeatOnPages)
        assertEquals(shape.transform.bounds, repeated.transform.bounds)
        assertEquals(1, repeated.repeatOriginPage())
        vm.undo()
        assertEquals(shape, vm.state.value.project!!.elements.single())
        vm.redo()
        assertEquals(repeated, vm.state.value.project!!.elements.single())
    }

    @Test fun `repeating is a separate undo even if an earlier slider never finished its gesture`() {
        val vm = model(shape)
        vm.beginGesture(stage = false)
        vm.setSelectedOpacity(.6f)
        val adjusted = vm.state.value.project!!.elements.single()
        vm.repeatOnAllPages()
        vm.undo()
        assertEquals(adjusted, vm.state.value.project!!.elements.single())
        vm.undo()
        assertEquals(shape, vm.state.value.project!!.elements.single())
    }

    @Test fun `repeating again does not create an empty undo step`() {
        val vm = model(shape)
        vm.repeatOnAllPages(); vm.repeatOnAllPages(); vm.undo()
        assertEquals(shape, vm.state.value.project!!.elements.single())
    }

    @Test fun `stopping repetition keeps a group at its original positions and is itself undoable`() {
        val first = shape.copy(groupId = "group")
        val second = first.copy(id = "second", transform = first.transform.copy(bounds = NormalizedRect(1.6f, .2f, .2f, .2f)))
        val vm = model(first, second)
        vm.repeatOnAllPages()
        val repeated = vm.state.value.project!!.elements
        assertTrue(vm.selectionRepeatsOnAllPages())
        vm.stopRepeatingSelected()
        assertFalse(vm.selectionRepeatsOnAllPages())
        assertEquals(listOf(first, second), vm.state.value.project!!.elements)
        vm.undo()
        assertEquals(repeated, vm.state.value.project!!.elements)
        vm.redo()
        assertEquals(listOf(first, second), vm.state.value.project!!.elements)
    }

    @Test fun `a locked group member prevents page repetition changes`() {
        val first = shape.copy(groupId = "group")
        val locked = first.copy(id = "locked", locked = true)
        val vm = model(first, locked)
        vm.repeatOnAllPages(); vm.stopRepeatingSelected()
        assertFalse(vm.state.value.canUndo)
        assertEquals(listOf(first, locked), vm.state.value.project!!.elements)
    }
}
