package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerGridTest {
    private val elements = List(4) { index -> CompositionElement.MediaPlaceholder("slot-$index",
        ElementTransform(NormalizedRect(.1f + index * .15f, .2f, .15f, .2f)), index) }
    private fun source(members: List<CompositionElement> = elements, pages: Int = 1) = CompositionProject(id = "grid-test", name = "Grid",
        format = PageFormatPreset.SQUARE_1_1.format, pages = List(pages) { CompositionPage("page-$it", it) },
        elements = members, createdAt = "", updatedAt = "")
    private class Projects(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll() = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored
        override suspend fun save(project: CompositionProject) { stored = Json.decodeFromString(Json.encodeToString(project)) }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
    }
    private fun model(project: CompositionProject = source(), templates: CompositionTemplateRepository = FakeComposerRepos.templates,
        projects: Projects = Projects(project)): ComposerEditorViewModel {
        val f = FakeComposerRepos
        return ComposerEditorViewModel(projects, templates, f.media, f.recipes, f.exports, f.brandKits).also { vm ->
            vm.open(project.id); vm.beginMultiSelection(project.elements.first().id)
            project.elements.drop(1).forEach { vm.toggleElementInSelection(it.id) }
        }
    }
    private fun ComposerEditorViewModel.project() = state.value.project!!
    private fun ComposerEditorViewModel.create() { setArrangementReference(CompositionArrangementReference.PAGE); beginGrid(); confirmGrid() }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `preview and cancellation leave content and undo history untouched`() {
        val vm = model(); val original = vm.project()
        vm.beginGrid(); vm.setGridKind(CompositionLayoutKind.FEATURE_LEFT)
        vm.updateGridLayout(vm.state.value.gridEditor!!.layout.copy(margin = .1f, gap = .05f))
        val preview = vm.state.value.gridEditor!!.preview()
        assertEquals(4, preview.elements.size); assertEquals(1, preview.layouts.size)
        assertEquals(original, vm.project()); assertFalse(vm.state.value.canUndo)
        vm.cancelGrid(); assertNull(vm.state.value.gridEditor); assertEquals(original, vm.project())
    }

    @Test fun `confirming creates one persistent group and a single undo restores all previous placements`() {
        val vm = model(); val original = vm.project()
        vm.beginGrid(); vm.setGridKind(CompositionLayoutKind.COLUMN); vm.setGridKind(CompositionLayoutKind.ROW)
        vm.setGridReference(CompositionArrangementReference.PAGE); vm.confirmGrid()
        assertEquals(1, vm.project().layouts.size)
        assertEquals(1, vm.project().elements.map { it.groupId }.distinct().size)
        val grouped = vm.project(); vm.undo()
        assertEquals(original.elements, vm.project().elements); assertTrue(vm.project().layouts.isEmpty())
        assertFalse(vm.state.value.canUndo)
        vm.redo(); assertEquals(grouped.layouts, vm.project().layouts); assertEquals(grouped.elements, vm.project().elements)
    }

    @Test fun `combining existing groups is disclosed before application and cancel retains membership`() {
        val original = elements.mapIndexed { i, e -> e.copy(groupId = if (i < 2) "first" else "second") }
        val vm = model(source(original))
        vm.selectGroup("first"); vm.toggleElementInSelection(original.last().id)
        vm.beginGrid(); assertTrue(vm.state.value.gridEditor!!.combinesGroups)
        vm.cancelGrid(); assertEquals(original, vm.project().elements)
        vm.beginGrid(); vm.confirmGrid()
        assertTrue(vm.project().elements.none { it.groupId == "first" || it.groupId == "second" })
    }

    @Test fun `locked members prevent creating and resizing grid cells`() {
        val vm = model(); vm.create()
        val layout = vm.project().layouts.single()
        vm.toggleElementLocked(elements.last().id, wholeGroup = false)
        val locked = vm.project()
        vm.adjustGridDivider(layout.groupId, "rows", 0, .1f)
        assertEquals(locked, vm.project())
        vm.selectGroup(layout.groupId); vm.beginGrid(); assertNull(vm.state.value.gridEditor)
    }

    @Test fun `repeated members and videos from another page are refused without any changes`() {
        val repeated = model(source(elements.map { it.copy(repeatOnPages = setOf(0)) }))
        val before = repeated.project(); repeated.beginGrid()
        assertNull(repeated.state.value.gridEditor); assertEquals(before, repeated.project()); assertNotNull(repeated.state.value.notice)
        val video = CompositionElement.Video("video", ElementTransform(NormalizedRect(1.1f, .2f, .4f, .3f)), 8, 1,
            MediaReference("clip", "hash", "clip.mp4"), VideoTiming(trimEndMs = 4000))
        val vm = model(source(elements + video, pages = 2))
        val original = vm.project(); vm.beginGrid()
        assertNull(vm.state.value.gridEditor); assertEquals(original, vm.project())
        vm.selectPage(1); vm.beginGrid(); vm.confirmGrid()
        val result = vm.project().elements.filterIsInstance<CompositionElement.Video>().single()
        assertEquals(video.pageIndex, result.pageIndex); assertEquals(video.timing, result.timing)
    }

    @Test fun `divider dragging is one undo operation and editing styles preserves the current crops`() {
        val vm = model(); vm.create(); val before = vm.project()
        val layout = before.layouts.single()
        vm.beginGesture(); repeat(4) { vm.adjustGridDivider(layout.groupId, "rows", 0, .02f) }; vm.endGesture()
        assertNotEquals(before.elements, vm.project().elements)
        vm.undo(); assertEquals(before.layouts, vm.project().layouts); assertEquals(before.elements, vm.project().elements)
        vm.openGrid(layout.groupId); vm.updateGridLayout(vm.project().layouts.single().copy(outline = LayoutStroke(0xFF77AA99, .4f, .02f)))
        assertEquals(.4f, vm.project().layouts.single().outline.opacity, .00001f)
        vm.undo(); assertEquals(before.layouts, vm.project().layouts)
    }

    @Test fun `group transforms and duplication retain independent layouts and ungroup can be undone`() {
        val vm = model(); vm.create(); val initial = vm.project().layouts.single()
        vm.moveSelected(.15f, -.1f); vm.rotateSelectedBy(30f); vm.scaleSelected(.8f)
        val moved = vm.project().layouts.single()
        assertNotEquals(initial.frame.bounds, moved.frame.bounds); assertEquals(30f, moved.frame.rotationDegrees, .001f)
        vm.duplicateSelected()
        assertEquals(2, vm.project().layouts.size)
        assertEquals(8, vm.project().elements.map { it.id }.distinct().size)
        val copy = vm.state.value.selectedLayout()!!
        assertNotEquals(moved.groupId, copy.groupId)
        assertTrue(copy.root.memberIds().none { it in moved.root.memberIds() })
        vm.ungroupSelected(); assertEquals(1, vm.project().layouts.size)
        vm.undo(); assertEquals(2, vm.project().layouts.size)
    }

    @Test fun `layouts survive saving reopening and templates without media`() {
        var template: CompositionTemplate? = null
        val templates = object : CompositionTemplateRepository {
            override fun observeAll() = flowOf(emptyList<CompositionTemplate>())
            override suspend fun get(id: String) = template
            override suspend fun save(value: CompositionTemplate) { template = Json.decodeFromString(Json.encodeToString(value)) }
            override suspend fun delete(id: String) = Unit
        }
        val projects = Projects(source()); val vm = model(templates = templates, projects = projects)
        vm.create(); val created = vm.project()
        vm.saveAsTemplate("Grid", false); assertEquals(created.layouts, template!!.project.layouts)
        vm.saveDraft(); vm.open(created.id)
        assertEquals(created.layouts, vm.project().layouts); assertEquals(created.elements, vm.project().elements)
    }

    @Test fun `video plans keep separators in a still overlay above the clips`() {
        val video = CompositionElement.Video("video", ElementTransform(NormalizedRect(.1f, .2f, .4f, .3f)), 8, 0,
            MediaReference("clip", "hash", "clip.mp4"), VideoTiming(trimEndMs = 4000))
        val vm = model(source(elements + video)); vm.create()
        val plan = PageVideoPlan.of(vm.project(), 0)
        assertTrue(plan.layers.last() is PageVideoPlan.Layer.Still)
        val decoration = (plan.layers.last() as PageVideoPlan.Layer.Still).elementIds
        assertTrue(decoration.any { it.startsWith("layout-decoration:") })
        assertEquals(video.timing, plan.clips.single().video.timing)
    }

    @Test fun `semi transparent separators do not double their opacity at a shared junction`() {
        val slots = elements.map { it.copy(visible = false) }.toMutableList().also { it[0] = it[0].copy(visible = true) }
        val vm = model(source(slots)); vm.create(); vm.openGrid(vm.project().layouts.single().groupId)
        val draft = vm.project().layouts.single().copy(margin = 0f, gap = 0f, separators = LayoutStroke(0xFFFFFFFF, .5f, .04f))
        vm.updateGridLayout(draft)
        val project = vm.project().copy(background = CompositionBackground.Solid(0xFF000000))
        val image = ImageIO.read(ByteArrayInputStream(CompositionRenderer().render(project, 0, emptyMap(), 240)))
        val crossing = image.getRGB(120, 120) and 255
        val line = image.getRGB(120, 180) and 255
        assertEquals(line.toDouble(), crossing.toDouble(), 3.0)
        assertTrue(crossing in 120..135)
    }

    @Test fun `rotated grid resize uses the layout frame and keeps fitted artwork proportional`() {
        val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.1f, .1f, .1f, .2f)), 0,
            ShapeKind.ELLIPSE, strokeWidth = 0f)
        val vm = model(source(listOf(shape) + elements.drop(1))); vm.create(); vm.rotateSelectedBy(25f)
        val before = vm.project().layouts.single()
        val expected = listOf(before.proxy()).resizedSelectionFromCorner(ResizeCorner.BOTTOM_RIGHT, -.2f, -.1f, 1, 1f).single().transform
        vm.beginResizeGesture(ResizeCorner.BOTTOM_RIGHT)
        vm.resizeSelectedWithGuides(-.2f, -.1f, .00001f, .00001f); vm.endGesture()
        val after = vm.project().layouts.single()
        assertEquals(expected.bounds.x, after.frame.bounds.x, .001f); assertEquals(expected.bounds.y, after.frame.bounds.y, .001f)
        assertEquals(expected.bounds.width, after.frame.bounds.width, .001f); assertEquals(expected.bounds.height, after.frame.bounds.height, .001f)
        val fitted = vm.project().elements.first { it.id == shape.id }.transform.bounds
        assertEquals(.5f, fitted.width / fitted.height, .001f)
        assertEquals(25f, vm.project().elements.first().transform.rotationDegrees, .001f)
    }

    @Test fun `aligning a grid uses the complete frame including margins instead of the fitted shapes`() {
        val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.1f, .1f, .1f, .2f)), 0, ShapeKind.ELLIPSE)
        val vm = model(source(listOf(shape) + elements.drop(1))); vm.create()
        vm.moveSelected(.2f, .1f); vm.alignSelectedHorizontal(-1); vm.alignSelectedVertical(-1)
        val frame = vm.project().layouts.single().frame.bounds
        assertEquals(0f, frame.x, .0001f); assertEquals(0f, frame.y, .0001f)
        assertEquals(1f, frame.width, .0001f); assertEquals(1f, frame.height, .0001f)
    }

    @Test fun `same page still grids repeat coherently while multi page grids refuse partial repetition`() {
        val vm = model(source(pages = 2)); vm.create(); vm.repeatOnAllPages()
        assertTrue(vm.project().renderElements().all { it.repeatOnPages == setOf(0, 1) })
        vm.undo(); assertTrue(vm.project().elements.all { it.repeatOnPages.isEmpty() })
        val across = model(source(listOf(elements[0].copy(transform = ElementTransform(NormalizedRect(.7f, .2f, .15f, .2f))),
            elements[1].copy(transform = ElementTransform(NormalizedRect(1.2f, .2f, .15f, .2f)))), pages = 2))
        across.beginGrid(); across.confirmGrid(); val before = across.project()
        across.repeatOnAllPages(); assertEquals(before, across.project()); assertNotNull(across.state.value.notice)
    }

    @Test fun `oversized video cells are refused before applying and page reference resolves the selection`() {
        val video = CompositionElement.Video("video", ElementTransform(NormalizedRect(.1f, .2f, .4f, .3f)), 0, 0,
            MediaReference("clip", "hash", "clip.mp4"), VideoTiming(trimEndMs = 4000))
        val far = elements[0].copy(transform = ElementTransform(NormalizedRect(4.2f, .2f, .4f, .3f)))
        val vm = model(source(listOf(video, far), pages = 5))
        val original = vm.project(); vm.beginGrid(); vm.setGridKind(CompositionLayoutKind.ROW); vm.confirmGrid()
        assertEquals(original, vm.project()); assertNotNull(vm.state.value.gridEditor); assertNotNull(vm.state.value.notice)
        vm.setGridReference(CompositionArrangementReference.PAGE); vm.confirmGrid()
        assertEquals(1, vm.project().layouts.size)
        assertEquals(video.pageIndex, vm.project().elements.filterIsInstance<CompositionElement.Video>().single().pageIndex)
    }

    @Test fun `collective opacity includes grid decoration even when the original members have different alpha`() {
        val vm = model(source(elements.mapIndexed { index, element -> element.copy(transform = element.transform.copy(opacity = .2f * (index + 1))) }))
        vm.create(); val before = vm.project()
        vm.setSelectedOpacity(.5f)
        assertEquals(.5f, vm.project().layouts.single().frame.opacity, .0001f)
        assertTrue(vm.project().elements.all { it.transform.opacity == .5f })
        vm.undo(); assertEquals(before.elements, vm.project().elements); assertEquals(before.layouts, vm.project().layouts)
    }
}
