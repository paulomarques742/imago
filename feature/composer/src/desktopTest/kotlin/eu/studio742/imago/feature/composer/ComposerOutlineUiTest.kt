package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class ComposerOutlineUiTest {
    private val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.23f, .25f, .4f, .3f), opacity = .65f),
        1, ShapeKind.RECTANGLE, fillArgb = 0xFF448877, strokeArgb = 0xCCE26142, strokeWidth = 30f)
    private fun project(vararg elements: CompositionElement) = CompositionProject(id = "outline", name = "Outline sample", format = PageFormatPreset.SQUARE_1_1.format,
        pages = listOf(CompositionPage("page", 0)), elements = elements.toList(), background = CompositionBackground.Solid(0xFF26394C),
        createdAt = "", updatedAt = "")
    private class Projects(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll() = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored
        override suspend fun save(project: CompositionProject) { stored = Json.decodeFromString(Json.encodeToString(CompositionProject.serializer(), project)) }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
    }
    private fun model(source: CompositionProject, projects: Projects = Projects(source),
        templates: CompositionTemplateRepository = FakeComposerRepos.templates): ComposerEditorViewModel {
        val f = FakeComposerRepos
        return ComposerEditorViewModel(projects, templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open(source.id); it.selectElement(shape.id) }
    }
    private var outlineLabel = ""
    private var outlineColorLabel = ""
    private var fillColorLabel = ""
    private var eyedropperLabel = ""
    private var applyLabel = ""
    private var compositionColorsLabel = ""
    @Composable private fun screen(vm: ComposerEditorViewModel, width: Dp = 420.dp, palette: ImagoUiPalette = ImagoUiPalette()) {
        outlineLabel = stringResource(Res.string.composer_outline)
        outlineColorLabel = stringResource(Res.string.composer_outline_color)
        fillColorLabel = stringResource(Res.string.composer_shape_fill)
        eyedropperLabel = stringResource(Res.string.composer_eyedropper)
        applyLabel = stringResource(Res.string.composer_apply)
        compositionColorsLabel = stringResource(Res.string.composer_composition_colors)
        Box(Modifier.size(width, 760.dp).testTag("outline-review")) {
            ImagoTheme(palette) {
                CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width), width, 760.dp)) {
                    ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                }
            }
        }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test(timeout = 30_000) fun `opening and moving the outline bubble keeps the page stationary and the controls in reach`() = runComposeUiTest {
        val vm = model(project(shape))
        setContent { screen(vm) }
        val before = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithText(outlineLabel).performClick()
        val bubble = onNodeWithTag("composer-outline-bubble").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("outline-width").assertIsDisplayed()
        onNodeWithTag("outline-opacity").assertIsDisplayed()
        onNodeWithText("80%").assertExists()
        onNodeWithTag("outline-corners").assertIsDisplayed()
        onNodeWithTag("composer-bubble-grip").performTouchInput { down(center); moveBy(Offset(-40f, 250f)); up() }
        val after = onNodeWithTag("composer-outline-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(after.top > bubble.top)
        assertEquals(before, onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("outline-corners").assertIsDisplayed()
        onNodeWithTag("outline-close").performClick()
        onNodeWithTag("composer-outline-bubble").assertDoesNotExist()
        assertEquals(shape, vm.state.value.project!!.elements.single())
    }

    @Test(timeout = 30_000) fun `a moved bubble stays inside the stage after the window becomes narrow`() = runComposeUiTest {
        val vm = model(project(shape))
        val width = mutableStateOf(1024.dp)
        setContent { screen(vm, width.value) }
        onNodeWithText(outlineLabel).performClick()
        onNodeWithTag("composer-bubble-grip").performTouchInput { down(center); moveBy(Offset(300f, 250f)); up() }
        runOnIdle { width.value = 420.dp }
        val root = onNodeWithTag("outline-review").fetchSemanticsNode().boundsInRoot
        val bubble = onNodeWithTag("composer-outline-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.left >= root.left && bubble.right <= root.right)
        onNodeWithTag("outline-corners").assertIsDisplayed()
    }

    @Test(timeout = 30_000) fun `no fill keeps outline and global opacity independent and corners stay available`() = runComposeUiTest {
        val vm = model(project(shape))
        setContent { screen(vm) }
        onNodeWithText(outlineLabel).performClick()
        onNodeWithTag("outline-no-fill").performClick()
        var current = vm.state.value.project!!.elements.single() as CompositionElement.Shape
        assertEquals(0L, current.fillArgb ushr 24)
        assertEquals(shape.strokeArgb, current.strokeArgb)
        assertEquals(shape.transform.opacity, current.transform.opacity, 0f)
        onNodeWithTag("outline-width").performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        onNodeWithTag("outline-opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(.25f) }
        onNodeWithTag("outline-corners").performSemanticsAction(SemanticsActions.SetProgress) { it(.25f) }
        current = vm.state.value.project!!.elements.single() as CompositionElement.Shape
        assertEquals(120f, current.strokeWidth, .001f)
        assertEquals(64L, current.strokeArgb ushr 24)
        assertEquals(.25f, current.cornerRadius, .001f)
        assertEquals(0L, current.fillArgb ushr 24)
        assertEquals(shape.transform.opacity, current.transform.opacity, 0f)
        onNodeWithTag("composer-outline-bubble").assertIsDisplayed()
        assertFalse(vm.state.value.stageGesture)
        onNodeWithContentDescription(fillColorLabel).performClick()
        onNodeWithText(applyLabel).performClick()
        val filled = vm.state.value.project!!.elements.single() as CompositionElement.Shape
        assertEquals(shape.fillArgb, filled.fillArgb)
        assertEquals(current.strokeArgb, filled.strokeArgb)
        assertEquals(shape.transform.opacity, filled.transform.opacity, 0f)
    }

    @Test(timeout = 30_000) fun `a continuous thickness adjustment is one undo and never hides the bubble`() = runComposeUiTest {
        val vm = model(project(shape))
        setContent { screen(vm) }
        onNodeWithText(outlineLabel).performClick()
        onNodeWithTag("outline-width").performTouchInput { down(Offset(width * .2f, centerY)); moveTo(Offset(width * .8f, centerY)) }
        onNodeWithTag("composer-outline-bubble").assertIsDisplayed()
        assertFalse(vm.state.value.stageGesture)
        onNodeWithTag("outline-width").performTouchInput { up() }
        assertTrue((vm.state.value.project!!.elements.single() as CompositionElement.Shape).strokeWidth > 80f)
        runOnIdle { vm.undo() }
        assertEquals(shape, vm.state.value.project!!.elements.single())
    }

    @Test(timeout = 30_000) fun `outline color keeps composition swatches and sampling resumes the same picker`() = runComposeUiTest {
        val vm = model(project(shape))
        setContent { screen(vm) }
        onNodeWithText(outlineLabel).performClick()
        onNodeWithContentDescription(outlineColorLabel).performClick()
        onNodeWithText(compositionColorsLabel).assertExists()
        onNodeWithContentDescription(eyedropperLabel).performClick()
        onNodeWithTag("composer-outline-bubble").assertDoesNotExist()
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer_stage").performTouchInput { click(page.topLeft + Offset(page.width * .8f, page.height * .8f)) }
        onNodeWithText(applyLabel).performClick()
        val after = vm.state.value.project!!.elements.single() as CompositionElement.Shape
        assertEquals(0xCC26394CL, after.strokeArgb)
        onNodeWithTag("composer-outline-bubble").assertExists()
    }

    @Test fun `hollow rounded outlines survive duplication template saving and reopening`() {
        val source = project(shape)
        val projects = Projects(source)
        var saved: CompositionTemplate? = null
        val templates = object : CompositionTemplateRepository by FakeComposerRepos.templates {
            override suspend fun save(template: CompositionTemplate) { saved = template }
        }
        val vm = model(source, projects, templates)
        vm.setShapeNoFill(); vm.setOutlineWidth(120f); vm.setOutlineCorner(.3f); vm.setOutlineOpacity(.4f)
        val expected = vm.state.value.project!!.elements.single() as CompositionElement.Shape
        vm.duplicateSelected()
        vm.saveNow()
        vm.saveAsTemplate("Hollow", false)
        val reopened = model(projects.stored).state.value.project!!
        for (copy in reopened.elements + saved!!.project.elements) {
            val restored = copy as CompositionElement.Shape
            assertEquals(expected.fillArgb, restored.fillArgb)
            assertEquals(expected.strokeArgb, restored.strokeArgb)
            assertEquals(expected.strokeWidth, restored.strokeWidth, 0f)
            assertEquals(expected.cornerRadius, restored.cornerRadius, 0f)
        }
    }

    @Test fun `compatible selections share outline parameters and locked or incompatible selections cannot be changed`() {
        val slot = CompositionElement.MediaPlaceholder("slot", shape.transform, 0)
        val vm = model(project(shape, slot))
        vm.toggleElementInSelection(slot.id)
        vm.setOutlineWidth(200f); vm.setOutlineOpacity(.25f); vm.setOutlineCorner(.2f)
        val a = vm.state.value.project!!.elements[0] as CompositionElement.Shape
        val b = vm.state.value.project!!.elements[1] as CompositionElement.MediaPlaceholder
        assertEquals(120f, a.strokeWidth, 0f)
        assertEquals(120f, b.frame.borderWidth, 0f)
        assertEquals(64L, a.strokeArgb ushr 24)
        assertEquals(64L, b.frame.borderArgb ushr 24)
        assertEquals(.2f, a.cornerRadius, 0f)
        assertEquals(.2f, b.frame.cornerRadius, 0f)
        val text = CompositionElement.Text("text", shape.transform, 0, "Text")
        val incompatible = model(project(shape, text))
        incompatible.toggleElementInSelection(text.id)
        incompatible.setOutlineWidth(100f)
        assertEquals(listOf(shape, text), incompatible.state.value.project!!.elements)
        val locked = model(project(shape.copy(locked = true)))
        assertTrue(locked.state.value.outlineSelection().isEmpty())
        locked.setOutlineWidth(100f)
        assertEquals(shape.copy(locked = true), locked.state.value.project!!.elements.single())
    }

    @Test fun `lines keep their stroke color and minimum thickness without exposing fill or corner controls`() {
        val line = shape.copy(kind = ShapeKind.LINE)
        val vm = model(project(line))
        vm.setShapeNoFill(); vm.setOutlineWidth(0f); vm.setOutlineCorner(.4f)
        val after = vm.state.value.project!!.elements.single() as CompositionElement.Shape
        assertEquals(line.fillArgb, after.fillArgb)
        assertEquals(.5f, after.strokeWidth, 0f)
        assertEquals(0f, after.cornerRadius, 0f)
    }

    @Test(timeout = 30_000) fun `phone tablet and alternate theme previews retain content colors while the bubble floats`() = runComposeUiTest {
        val backing = shape.copy(id = "backing", zIndex = 0, fillArgb = 0xFF36594B, strokeWidth = 0f,
            transform = ElementTransform(NormalizedRect(.1f, .1f, .8f, .8f)))
        val vm = model(project(backing, shape.copy(transform = shape.transform.copy(opacity = 1f))))
        vm.setShapeNoFill(); vm.setOutlineWidth(120f)
        val phone = mutableStateOf(true)
        val alternate = mutableStateOf(false)
        val palette = ImagoUiPalette(glass = Color(0xFF173E45), accent = Color.Cyan, text = Color(0xFFE2FFFF))
        val renderer = CompositionRenderer()
        val before = renderer.render(vm.state.value.project!!, 0, emptyMap(), 240)
        setContent { screen(vm, if (phone.value) 420.dp else 1024.dp, if (alternate.value) palette else ImagoUiPalette()) }
        onNodeWithText(outlineLabel).performClick()
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-outline-review").apply { mkdirs() }
        fun capture(name: String) {
            Image.makeFromBitmap(onNodeWithTag("outline-review").captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, name).writeBytes(data.bytes) }
            }
        }
        capture("outline-phone.png")
        onNodeWithTag("composer-bubble-grip").performTouchInput { down(center); moveBy(Offset(0f, 230f)); up() }
        capture("outline-phone-moved.png")
        runOnIdle { phone.value = false }
        capture("outline-tablet.png")
        runOnIdle { alternate.value = true }
        capture("outline-alternate-theme.png")
        val bitmap = onNodeWithTag("composer-outline-bubble").captureToImage().asSkiaBitmap()
        val accent = palette.accent.toArgb()
        assertTrue((0 until bitmap.height).sumOf { y -> (0 until bitmap.width).count { x -> bitmap.getColor(x, y) == accent } } > 10)
        assertArrayEquals(before, renderer.render(vm.state.value.project!!, 0, emptyMap(), 240))
    }
}
