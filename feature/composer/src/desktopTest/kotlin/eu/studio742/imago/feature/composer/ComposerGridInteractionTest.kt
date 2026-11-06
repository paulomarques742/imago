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

    @Test fun `swapping draft cells keeps split weights recipes and crops and cancel restores everything`() {
        val vm = model(); val group = vm.grid()
        vm.adjustGridDivider(group, "row-0", 0, .1f)
        val before = vm.project(); vm.openGrid(group)
        val root = vm.state.value.gridEditor!!.layout.root
        vm.swapGridDraftMembers("a", "b")
        val editor = vm.state.value.gridEditor!!; val preview = editor.preview()
        assertEquals(root.swapMembers("a", "b"), editor.layout.root)
        assertEquals(before.layouts.single().geometry(1f).cells["a"], editor.layout.geometry(1f).cells["b"])
        before.elements.filterIsInstance<CompositionElement.Photo>().forEach { photo ->
            val after = preview.elements.first { it.id == photo.id } as CompositionElement.Photo
            assertEquals(photo.media, after.media); assertEquals(photo.recipe, after.recipe); assertEquals(photo.crop, after.crop)
        }
        assertEquals(before, vm.project()); vm.cancelGrid(); assertEquals(before, vm.project())
    }

    @Test fun `confirming a swap is one undo step and invalid drops do not change the draft`() {
        val vm = model(); val group = vm.grid(); val before = vm.project()
        vm.openGrid(group); val original = vm.state.value.gridEditor
        vm.swapGridDraftMembers("a", "missing"); assertEquals(original, vm.state.value.gridEditor)
        vm.swapGridDraftMembers("a", "a"); assertEquals(original, vm.state.value.gridEditor)
        vm.swapGridDraftMembers("a", "b"); vm.confirmGrid(); val after = vm.project()
        assertNotEquals(before.layouts, after.layouts)
        vm.undo(); assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
        vm.redo(); assertEquals(after.elements, vm.project().elements); assertEquals(after.layouts, vm.project().layouts)
    }

    @Test fun `adding media to a draft stays provisional and never fills unrelated placeholders`() {
        val vm = model(); val group = vm.grid(); val before = vm.project(); vm.openGrid(group)
        vm.addMediaToGrid(listOf(incoming("new")), group, draft = true)
        assertEquals(before, vm.project())
        val preview = vm.state.value.gridEditor!!.preview()
        assertEquals(3, preview.layouts.single().root.memberIds().size)
        assertEquals(before.elements.last(), preview.elements.first { it.id == "unrelated" })
        vm.cancelGrid(); assertEquals(before, vm.project())
        vm.openGrid(group); vm.addMediaToGrid(listOf(incoming("new")), group, draft = true); vm.confirmGrid()
        assertEquals(4, vm.project().elements.size); assertEquals(3, vm.project().layouts.single().root.memberIds().size)
        vm.undo(); assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
    }

    @Test fun `adding from the selected grid preserves its frame and styles as one undo step`() {
        val vm = model(); val group = vm.grid(); vm.openGrid(group)
        vm.updateGridDraft(vm.state.value.gridEditor!!.layout.copy(margin = .1f, gap = .05f, outline = LayoutStroke(width = .02f)))
        vm.confirmGrid(); vm.setSelectedOpacity(.4f); val before = vm.project()
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
        assertEquals(before, vm.project()); assertNull(vm.state.value.gridEditor)
    }

    @Test fun `opening an existing grid leaves multi selection and member editing before a provisional swap`() {
        val vm = model(); val group = vm.grid(); val before = vm.project()
        vm.editGroup(group); vm.beginMultiSelection("a")
        assertTrue(vm.state.value.multiSelect); assertEquals(group, vm.state.value.editingGroupId)
        vm.openGrid(group)
        assertNotNull(vm.state.value.gridEditor)
        assertFalse(vm.state.value.multiSelect); assertNull(vm.state.value.editingGroupId)
        vm.swapGridDraftMembers("a", "b")
        assertFalse(vm.state.value.multiSelect); assertNull(vm.state.value.editingGroupId)
        assertEquals(before, vm.project())
        vm.cancelGrid(); assertEquals(before, vm.project())
    }
}
