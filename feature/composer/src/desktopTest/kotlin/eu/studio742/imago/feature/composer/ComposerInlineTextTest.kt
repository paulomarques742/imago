package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.ImagoUiPalette
import eu.studio742.imago.core.designsystem.LocalImagoWindow
import eu.studio742.imago.core.designsystem.ImagoWindow
import eu.studio742.imago.core.designsystem.ImagoBreakpoints
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ComposerInlineTextTest {
    private val text = CompositionElement.Text("text", ElementTransform(NormalizedRect(.12f, .3f, .76f, .2f)), 0,
        "A title on stage", fontSize = 76f, autoHeight = true, verticalAlignment = TextVerticalAlignment.TOP, colorArgb = 0xFF191919)
    private fun model(): ComposerEditorViewModel {
        val p = CompositionProject(id = "p", name = "Text", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), background = CompositionBackground.Solid(0xFFF6F6F6), elements = listOf(text), createdAt = "", updatedAt = "")
        val f = FakeComposerRepos
        val repo = object : CompositionRepository by f.projects { override suspend fun get(id: String) = p }
        return ComposerEditorViewModel(repo, f.templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open("p") }
    }
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()); runBlocking { ComposerFontFiles.bytes(CompositionFonts.Inter) } }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Composable private fun Stage(vm: ComposerEditorViewModel, alternate: Boolean = false) {
        val state by vm.state.collectAsState()
        ImagoTheme(if (alternate) ImagoUiPalette(text = Color.Cyan, accent = Color.Magenta) else ImagoUiPalette()) {
            Column(Modifier.size(500.dp, 800.dp).testTag("text_review")) {
                ComposerCanvas(state.displayProject()!!, state, false, StrokeKind.PEN, false, false, false,
                    { "" }, { "" }, { "" }, { "" }, vm, {}, {}, {}, ComposerStageInsets(), Modifier.size(500.dp, 500.dp))
                state.selectionElements().singleOrNull()?.let { selected ->
                    if (selected is CompositionElement.Text) ComposerTextTools(selected, state, vm, rail = false)
                }
            }
        }
    }

    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-text-review").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(folder, name).writeBytes(it.bytes) }
        }
    }

    @Test(timeout = 30_000) fun `double tap opens native stage input and typing commits with one undo`() = runComposeUiTest {
        val vm = model()
        setContent { Stage(vm) }
        onNodeWithTag("composer_stage").performTouchInput { doubleClick(Offset(width * .4f, height * .35f)) }
        onNodeWithTag("composer_inline_text").assertExists().performTextReplacement("Modern text\nwith a second line 😀")
        waitForIdle()
        assertEquals(text, vm.state.value.project!!.elements.single())
        capture(onNodeWithTag("text_review"), "editing.png")
        onNodeWithTag("composer_inline_text").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft) }
        assertNull(vm.state.value.textSession)
        assertEquals("Modern text\nwith a second line 😀", (vm.state.value.project!!.elements.single() as CompositionElement.Text).text)
        vm.undo(); assertEquals(text, vm.state.value.project!!.elements.single())
    }

    @Test(timeout = 30_000) fun `side handles reflow and corner handles change the font`() = runComposeUiTest {
        val vm = model()
        vm.selectElement(text.id); vm.setTextAutoHeight(true)
        setContent { Stage(vm, alternate = true) }
        fun stagePoint(x: Float, y: Float) = Offset(18f + x * 464f, 18f + y * 464f)
        val before = vm.state.value.project!!.elements.single() as CompositionElement.Text
        val b = before.transform.bounds
        onNodeWithTag("composer_stage").performTouchInput {
            val point = stagePoint(b.right, b.y + b.height / 2f)
            down(point); moveTo(point + Offset(-210f, 0f), 600); up()
        }
        val narrow = vm.state.value.project!!.elements.single() as CompositionElement.Text
        assertEquals(before.fontSize, narrow.fontSize)
        assertTrue(narrow.transform.bounds.width < b.width)
        assertTrue(narrow.transform.bounds.height > b.height)
        capture(onNodeWithTag("text_review"), "width-handles-alternate-theme.png")
        val nb = narrow.transform.bounds
        onNodeWithTag("composer_stage").performTouchInput {
            val point = stagePoint(nb.right, nb.bottom)
            down(point); moveTo(point + Offset(75f, 60f), 600); up()
        }
        assertTrue((vm.state.value.project!!.elements.single() as CompositionElement.Text).fontSize > narrow.fontSize)
    }

    @Test(timeout = 30_000) fun `new block selects its placeholder and cancel removes all provisional content`() = runComposeUiTest {
        val vm = model()
        vm.addText("Placeholder", edit = true)
        setContent { Stage(vm) }
        onNodeWithTag("composer_inline_text").performTextInput("Replacement")
        assertEquals("Replacement", vm.state.value.textSession!!.draft.text)
        onNodeWithTag("composer_inline_text").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("composer_inline_text").assertDoesNotExist()
        assertEquals(listOf(text), vm.state.value.project!!.elements)
    }

    @Test(timeout = 30_000) fun `native caret baseline and line breaks follow the export painter`() = runComposeUiTest {
        val vm = model()
        vm.beginTextEditing(text.id); vm.updateTextDraft("An editable title with\naccents and emoji 😀")
        setContent { Stage(vm) }
        waitForIdle()
        val node = onNodeWithTag("composer_inline_text")
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val result = results.single()
        val draft = vm.state.value.textSession!!.draft
        val layout = measureCompositionText(draft, draft.transform.bounds.width * 464f,
            draft.transform.bounds.height * 464f, 464f / REFERENCE_PAGE_WIDTH,
            ComposerFontFiles.loadedOrNull(CompositionFonts.Inter))
        assertEquals(layout.lines.size, result.lineCount)
        assertEquals(18f + draft.transform.bounds.y * 464f + layout.lines.first().baseline,
            node.fetchSemanticsNode().boundsInRoot.top + result.firstBaseline, 1.5f)
        layout.lines.forEachIndexed { index, line ->
            assertEquals(line.width, result.getLineRight(index) - result.getLineLeft(index), 1.5f)
        }
    }

    @Test(timeout = 30_000) fun `phone screen exposes apply for a new provisional block and reversible font browsing`() = runComposeUiTest {
        val vm = model()
        setContent {
            ImagoTheme {
                CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(400.dp), 400.dp, 800.dp)) {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                    }
                }
            }
        }
        runOnIdle { vm.addText("Placeholder", edit = true); vm.setElementColor(text.colorArgb) }
        onNodeWithTag("composer_inline_text").performTextReplacement("A modern title")
        onNodeWithTag("composer_text_apply").assertIsDisplayed()
        capture(onRoot(), "phone-inline-edit.png")
        onNodeWithTag("composer_text_apply").performClick()
        runOnIdle { vm.beginFontPreview(); vm.setTextFont(CompositionFonts.Lora.id) }
        waitForIdle()
        capture(onRoot(), "phone-font-preview.png")
        runOnIdle { vm.finishFontPreview(false) }
        assertEquals(CompositionFonts.Inter.id, (vm.state.value.project!!.elements.last() as CompositionElement.Text).fontFamily)
    }

    @Test(timeout = 30_000) fun `style tools save apply copy rename and delete without replacing words`() = runComposeUiTest {
        val vm = model(); vm.selectElement(text.id)
        var styles = ""; var save = ""; var apply = ""; var copy = ""; var paste = ""; var rename = ""; var delete = ""
        setContent { ImagoTheme {
            styles = stringResource(Res.string.composer_text_styles); save = stringResource(Res.string.composer_save_text_style)
            apply = stringResource(Res.string.composer_apply); copy = stringResource(Res.string.composer_copy_text_style)
            paste = stringResource(Res.string.composer_paste_text_style); rename = stringResource(Res.string.composer_rename)
            delete = stringResource(Res.string.composer_delete)
            val state by vm.state.collectAsState()
            Box(Modifier.width(600.dp).testTag("style-review")) { ComposerTextTools(state.selectionElements().single() as CompositionElement.Text, state, vm, false) }
        } }
        onNodeWithContentDescription(styles).performClick()
        onNodeWithText(save).performClick()
        onNode(hasSetTextAction()).performTextInput("Headline")
        onNodeWithText(apply).performClick()
        runOnIdle { vm.setTextSize(30f) }
        onNodeWithText("Headline").performScrollTo().performClick()
        assertEquals(text.fontSize, (vm.state.value.project!!.elements.single() as CompositionElement.Text).fontSize)
        onNodeWithText(copy).performClick()
        runOnIdle { vm.setTextSize(20f) }
        onNodeWithText(paste).performClick()
        assertEquals(text.text, (vm.state.value.project!!.elements.single() as CompositionElement.Text).text)
        assertEquals(text.fontSize, (vm.state.value.project!!.elements.single() as CompositionElement.Text).fontSize)
        onNodeWithContentDescription(rename).performScrollTo().performClick()
        onNode(hasSetTextAction()).performTextReplacement("Subheading")
        onNodeWithText(apply).performClick()
        onNodeWithText("Subheading").assertExists()
        capture(onNodeWithTag("style-review"), "saved-style.png")
        onNodeWithContentDescription(delete).performScrollTo().performClick()
        assertTrue(vm.state.value.brandKit!!.textStyles.isEmpty())
    }

    @Test(timeout = 30_000) fun `font preview stage gestures cannot leak edits into the saved project`() = runComposeUiTest {
        val vm = model(); vm.selectElement(text.id); vm.beginFontPreview(); vm.setTextFont(CompositionFonts.Lora.id)
        val before = vm.state.value.project
        setContent { Stage(vm) }
        waitUntil(timeoutMillis = 5_000) { ComposerFontFiles.loadedOrNull(CompositionFonts.Lora) != null }
        waitForIdle()
        val bounds = vm.state.value.textSession!!.draft.transform.bounds
        onNodeWithTag("composer_stage").performTouchInput {
            val corner = Offset(18f + bounds.right * 464f, 18f + bounds.bottom * 464f)
            down(corner); moveTo(corner + Offset(50f, 50f), 500); up()
        }
        assertEquals(before, vm.state.value.project)
        assertFalse(vm.state.value.canUndo)
        runOnIdle { vm.finishFontPreview(false) }
        assertEquals(before, vm.state.value.project)
    }
}
