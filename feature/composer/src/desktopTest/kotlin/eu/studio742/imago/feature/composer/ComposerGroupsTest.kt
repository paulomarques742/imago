package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerGroupsTest {
    private val a = CompositionElement.Shape("a", ElementTransform(NormalizedRect(.1f, .2f, .15f, .2f)),
        0, ShapeKind.RECTANGLE, fillArgb = 0x804488AA, strokeWidth = 12f)
    private val b = a.copy(id = "b", zIndex = 1, transform = a.transform.copy(bounds = NormalizedRect(.35f, .4f, .1f, .15f)))
    private val c = a.copy(id = "c", zIndex = 2, transform = a.transform.copy(bounds = NormalizedRect(.6f, .3f, .12f, .1f)))
    private val d = a.copy(id = "d", zIndex = 3, transform = a.transform.copy(bounds = NormalizedRect(.85f, .5f, .1f, .2f)))
    private fun source(elements: List<CompositionElement>, pages: Int = 1) = CompositionProject(id = "groups", name = "Groups",
        format = PageFormatPreset.SQUARE_1_1.format, pages = List(pages) { CompositionPage("page-$it", it) },
        elements = elements, createdAt = "", updatedAt = "")
    private class Projects(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll() = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored
        override suspend fun save(project: CompositionProject) {
            stored = Json.decodeFromString(Json.encodeToString(CompositionProject.serializer(), project))
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
    }
    private fun model(elements: List<CompositionElement>, templates: CompositionTemplateRepository = FakeComposerRepos.templates, pages: Int = 1): ComposerEditorViewModel {
        val f = FakeComposerRepos
        return ComposerEditorViewModel(Projects(source(elements, pages)), templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open("groups") }
    }
    private fun ComposerEditorViewModel.elements() = state.value.project!!.elements
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `temporary selection changes neither the project nor persistent membership`() {
        val vm = model(listOf(a, b))
        val before = vm.state.value.project
        vm.beginMultiSelection(a.id); vm.toggleElementInSelection(b.id)
        assertEquals(before, vm.state.value.project)
        assertFalse(vm.state.value.canUndo)
        assertTrue(vm.elements().all { it.groupId == null })
        vm.clearSelection()
        assertEquals(before, vm.state.value.project)
    }

    @Test fun `group and ungroup preserve rendered appearance geometry and stacking and support undo`() {
        val vm = model(listOf(a, b))
        val renderer = CompositionRenderer()
        val before = renderer.render(vm.state.value.project!!, 0, emptyMap(), 240)
        vm.beginMultiSelection(a.id); vm.toggleElementInSelection(b.id); vm.groupSelection()
        val grouped = vm.elements()
        assertEquals(1, grouped.map { it.groupId }.distinct().size)
        assertNotNull(grouped.first().groupId)
        assertEquals(listOf(a, b), grouped.map { it.withGroupId(null) })
        assertArrayEquals(before, renderer.render(vm.state.value.project!!, 0, emptyMap(), 240))
        vm.ungroupSelected()
        assertEquals(listOf(a, b), vm.elements())
        vm.undo(); assertEquals(grouped, vm.elements())
        vm.undo(); assertEquals(listOf(a, b), vm.elements())
        vm.redo(); assertEquals(grouped, vm.elements())
    }

    @Test fun `existing groups are selected atomically and only combined after explicit confirmation`() {
        val original = listOf(a.copy(groupId = "first"), b.copy(groupId = "first"), c.copy(groupId = "second"), d.copy(groupId = "second"))
        val vm = model(original)
        vm.selectElement(a.id)
        assertEquals(setOf(a.id, b.id), vm.state.value.selectedElementIds)
        vm.toggleElementInSelection(c.id)
        assertEquals(4, vm.state.value.selectedElementIds.size)
        vm.groupSelection()
        assertNotNull(vm.state.value.groupMergeRequest)
        assertEquals(original, vm.elements())
        assertFalse(vm.state.value.canUndo)
        vm.cancelGroupMerge(); assertEquals(original, vm.elements())
        vm.groupSelection(); vm.confirmGroupMerge()
        val combined = vm.elements()
        assertEquals(1, combined.map { it.groupId }.distinct().size)
        assertTrue(combined.none { it.groupId in setOf("first", "second") })
        assertEquals(original.map { it.withGroupId(null) }, combined.map { it.withGroupId(null) })
        vm.undo(); assertEquals(original, vm.elements())
        vm.redo(); assertEquals(combined, vm.elements())
    }

    @Test fun `a changed selection or newly locked member invalidates a pending group combination`() {
        val original = listOf(a.copy(groupId = "first"), b.copy(groupId = "first"), c)
        val vm = model(original)
        vm.selectElement(a.id); vm.toggleElementInSelection(c.id); vm.groupSelection()
        vm.toggleElementLocked(b.id, wholeGroup = false)
        val locked = vm.elements()
        vm.confirmGroupMerge()
        assertEquals(locked, vm.elements())
        assertEquals("first", vm.elements()[0].groupId)
        assertNull(vm.elements()[2].groupId)
    }

    @Test fun `editing one member changes only that member while the group stays intact`() {
        val original = listOf(a.copy(groupId = "g"), b.copy(groupId = "g"))
        val vm = model(original)
        vm.selectGroup("g"); vm.editGroup("g")
        assertEquals("g", vm.state.value.editingGroupId)
        assertEquals(setOf(a.id), vm.state.value.selectedElementIds)
        vm.moveSelected(.12f, -.1f); vm.setShapeFill(0xFFAACCEE); vm.setOutlineWidth(50f)
        assertEquals(original[1], vm.elements()[1])
        assertEquals("g", vm.elements()[0].groupId)
        val before = vm.elements()
        vm.finishEditingGroup(); vm.moveSelected(.1f, .05f)
        vm.elements().zip(before).forEach { (after, previous) ->
            assertEquals(previous.transform.bounds.x + .1f, after.transform.bounds.x, .00001f)
            assertEquals(previous.transform.bounds.y + .05f, after.transform.bounds.y, .00001f)
        }
    }

    @Test fun `locked members block collective edits while an explicitly chosen unlocked member remains editable`() {
        val original = listOf(a.copy(groupId = "g"), b.copy(groupId = "g", locked = true))
        val vm = model(original)
        vm.selectGroup("g"); vm.moveSelected(.2f, .2f); vm.duplicateSelected()
        assertEquals(original, vm.elements())
        vm.focusGroupMember(a.id); vm.moveSelected(.1f, 0f)
        assertEquals(original[1], vm.elements()[1])
        assertEquals(.2f, vm.elements()[0].transform.bounds.x, .00001f)
        val before = vm.elements()
        vm.ungroupSelected(); assertEquals(before, vm.elements())
        vm.finishEditingGroup(); assertNull(vm.state.value.editingGroupId)
        assertNull(vm.state.value.selectedElementId)
    }

    @Test fun `group locking protects every member and unlocking remains undoable`() {
        val vm = model(listOf(a.copy(groupId = "g"), b.copy(groupId = "g")))
        vm.toggleGroupLocked("g")
        val locked = vm.elements()
        assertTrue(locked.all { it.locked })
        vm.editGroup("g"); vm.moveSelected(.1f, .1f); vm.deleteSelected()
        assertEquals(locked, vm.elements())
        vm.toggleGroupLocked("g"); assertTrue(vm.elements().none { it.locked })
        vm.undo(); assertEquals(locked, vm.elements())
    }

    @Test fun `duplicating a temporary selection keeps independent elements independent`() {
        val vm = model(listOf(a, b))
        vm.beginMultiSelection(a.id); vm.toggleElementInSelection(b.id); vm.duplicateSelected()
        assertEquals(4, vm.elements().size)
        assertTrue(vm.elements().all { it.groupId == null })
        vm.undo(); assertEquals(listOf(a, b), vm.elements())
    }

    @Test fun `duplicating several groups keeps each copy separate and a member copy stays in its edited group`() {
        val vm = model(listOf(a.copy(groupId = "first"), b.copy(groupId = "first"), c.copy(groupId = "second"), d.copy(groupId = "second")))
        vm.selectGroup("first"); vm.toggleElementInSelection(c.id); vm.duplicateSelected()
        val copies = vm.elements().filter { it.id !in setOf(a.id, b.id, c.id, d.id) }
        assertEquals(listOf(2, 2), copies.groupBy { it.groupId }.values.map { it.size })
        assertTrue(copies.none { it.groupId in setOf("first", "second") })
        vm.focusGroupMember(a.id); vm.duplicateSelected()
        assertEquals(3, vm.elements().count { it.groupId == "first" })
        assertEquals("first", vm.state.value.editingGroupId)
    }

    @Test fun `alignment and distribution use explicit references and preserve groups and sizes`() {
        val vm = model(listOf(a.copy(groupId = "g"), b.copy(groupId = "g"), c, d))
        vm.selectGroup("g"); vm.toggleElementInSelection(c.id); vm.toggleElementInSelection(d.id)
        val before = vm.elements()
        vm.distributeSelected(true)
        val distributed = vm.elements()
        val boxes = distributed.selectionUnits().map { it.bounds(1f) }.sortedBy { it.x }
        assertEquals(boxes[1].x - boxes[0].right, boxes[2].x - boxes[1].right, .00001f)
        before.zip(distributed).forEach { (x, y) -> assertEquals(x.transform.bounds.width, y.transform.bounds.width, 0f) }
        vm.undo(); assertEquals(before, vm.elements())
        vm.setArrangementReference(CompositionArrangementReference.PAGE); vm.distributeSelected(true)
        val pageBoxes = vm.elements().selectionUnits().map { it.bounds(1f) }.sortedBy { it.x }
        assertEquals(0f, pageBoxes.first().x, .00001f)
        assertEquals(1f, pageBoxes.last().right, .00001f)
        vm.undo(); vm.setArrangementReference(CompositionArrangementReference.SELECTION); vm.alignSelectedHorizontal(-1)
        val aligned = vm.elements().selectionUnits().map { it.bounds(1f) }
        assertTrue(aligned.all { kotlin.math.abs(it.x - aligned.first().x) < .00001f })
    }

    @Test fun `group commands and distribution never merge into an unfinished property gesture`() {
        val vm = model(listOf(a, b, c))
        vm.beginMultiSelection(a.id); vm.toggleElementInSelection(b.id); vm.toggleElementInSelection(c.id)
        vm.beginGesture(stage = false); vm.setSelectedOpacity(.4f)
        val adjusted = vm.elements()
        vm.groupSelection(); vm.undo(); assertEquals(adjusted, vm.elements())
        vm.beginGesture(stage = false); vm.setSelectedOpacity(.6f)
        val next = vm.elements()
        vm.distributeSelected(true); vm.undo(); assertEquals(next, vm.elements())
    }

    @Test fun `member reordering preserves outsiders and refuses to move a locked member`() {
        val original = listOf(a.copy(groupId = "g", zIndex = 2), b.copy(groupId = "g", zIndex = 4), c.copy(zIndex = 10, locked = true))
        val vm = model(original)
        vm.reorderGroupMember("g", 0, 1)
        assertEquals(original[2], vm.elements()[2])
        assertEquals(4, vm.elements()[0].zIndex)
        vm.undo(); assertEquals(original, vm.elements())
        vm.toggleElementLocked(b.id, wholeGroup = false)
        val locked = vm.elements()
        vm.reorderGroupMember("g", 0, 1); assertEquals(locked, vm.elements())
        vm.reorderLayer(1, 0); assertEquals(locked, vm.elements())
    }

    @Test fun `groups and member locks survive saving reopening and templates`() {
        var template: CompositionTemplate? = null
        val templates = object : CompositionTemplateRepository by FakeComposerRepos.templates {
            override suspend fun save(value: CompositionTemplate) { template = Json.decodeFromString(Json.encodeToString(CompositionTemplate.serializer(), value)) }
        }
        val original = listOf(a.copy(groupId = "g"), b.copy(groupId = "g", locked = true), c)
        val vm = model(original, templates)
        vm.saveNow(); vm.saveAsTemplate("Grouped", false); vm.open("groups")
        assertEquals(original, vm.elements())
        assertEquals(original, template!!.project.elements)
        assertNull(vm.state.value.editingGroupId)
    }

    @Test fun `page arrangement never silently moves a video to a different associated page`() {
        val video = CompositionElement.Video("video", ElementTransform(NormalizedRect(1.3f, .2f, .4f, .3f)), 2, 1,
            MediaReference("asset", "checksum", "clip.mp4"), VideoTiming(0, 3000))
        val vm = model(listOf(a, video), pages = 2)
        vm.selectElement(a.id); vm.toggleElementInSelection(video.id)
        vm.setArrangementReference(CompositionArrangementReference.PAGE)
        val before = vm.elements()
        vm.alignSelectedHorizontal(-1)
        assertEquals(before, vm.elements())
        assertNotNull(vm.state.value.notice)
        assertFalse(vm.state.value.canUndo)
        vm.selectPage(1); vm.alignSelectedHorizontal(-1)
        val after = vm.elements().filterIsInstance<CompositionElement.Video>().single()
        assertEquals(1, after.pageIndex)
        assertEquals(video.timing, after.timing)
        assertEquals(1f, after.transform.bounds.x, .00001f)
        vm.undo(); assertEquals(before, vm.elements())
    }

    @Test fun `continuous group rotation keeps a stable pivot and relative member angles`() {
        val original = listOf(a.copy(groupId = "g", transform = a.transform.copy(rotationDegrees = 20f)),
            b.copy(groupId = "g", transform = b.transform.copy(rotationDegrees = 345f)))
        val vm = model(original)
        vm.selectGroup("g"); vm.beginGesture(stage = false)
        vm.setSelectedRotation(45f); vm.setSelectedRotation(70f); vm.endGesture(stage = false)
        val after = vm.elements()
        assertEquals(70f, after[0].transform.rotationDegrees, .0001f)
        assertEquals(35f, after[1].transform.rotationDegrees, .0001f)
        val once = model(original)
        once.selectGroup("g"); once.rotateSelectedBy(50f)
        after.zip(once.elements()).forEach { (x, y) ->
            assertEquals(x.transform.bounds.x, y.transform.bounds.x, .00001f)
            assertEquals(x.transform.bounds.y, y.transform.bounds.y, .00001f)
        }
        vm.undo(); assertEquals(original, vm.elements())
    }

    @Test fun `reordering an unlocked group does not renumber an unrelated locked layer`() {
        val original = listOf(a.copy(groupId = "g", zIndex = 2), b.copy(groupId = "g", zIndex = 4),
            c.copy(zIndex = 6), d.copy(zIndex = 20, locked = true))
        val vm = model(original)
        vm.reorderLayer(2, 1)
        assertEquals(original[3], vm.elements()[3])
        assertEquals("g", vm.elements()[0].groupId)
        assertTrue(vm.elements()[0].zIndex < vm.elements()[1].zIndex)
        val reordered = vm.elements()
        vm.reorderLayer(1, 0); assertEquals(reordered, vm.elements())
        vm.undo(); assertEquals(original, vm.elements())
    }

    @Test fun `targeted member and group deletion never acts on the previous selection`() {
        val original = listOf(a.copy(groupId = "g"), b.copy(groupId = "g", locked = true), c)
        val vm = model(original)
        vm.selectElement(c.id)
        vm.deleteGroup("g"); vm.deleteGroupMember(b.id)
        assertEquals(original, vm.elements())
        vm.deleteGroupMember(a.id)
        assertEquals(listOf(original[1], c), vm.elements())
        assertEquals(c.id, vm.state.value.selectedElementId)
        vm.undo(); assertEquals(original, vm.elements())
    }

    @Test fun `ungrouping keeps unrelated selected elements in the selection`() {
        val vm = model(listOf(a.copy(groupId = "g"), b.copy(groupId = "g"), c))
        vm.selectGroup("g"); vm.toggleElementInSelection(c.id); vm.ungroupSelected()
        assertEquals(setOf(a.id, b.id, c.id), vm.state.value.selectedElementIds)
        assertTrue(vm.elements().all { it.groupId == null })
    }
}
