package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.BrandKitRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerTextEditingTest {
    private val original = CompositionElement.Text("text", ElementTransform(NormalizedRect(.1f, .2f, .6f, .2f)), 0, "Original")
    private var saved: CompositionProject? = null
    private val kit = MutableStateFlow<BrandKit?>(null)
    private fun model(elements: List<CompositionElement> = listOf(original)): ComposerEditorViewModel {
        val source = CompositionProject(id = "p", name = "Text", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = elements, createdAt = "", updatedAt = "")
        val repos = FakeComposerRepos
        val projects = object : CompositionRepository by repos.projects {
            override suspend fun get(id: String) = source
            override suspend fun save(project: CompositionProject) { saved = project }
        }
        val kits = object : BrandKitRepository {
            override fun observe() = kit
            override suspend fun save(brandKit: BrandKit) { kit.value = brandKit }
        }
        return ComposerEditorViewModel(projects, repos.templates, repos.media, repos.recipes, repos.exports, kits).also { it.open("p"); it.selectElement(original.id) }
    }
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()); runBlocking { ComposerFontFiles.bytes(CompositionFonts.Inter) } }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `draft edits stay out of autosave and cancel restores exact content`() {
        val vm = model()
        vm.beginTextEditing()
        vm.updateTextDraft("Olá 😀\nnew words")
        vm.setTextSize(90f)
        assertEquals(original, vm.state.value.project!!.elements.single())
        assertEquals("Olá 😀\nnew words", vm.state.value.displayProject()!!.elements.filterIsInstance<CompositionElement.Text>().single().text)
        assertFalse(vm.state.value.canUndo)
        assertNull(saved)
        vm.cancelTextEditing()
        assertEquals(original, vm.state.value.project!!.elements.single())
        assertNull(vm.state.value.textSession)
    }

    @Test fun `all typing and formatting accepts as one undo step`() {
        val vm = model()
        vm.beginTextEditing(); vm.updateTextDraft("One"); vm.updateTextDraft("One two"); vm.setTextWeight(700)
        vm.confirmTextEditing()
        val accepted = vm.state.value.project!!.elements.single() as CompositionElement.Text
        assertEquals("One two", accepted.text); assertEquals(700, accepted.fontWeight)
        vm.undo(); assertEquals(original, vm.state.value.project!!.elements.single())
        assertFalse(vm.state.value.canUndo)
        vm.redo(); assertEquals(accepted, vm.state.value.project!!.elements.single())
    }

    @Test fun `new text cancellation leaves no placeholder or history entry`() {
        val vm = model(emptyList())
        vm.addText("Text", edit = true)
        assertTrue(vm.state.value.project!!.elements.isEmpty())
        assertTrue(vm.state.value.textSession!!.draft.autoHeight)
        vm.cancelTextEditing()
        assertTrue(vm.state.value.project!!.elements.isEmpty()); assertFalse(vm.state.value.canUndo)
        vm.addText("Text", edit = true); vm.updateTextDraft("Title"); vm.confirmTextEditing()
        assertEquals(1, vm.state.value.project!!.elements.size)
        vm.undo(); assertTrue(vm.state.value.project!!.elements.isEmpty())
    }

    @Test fun `selection change and explicit save accept pending words`() {
        val vm = model()
        vm.beginTextEditing(); vm.updateTextDraft("Saved"); vm.saveNow()
        assertEquals("Saved", (saved!!.elements.single() as CompositionElement.Text).text)
        vm.beginTextEditing(); vm.updateTextDraft("Selected"); vm.clearSelection()
        assertNull(vm.state.value.textSession)
        assertEquals("Selected", (vm.state.value.project!!.elements.single() as CompositionElement.Text).text)
    }

    @Test fun `new text fits both dimensions and grows and shrinks while typing`() {
        val vm = model(emptyList())
        vm.addText("Hi", edit = true)
        val initial = vm.state.value.textSession!!.draft.transform.bounds
        assertTrue(initial.width < .15f)
        assertTrue(initial.height < .1f)
        vm.updateTextDraft("A longer title")
        val longer = vm.state.value.textSession!!.draft.transform.bounds
        assertTrue(longer.width > initial.width)
        assertEquals(initial.x, longer.x, .0001f)
        assertEquals(initial.y, longer.y, .0001f)
        vm.updateTextDraft("Hi")
        val fitted = vm.state.value.textSession!!.draft.transform.bounds
        assertEquals(initial.x, fitted.x, .0001f)
        assertEquals(initial.y, fitted.y, .0001f)
        assertEquals(initial.width, fitted.width, .0001f)
        assertEquals(initial.height, fitted.height, .0001f)
        vm.confirmTextEditing()
        assertEquals(fitted, vm.state.value.project!!.elements.single().transform.bounds)
        vm.undo()
        assertTrue(vm.state.value.project!!.elements.isEmpty())
    }

    @Test fun `new text wraps at the available width and fits explicit lines and empty input`() {
        val vm = model(emptyList())
        vm.addText("Title", edit = true)
        val singleHeight = vm.state.value.textSession!!.draft.transform.bounds.height
        vm.updateTextDraft("First line\nSecond line")
        val multiline = vm.state.value.textSession!!.draft
        assertTrue(multiline.transform.bounds.width < .5f)
        assertTrue(multiline.transform.bounds.height > singleHeight)
        vm.updateTextDraft("A long heading with many words and accents that needs to wrap. ".repeat(5))
        val wrapped = vm.state.value.textSession!!.draft
        assertTrue(wrapped.transform.bounds.width <= .76f)
        val result = measureCompositionText(wrapped, wrapped.transform.bounds.width * REFERENCE_PAGE_WIDTH,
            wrapped.transform.bounds.height * REFERENCE_PAGE_WIDTH, 1f, ComposerFontFiles.loadedOrNull(CompositionFonts.Inter))
        assertTrue(result.lines.size > 2)
        assertEquals(result.contentHeight / REFERENCE_PAGE_WIDTH, wrapped.transform.bounds.height, .0001f)
        vm.updateTextDraft("")
        val empty = vm.state.value.textSession!!.draft.transform.bounds
        assertEquals(MIN_ELEMENT_SIZE, empty.width, .0001f)
        assertTrue(empty.height.isFinite() && empty.height >= MIN_ELEMENT_SIZE)
    }

    @Test fun `creating without inline editing also fits the box and existing widths remain explicit`() {
        val vm = model(emptyList())
        vm.addText("Hi")
        val created = vm.state.value.project!!.elements.single() as CompositionElement.Text
        assertTrue(created.transform.bounds.width < .15f)
        vm.beginTextEditing(created.id)
        vm.updateTextDraft("A longer title that wraps inside the chosen box")
        assertEquals(created.transform.bounds.width, vm.state.value.textSession!!.draft.transform.bounds.width, 0f)
        vm.cancelTextEditing()
        assertEquals(created, vm.state.value.project!!.elements.single())
    }

    @Test fun `arranging provisional text updates the draft and accepts as one undo step`() {
        val vm = model(emptyList())
        vm.addText("Title", edit = true)
        vm.alignSelectedHorizontal(0)
        vm.alignSelectedVertical(0)
        val draft = vm.state.value.textSession!!.draft
        val bounds = draft.transform.bounds
        assertEquals(.5f, bounds.x + bounds.width / 2f, .0001f)
        assertEquals(.5f, bounds.y + bounds.height / 2f, .0001f)
        assertTrue(vm.state.value.project!!.elements.isEmpty())
        assertFalse(vm.state.value.canUndo)
        vm.confirmTextEditing()
        assertEquals(draft, vm.state.value.project!!.elements.single())
        vm.undo()
        assertTrue(vm.state.value.project!!.elements.isEmpty())
    }

    @Test fun `locked elements and locked groups cannot enter text editing`() {
        val vm = model(listOf(original.copy(groupId = "g"), original.copy(id = "locked", groupId = "g", locked = true)))
        vm.beginTextEditing(original.id); assertNull(vm.state.value.textSession)
        vm.beginTextEditing("locked"); assertNull(vm.state.value.textSession)
    }

    @Test fun `editing one grouped text leaves its companions untouched`() {
        val a = original.copy(groupId = "g")
        val b = original.copy(id = "other", text = "Other", groupId = "g")
        val vm = model(listOf(a, b))
        vm.beginTextEditing(a.id); assertEquals(1, vm.state.value.selectionElements().size)
        assertFalse(vm.state.value.hasSelectionTools())
        vm.updateTextDraft("Edited"); vm.confirmTextEditing()
        assertEquals(b, vm.state.value.project!!.elements.last())
    }

    @Test fun `font browsing cancel restores font while retaining typed words`() {
        val vm = model()
        vm.beginTextEditing(); vm.updateTextDraft("Draft"); vm.beginFontPreview()
        vm.setTextFont(CompositionFonts.Lora.id)
        assertEquals(CompositionFonts.Lora.id, vm.state.value.textSession!!.draft.fontFamily)
        vm.finishFontPreview(false)
        assertEquals(original.fontFamily, vm.state.value.textSession!!.draft.fontFamily)
        assertEquals("Draft", vm.state.value.textSession!!.draft.text)
        vm.confirmTextEditing(); vm.undo(); assertEquals(original, vm.state.value.project!!.elements.single())
    }

    @Test fun `standalone font preview has apply cancel and one history step`() {
        val vm = model()
        vm.beginFontPreview(); vm.setTextFont(CompositionFonts.Lora.id); vm.finishFontPreview(false)
        assertEquals(original, vm.state.value.project!!.elements.single()); assertFalse(vm.state.value.canUndo)
        vm.beginFontPreview(); vm.setTextFont(CompositionFonts.Lora.id); vm.finishFontPreview(true)
        assertEquals(CompositionFonts.Lora.id, (vm.state.value.project!!.elements.single() as CompositionElement.Text).fontFamily)
        vm.undo(); assertEquals(original, vm.state.value.project!!.elements.single())
    }

    @Test fun `side resize reflows automatic text and corners enlarge actual typography`() {
        val vm = model(listOf(original.copy(text = "a long title with several words", autoHeight = true, verticalAlignment = TextVerticalAlignment.TOP)))
        vm.setTextAutoHeight(true)
        val before = vm.state.value.project!!.elements.single() as CompositionElement.Text
        vm.beginGesture(); vm.resizeTextWidth(-.45f, fromLeft = false); vm.endGesture()
        val narrow = vm.state.value.project!!.elements.single() as CompositionElement.Text
        assertEquals(before.fontSize, narrow.fontSize)
        assertTrue(narrow.transform.bounds.height > before.transform.bounds.height)
        vm.resizeSelectedFromCorner(ResizeCorner.BOTTOM_RIGHT, .1f, .1f)
        val enlarged = vm.state.value.project!!.elements.single() as CompositionElement.Text
        assertTrue(enlarged.fontSize > narrow.fontSize)
        vm.undo(); assertEquals(narrow, vm.state.value.project!!.elements.single())
    }

    @Test fun `named styles and copied formatting retain the target words and placement`() {
        val vm = model()
        vm.setTextSize(92f); vm.copyTextStyle(); vm.saveTextStyle("Title")
        val id = kit.value!!.textStyles.single().id
        vm.renameTextStyle(id, "Heading"); assertEquals("Heading", kit.value!!.textStyles.single().name)
        vm.setTextSize(30f); vm.updateSelectedText("Target"); vm.pasteTextStyle()
        val pasted = vm.state.value.project!!.elements.single() as CompositionElement.Text
        assertEquals("Target", pasted.text); assertEquals(92f, pasted.fontSize)
        assertEquals(original.transform, pasted.transform)
        vm.deleteTextStyle(id); assertTrue(kit.value!!.textStyles.isEmpty())
    }

    @Test fun `baseline guides use measured fonts across different box heights and sizes`() {
        val other = original.copy(id = "other", fontSize = 80f,
            transform = ElementTransform(NormalizedRect(.65f, .5f, .3f, .25f)))
        val vm = model(listOf(original, other))
        val bytes = ComposerFontFiles.loadedOrNull(CompositionFonts.Inter)
        fun baseline(text: CompositionElement.Text) = text.transform.bounds.y + measureCompositionText(text,
            text.transform.bounds.width * 1080f, text.transform.bounds.height * 1080f, 1f, bytes).lines.first().baseline / 1080f
        val target = baseline(other)
        vm.beginMoveGesture(); vm.moveSelectedWithGuides(0f, target - baseline(original) - .003f, .0001f, .01f)
        assertTrue(vm.state.value.movementGuides.any { it is CompositionGuide.Alignment && it.axis == GuideAxis.Y && kotlin.math.abs(it.coordinate - target) < .0001f })
        assertEquals(target, baseline(vm.state.value.project!!.elements.first() as CompositionElement.Text), .0001f)
        vm.endGesture(); assertTrue(vm.state.value.movementGuides.isEmpty())
    }
}
