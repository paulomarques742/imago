package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.model.EditRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerGridInteractionTest {
    private fun photo(id: String, z: Int) = CompositionElement.Photo(id,
        ElementTransform(NormalizedRect(.1f + z * .2f, .2f, .2f, .3f)), z,
        MediaReference(id, "hash-$id", "$id.jpg", width = 600, height = 400),
        PhotoRecipeSnapshot(EditRecipe(assetId = id, originalChecksum = "hash-$id", createdAt = "", updatedAt = "")),
        crop = PlacementCrop(scale = 1.8f, offsetX = .3f, offsetY = -.2f))
    private fun model(pages: Int = 1): ComposerEditorViewModel {
        val project = CompositionProject(id = "interaction", name = "Grid", format = PageFormatPreset.SQUARE_1_1.format,
            pages = List(pages) { CompositionPage("page-$it", it) },
            elements = listOf(photo("a", 0), photo("b", 1), CompositionElement.MediaPlaceholder("unrelated", ElementTransform(NormalizedRect(.1f, .7f, .2f, .2f)), 2)),
            createdAt = "", updatedAt = "")
        val projects = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(project))
            override suspend fun get(id: String) = project
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val f = FakeComposerRepos
        return ComposerEditorViewModel(projects, f.templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open(project.id) }
    }
    private fun ComposerEditorViewModel.project() = state.value.project!!
    private fun ComposerEditorViewModel.grid(): String {
        beginMultiSelection("a"); toggleElementInSelection("b")
        setArrangementReference(CompositionArrangementReference.PAGE); beginGrid(); confirmGrid()
        return project().layouts.single().groupId
    }
    private fun incoming(id: String, video: Boolean = false) = ComposerMedia(id, "hash-$id", "$id.jpg", width = 600, height = 400,
        isVideo = video, durationMs = if (video) 8_000 else null)
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `swapping cells on the stage keeps split weights recipes and crops and undo restores everything`() {
        val vm = model(); val group = vm.grid()
        vm.adjustGridDivider(group, "row-0", 0, .1f)
        val before = vm.project(); vm.openGrid(group)
        vm.swapGridMembers("a", "b")
        val after = vm.project()
        assertEquals(before.layouts.single().root.swapMembers("a", "b"), after.layouts.single().root)
        assertEquals(before.layouts.single().geometry(1f).cells["a"], after.layouts.single().geometry(1f).cells["b"])
        before.elements.filterIsInstance<CompositionElement.Photo>().forEach { photo ->
            val moved = after.elements.first { it.id == photo.id } as CompositionElement.Photo
            assertEquals(photo.media, moved.media); assertEquals(photo.recipe, moved.recipe); assertEquals(photo.crop, moved.crop)
        }
        vm.undo(); assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
        vm.redo(); assertEquals(after.elements, vm.project().elements); assertEquals(after.layouts, vm.project().layouts)
    }

    @Test fun `invalid swaps change nothing`() {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); val before = vm.project()
        vm.swapGridMembers("a", "missing"); assertEquals(before, vm.project())
        vm.swapGridMembers("a", "a"); assertEquals(before, vm.project())
    }

    @Test fun `adding media while creating a grid stays provisional`() {
        val vm = model(); vm.beginMultiSelection("a"); vm.toggleElementInSelection("b")
        vm.setArrangementReference(CompositionArrangementReference.PAGE); vm.beginGrid()
        val before = vm.project(); val group = vm.state.value.gridEditor!!.layout.groupId
        vm.addMediaToGrid(listOf(incoming("new")), group, draft = true)
        assertEquals(before, vm.project())
        assertEquals(3, vm.state.value.gridEditor!!.preview().layouts.single().root.memberIds().size)
        vm.confirmGrid()
        assertEquals(4, vm.project().elements.size); assertEquals(3, vm.project().layouts.single().root.memberIds().size)
    }

    @Test fun `adding media on the stage stays in the mode and never fills unrelated placeholders`() {
        val vm = model(); val group = vm.grid(); val before = vm.project(); vm.openGrid(group)
        vm.addMediaToGrid(listOf(incoming("new")), group)
        assertEquals(group, vm.state.value.editingGroupId)
        assertEquals(3, vm.project().layouts.single().root.memberIds().size)
        assertEquals(before.elements.last(), vm.project().elements.first { it.id == "unrelated" })
        vm.undo(); assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
    }

    @Test fun `adding from the selected grid preserves its frame and styles as one undo step`() {
        val vm = model(); val group = vm.grid(); vm.openGrid(group)
        vm.updateGridLayout(vm.project().layouts.single().copy(margin = .1f, gap = .05f, outline = LayoutStroke(width = .02f)))
        vm.finishEditingGroup(); vm.setSelectedOpacity(.4f); val before = vm.project()
        vm.addMediaToGrid(listOf(incoming("new"), incoming("next")), group)
        val layout = vm.project().layouts.single()
        assertEquals(before.layouts.single().frame, layout.frame); assertEquals(before.layouts.single().outline, layout.outline)
        assertEquals(.1f, layout.margin, .001f); assertEquals(.05f, layout.gap, .001f)
        assertEquals(4, layout.root.memberIds().size)
        assertTrue(vm.project().elements.filter { it.groupId == group }.all { it.transform.opacity == .4f })
        assertEquals(before.elements.last(), vm.project().elements.first { it.id == "unrelated" })
        vm.undo(); assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
    }

    @Test fun `central creation uses the chosen page without changing existing content and undo removes the whole new grid`() {
        val vm = model(2); val before = vm.project()
        vm.createGridFromMedia(listOf(incoming("new"), incoming("clip", video = true)), pageIndex = 1)
        assertEquals(before.elements, vm.project().elements.take(before.elements.size))
        val layout = vm.project().layouts.single(); assertEquals(1f, layout.frame.bounds.x, .001f)
        assertEquals(1, vm.project().elements.filterIsInstance<CompositionElement.Video>().single().pageIndex)
        assertEquals(8_000L, vm.project().pages[1].durationMs)
        assertEquals(layout.root.memberIds().toSet(), vm.state.value.selectedElementIds)
        vm.undo(); assertEquals(before.elements, vm.project().elements); assertTrue(vm.project().layouts.isEmpty())
        assertEquals(before.pages, vm.project().pages)
    }

    @Test fun `single item creation and video overflow reject the batch without partial changes`() {
        val vm = model(); val before = vm.project()
        vm.createGridFromMedia(listOf(incoming("only"))); assertEquals(before, vm.project()); assertNotNull(vm.state.value.notice)
        vm.createGridFromMedia(List(MAX_VIDEOS_PER_PAGE + 1) { incoming("clip-$it", video = true) })
        assertEquals(before, vm.project()); assertFalse(vm.state.value.canUndo)
    }

    @Test fun `grid video additions keep the existing owner page even after navigating elsewhere`() {
        val vm = model(2)
        vm.createGridFromMedia(listOf(incoming("clip", video = true), incoming("new")), pageIndex = 1)
        val group = vm.project().layouts.single().groupId
        vm.selectPage(0); vm.addMediaToGrid(listOf(incoming("next-clip", video = true)), group)
        assertTrue(vm.project().elements.filterIsInstance<CompositionElement.Video>().all { it.pageIndex == 1 })
        assertEquals(2, vm.project().elements.filterIsInstance<CompositionElement.Video>().size)
        assertEquals(2, vm.project().pages.size)
    }

    @Test fun `locked grid members prevent picker insertion and grid editing`() {
        val vm = model(); val group = vm.grid(); vm.toggleElementLocked("b", wholeGroup = false)
        val before = vm.project(); vm.addMediaToGrid(listOf(incoming("new")), group); vm.openGrid(group)
        assertEquals(before, vm.project()); assertNull(vm.state.value.editingGroupId); assertNotNull(vm.state.value.notice)
    }

    private fun ComposerEditorViewModel.gridOfThree(): String = grid().also { addMediaToGrid(listOf(incoming("c")), it) }
    private fun ComposerEditorViewModel.members() = project().layouts.single().root.memberIds()

    @Test fun `opening a grid leaves any selection and its long press never starts multi selection`() {
        val vm = model(); val group = vm.grid(); vm.beginMultiSelection("a")
        vm.openGrid(group)
        assertEquals(group, vm.state.value.editingGroupId)
        assertNull(vm.state.value.selectedElementId); assertFalse(vm.state.value.multiSelect)
        assertNull(vm.state.value.gridEditor)
        vm.beginGroupMemberMultiSelection("a")
        assertFalse(vm.state.value.multiSelect); assertEquals("a", vm.state.value.selectedElementId)
    }

    @Test fun `editing a grid and editing a plain group take their own paths`() {
        val vm = model(); val group = vm.grid()
        vm.editGroup(group)
        assertEquals(group, vm.state.value.editingGroupId); assertNull(vm.state.value.selectedElementId)
        vm.finishEditingGroup()
        assertNull(vm.state.value.editingGroupId); assertEquals(setOf("a", "b"), vm.state.value.selectedElementIds)
    }

    @Test fun `removing a cell closes the gap at once as one undo step and keeps the mode open`() {
        val vm = model(); val group = vm.gridOfThree(); val before = vm.project()
        val survivors = vm.members() - "a"
        vm.openGrid(group); vm.removeGridMember("a")
        assertEquals(survivors, vm.members())
        assertTrue(vm.project().elements.none { it.id == "a" })
        assertEquals(group, vm.state.value.editingGroupId)
        vm.undo()
        assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
    }

    @Test fun `a grid left with one element releases it in hand and undo brings the grid back`() {
        val vm = model(); val group = vm.grid(); val before = vm.project()
        vm.openGrid(group); vm.removeGridMember("a")
        assertTrue(vm.project().layouts.isEmpty())
        assertNull(vm.project().elements.first { it.id == "b" }.groupId)
        assertNull(vm.state.value.editingGroupId)
        assertEquals("b", vm.state.value.selectedElementId)
        vm.undo()
        assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
    }

    @Test fun `deleting the selected cell stays in the mode and the layers path reflows the same way`() {
        val vm = model(); val group = vm.gridOfThree(); val before = vm.project()
        val members = vm.members(); val added = (members - setOf("a", "b")).single()
        vm.openGrid(group); vm.focusGroupMember("a"); vm.deleteSelected()
        assertEquals(members - "a", vm.members())
        assertEquals(group, vm.state.value.editingGroupId); assertNull(vm.state.value.selectedElementId)
        vm.undo()
        assertEquals(before.layouts, vm.project().layouts)

        vm.deleteGroupMember(added)
        assertEquals(listOf("a", "b"), vm.members())
        vm.deleteGroupMember("b")
        assertTrue(vm.project().layouts.isEmpty())
        assertNull(vm.project().elements.first { it.id == "a" }.groupId)
    }

    @Test fun `a locked member stops a deletion that would move it`() {
        val vm = model(); vm.gridOfThree()
        vm.toggleElementLocked("b", wholeGroup = false); val before = vm.project()
        vm.deleteGroupMember("a")
        assertEquals(before, vm.project())
        assertNotNull(vm.state.value.notice)
    }

    @Test fun `settings on the stage apply at once and a whole slider drag is one undo step`() {
        val vm = model(); val group = vm.grid(); val before = vm.project(); vm.openGrid(group)
        vm.beginGesture(stage = false)
        vm.updateGridLayout(vm.project().layouts.single().copy(margin = .05f))
        vm.updateGridLayout(vm.project().layouts.single().copy(margin = .1f))
        vm.endGesture(stage = false)
        assertEquals(.1f, vm.project().layouts.single().margin, .0001f)
        assertNotEquals(before.elements, vm.project().elements)
        vm.undo()
        assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)

        vm.setGridKind(CompositionLayoutKind.COLUMN)
        assertEquals(CompositionLayoutKind.COLUMN, vm.project().layouts.single().kind)
        vm.setGridReference(CompositionArrangementReference.SELECTION)
        vm.setGridReference(CompositionArrangementReference.PAGE)
        assertEquals(NormalizedRect(0f, 0f, 1f, 1f), vm.project().layouts.single().frame.bounds)
    }

    @Test fun `a member cannot leave its cell while the grid is edited`() {
        val vm = model(); val group = vm.grid(); vm.openGrid(group); vm.focusGroupMember("a"); val before = vm.project()
        vm.moveSelected(.1f, .1f)
        assertEquals(before, vm.project())
    }
}
