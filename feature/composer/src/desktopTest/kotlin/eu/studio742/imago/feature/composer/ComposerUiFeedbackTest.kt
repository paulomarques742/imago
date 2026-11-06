package eu.studio742.imago.feature.composer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
class ComposerUiFeedbackTest {
    private val left = CompositionElement.Shape("left", ElementTransform(NormalizedRect(.1f, .35f, .25f, .3f)),
        0, ShapeKind.RECTANGLE, fillArgb = 0xFF3F787A)
    private val right = left.copy(id = "right", zIndex = 1,
        transform = ElementTransform(NormalizedRect(.65f, .35f, .25f, .3f)))
    private val haptics = mutableListOf<HapticFeedbackType>()
    private val feedback = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
    }
    private class Projects(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll() = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored
        override suspend fun save(project: CompositionProject) { stored = project }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
    }
    private fun model(elements: List<CompositionElement> = listOf(left, right), pages: Int = 1): ComposerEditorViewModel {
        val source = CompositionProject(id = "feedback", name = "Feedback sample", format = PageFormatPreset.SQUARE_1_1.format,
            pages = List(pages) { CompositionPage("page-$it", it) }, elements = elements, createdAt = "", updatedAt = "")
        val f = FakeComposerRepos
        return ComposerEditorViewModel(Projects(source), f.templates, f.media, f.recipes, f.exports, f.brandKits)
            .also { it.open(source.id) }
    }
    private var more = ""; private var repeat = ""; private var stop = ""; private var undo = ""; private var redo = ""
    @Composable private fun screen(vm: ComposerEditorViewModel) {
        more = stringResource(Res.string.composer_more)
        repeat = stringResource(Res.string.composer_repeat_all_pages)
        stop = stringResource(Res.string.composer_stop_repeating)
        undo = stringResource(Res.string.composer_undo)
        redo = stringResource(Res.string.composer_redo)
        ImagoTheme {
            CompositionLocalProvider(LocalHapticFeedback provides feedback,
                LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(420.dp), 420.dp, 800.dp)) {
                Box(Modifier.size(420.dp, 800.dp).testTag("feedback-review")) {
                    ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                }
            }
        }
    }
    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-feedback-review").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, name).writeBytes(data.bytes) }
        }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `tool edges fade only towards hidden content and follow scrolling and layout direction`() = runComposeUiTest {
        val scroll = ScrollState(0)
        var count by mutableStateOf(8)
        var rtl by mutableStateOf(false)
        var alternate by mutableStateOf(false)
        setContent {
            ImagoTheme(if (alternate) ImagoUiPalette(text = Color.Cyan, accent = Color(0xFFE060A0)) else ImagoUiPalette()) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Box(Modifier.width(240.dp)) {
                        ComposerToolRow(Modifier.testTag("tools"), scroll, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            repeat(count) { Box(Modifier.size(64.dp, 60.dp).background(ImagoUi.colors.accent)) }
                        }
                    }
                }
            }
        }
        fun verify(leftFade: Boolean, rightFade: Boolean) {
            val bitmap = onNodeWithTag("tools").captureToImage().asSkiaBitmap()
            val center = bitmap.getColor(bitmap.width / 2, bitmap.height / 2)
            val edges = listOf(bitmap.getColor(1, bitmap.height / 2), bitmap.getColor(bitmap.width - 2, bitmap.height / 2))
            for ((fades, pixel) in listOf(leftFade, rightFade).zip(edges)) {
                if (fades) {
                    assertNotEquals(center, pixel)
                    assertTrue("Overflow must retain a visible part of the icon", (pixel ushr 24 and 255) >= 85)
                } else assertEquals(center, pixel)
            }
        }
        verify(leftFade = false, rightFade = true)
        capture(onNodeWithTag("tools"), "tools-start.png")
        runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue / 2) } }
        verify(leftFade = true, rightFade = true)
        runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        verify(leftFade = true, rightFade = false)
        capture(onNodeWithTag("tools"), "tools-end.png")
        runOnIdle { count = 2 }
        verify(leftFade = false, rightFade = false)
        assertEquals(0, scroll.maxValue)
        runOnIdle { count = 8; rtl = true; alternate = true }
        runOnIdle { runBlocking { scroll.scrollTo(0) } }
        verify(leftFade = true, rightFade = false)
    }

    @Test fun `overflow always exposes the next icon rather than only its label or empty button padding`() = runComposeUiTest {
        var viewport by mutableStateOf(356.dp)
        setContent {
            ImagoTheme {
                Box(Modifier.width(viewport)) {
                    ComposerToolRow(Modifier.testTag("peek-row")) {
                        repeat(10) { index ->
                            Box(Modifier.testTag("peek-$index")) { ToolButton("Tool $index", Icons.Outlined.Image) {} }
                        }
                    }
                }
            }
        }
        for ((width, next) in listOf(356 to 4, 420 to 5, 600 to 7)) {
            runOnIdle { viewport = width.dp }
            val button = onNodeWithTag("peek-0").fetchSemanticsNode().boundsInRoot
            val partial = onNodeWithTag("peek-$next").fetchSemanticsNode().boundsInRoot
            assertTrue("The clipped button must expose the icon centre", partial.width > button.width / 2f + 4f)
        }
    }

    @Test(timeout = 30_000) fun `long press gives one haptic and an instruction while taps extend selection without moving the page`() = runComposeUiTest {
        val vm = model()
        setContent { screen(vm) }
        val original = vm.state.value.project!!.elements
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer_element_left").performTouchInput { click(center) }
        assertTrue(haptics.isEmpty())
        onNodeWithTag("composer_element_left").performTouchInput { longClick(center) }
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        onNodeWithTag("composer-multiselection-hint").assertIsDisplayed()
        onNodeWithTag("composer_element_right").performTouchInput { click(center) }
        assertEquals(setOf(left.id, right.id), vm.state.value.selectedElementIds)
        assertEquals(page, onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot)
        assertEquals(original, vm.state.value.project!!.elements)
        capture(onNodeWithTag("feedback-review"), "multiple-selection-phone.png")
        onNodeWithTag("composer-multiselection-hint").performTouchInput { down(center); moveBy(Offset(25f, 35f)); up() }
        val movedPage = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        assertNotEquals(page.topLeft, movedPage.topLeft)
        val stage = onNodeWithTag("composer_stage").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer_stage").performTouchInput { click(Offset(movedPage.center.x, movedPage.bottom - 12f) - stage.topLeft) }
        onNodeWithTag("composer-multiselection-hint").assertDoesNotExist()
        assertFalse(vm.state.value.multiSelect)
        assertEquals(1, haptics.size)
    }

    @Test(timeout = 30_000) fun `a locked element does not trigger selection feedback`() = runComposeUiTest {
        val vm = model(listOf(left.copy(locked = true)))
        setContent { screen(vm) }
        onNodeWithTag("composer_element_left").performTouchInput { longClick(center) }
        assertTrue(haptics.isEmpty())
        onNodeWithTag("composer-multiselection-hint").assertDoesNotExist()
        assertTrue(vm.state.value.selectedElementIds.isEmpty())
    }

    @Test(timeout = 30_000) fun `repeat is reversible from the toolbar and remains reversible after reopening`() = runComposeUiTest {
        val vm = model(listOf(left), pages = 3)
        vm.selectElement(left.id)
        setContent { screen(vm) }
        capture(onNodeWithTag("feedback-review"), "secondary-toolbar-phone.png")
        onNodeWithContentDescription(more).performClick()
        onNodeWithText(repeat).performClick()
        val repeated = vm.state.value.project!!.elements.single()
        assertEquals(setOf(0, 1, 2), repeated.repeatOnPages)
        onNodeWithContentDescription(undo).performClick()
        assertEquals(left, vm.state.value.project!!.elements.single())
        onNodeWithContentDescription(redo).performClick()
        assertEquals(repeated, vm.state.value.project!!.elements.single())
        runOnIdle { vm.saveNow(); vm.open("feedback"); vm.selectElement(left.id) }
        onNodeWithContentDescription(more).performClick()
        onNodeWithText(stop).performClick()
        assertEquals(left, vm.state.value.project!!.elements.single())
        onNodeWithContentDescription(undo).performClick()
        assertEquals(repeated, vm.state.value.project!!.elements.single())
    }
}
