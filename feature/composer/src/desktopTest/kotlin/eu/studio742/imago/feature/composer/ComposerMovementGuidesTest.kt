package eu.studio742.imago.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ComposerMovementGuidesTest {
    private val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.23f, .31f, .17f, .13f)),
        0, ShapeKind.RECTANGLE, fillArgb = 0xFFE26142, strokeWidth = 0f)
    private fun project(vararg elements: CompositionElement, pages: Int = 1) = CompositionProject(
        id = "guides", name = "Alignment guides", format = PageFormat.custom(600, 600),
        pages = List(pages) { CompositionPage("page-$it", it) }, elements = elements.toList(),
        background = CompositionBackground.Solid(0xFF26394C), createdAt = "", updatedAt = "")
    private class Projects(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll() = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored
        override suspend fun save(project: CompositionProject) { stored = Json.decodeFromString(Json.encodeToString(project)) }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
    }
    private fun model(source: CompositionProject, repository: Projects = Projects(source)): ComposerEditorViewModel {
        val f = FakeComposerRepos
        return ComposerEditorViewModel(repository, f.templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open(source.id) }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `one snapped drag is one undo and transient guides never enter saves or exports`() {
        val source = project(shape)
        val repository = Projects(source)
        val vm = model(source, repository)
        vm.selectElement(shape.id)
        vm.beginMoveGesture()
        vm.moveSelectedWithGuides(-.225f, 0f, .01f, .01f)
        vm.moveSelectedWithGuides(.001f, 0f, .01f, .01f)
        assertEquals(0f, vm.state.value.project!!.elements.single().transform.bounds.x, .00001f)
        assertTrue(vm.state.value.movementGuides.isNotEmpty())
        val renderer = CompositionRenderer()
        val during = renderer.render(vm.state.value.project!!, 0, emptyMap(), maxSide = 240)
        vm.saveNow()
        val saved = Json.encodeToString(repository.stored)
        assertFalse(saved.contains("movementGuides"))
        vm.endGesture()
        assertTrue(vm.state.value.movementGuides.isEmpty())
        assertArrayEquals(during, renderer.render(vm.state.value.project!!, 0, emptyMap(), maxSide = 240))
        vm.undo()
        assertEquals(shape, vm.state.value.project!!.elements.single())
        vm.redo()
        assertEquals(0f, vm.state.value.project!!.elements.single().transform.bounds.x, .00001f)
        val reopened = model(repository.stored, repository)
        assertTrue(reopened.state.value.movementGuides.isEmpty())
        assertEquals(0f, reopened.state.value.project!!.elements.single().transform.bounds.x, .00001f)
    }

    @Test fun `groups and multiple selections translate whole without changing sizes rotation or internal offsets`() {
        for (group in listOf(false, true)) {
            val a = shape.copy(groupId = if (group) "group" else null)
            val b = shape.copy(id = "second", groupId = a.groupId,
                transform = ElementTransform(NormalizedRect(.61f, .56f, .19f, .17f), rotationDegrees = 20f))
            val vm = model(project(a, b))
            vm.selectElement(a.id)
            if (!group) vm.toggleElementInSelection(b.id)
            vm.beginMoveGesture()
            vm.moveSelectedWithGuides(-.225f, 0f, .01f, .001f)
            vm.endGesture()
            val after = vm.state.value.project!!.elements
            assertEquals(0f, after[0].transform.bounds.x, .00001f)
            assertEquals(b.transform.bounds.x - a.transform.bounds.x,
                after[1].transform.bounds.x - after[0].transform.bounds.x, .00001f)
            for ((before, moved) in listOf(a to after[0], b to after[1])) {
                assertEquals(before.transform.bounds.width, moved.transform.bounds.width, 0f)
                assertEquals(before.transform.bounds.height, moved.transform.bounds.height, 0f)
                assertEquals(before.transform.rotationDegrees, moved.transform.rotationDegrees, 0f)
                assertEquals(before.transform.bounds.y, moved.transform.bounds.y, .00001f)
            }
        }
    }

    @Test fun `a grabbed repeat snaps on its displayed page without rewriting its repeat origin`() {
        val repeated = shape.copy(repeatOnPages = setOf(0, 1))
        val vm = model(project(repeated, pages = 2))
        vm.selectElement(repeated.id)
        vm.beginMoveGesture(instanceOffsetX = 1f)
        vm.moveSelectedWithGuides(-.225f, 0f, .01f, .01f)
        val placed = vm.state.value.project!!.elements.single()
        assertEquals(1f, placed.boundsOnPage(1).x, .00001f)
        assertEquals(0, placed.repeatOriginPage())
        assertTrue(vm.state.value.movementGuides.filterIsInstance<CompositionGuide.Alignment>()
            .any { it.axis == GuideAxis.X && it.coordinate == 1f })
        vm.endGesture()
    }

    @Test fun `keyboard nudges stay exact and changing selection or starting another tool removes guides`() {
        val second = shape.copy(id = "second", transform = ElementTransform(NormalizedRect(.71f, .67f, .17f, .13f)))
        val vm = model(project(shape, second))
        vm.selectElement(shape.id)
        vm.moveSelected(-.225f, 0f)
        assertEquals(.005f, vm.state.value.project!!.elements.first().transform.bounds.x, .00001f)
        assertTrue(vm.state.value.movementGuides.isEmpty())
        vm.beginMoveGesture()
        vm.moveSelectedWithGuides(.001f, 0f, .01f, .01f)
        assertTrue(vm.state.value.movementGuides.isNotEmpty())
        vm.selectElement(second.id)
        assertTrue(vm.state.value.movementGuides.isEmpty())
        vm.endGesture()
        vm.selectElement(shape.id)
        vm.beginMoveGesture()
        vm.moveSelectedWithGuides(.001f, 0f, .01f, .01f)
        assertTrue(vm.state.value.movementGuides.isNotEmpty())
        vm.beginGesture(stage = false)
        assertTrue(vm.state.value.movementGuides.isEmpty())
        vm.endGesture(stage = false)
    }

    @Composable private fun canvas(vm: ComposerEditorViewModel) {
        val state = vm.state.collectAsState().value
        ImagoTheme {
            ComposerCanvas(state.project!!, state, false, StrokeKind.PEN, state.multiSelect, false, false,
                { "" }, { "" }, { "" }, { "" }, vm, {}, {}, {}, ComposerStageInsets(), Modifier.size(600.dp, 800.dp))
        }
    }

    @Test(timeout = 30_000) fun `touch drag shows matching guides and small continued moves push through the snap`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        setContent { canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val start = page.topLeft + Offset(page.width * .315f, page.height * .375f)
        onNodeWithTag("composer_stage").performTouchInput {
            down(start)
            moveBy(Offset(-page.width * .23f + 3f, 0f))
        }
        assertEquals(0f, vm.state.value.project!!.elements.single().transform.bounds.x, .00001f)
        onNodeWithTag("composer_movement_guides").assertExists()
        repeat(4) { onNodeWithTag("composer_stage").performTouchInput { moveBy(Offset(2f, 0f)) } }
        assertEquals(11f / page.width, vm.state.value.project!!.elements.single().transform.bounds.x, .0001f)
        onNodeWithTag("composer_stage").performTouchInput { up() }
        onNodeWithTag("composer_movement_guides").assertDoesNotExist()
        assertFalse(vm.state.value.stageGesture)
    }

    @Test(timeout = 30_000) fun `removing the stage during a drag cancels the gesture and clears transient state`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        val shown = mutableStateOf(true)
        setContent { if (shown.value) canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val start = page.topLeft + Offset(page.width * .315f, page.height * .375f)
        onNodeWithTag("composer_stage").performTouchInput {
            down(start); moveBy(Offset(-page.width * .23f + 3f, 0f))
        }
        onNodeWithTag("composer_movement_guides").assertExists()
        // The desktop test dispatcher does not send cancel events. Removing the node cancels its coroutine.
        runOnIdle { shown.value = false }
        onNodeWithTag("composer_movement_guides").assertDoesNotExist()
        runOnIdle {
            assertTrue(vm.state.value.movementGuides.isEmpty())
            assertFalse(vm.state.value.stageGesture)
        }
    }

    @Test(timeout = 30_000) fun `switching from an active drag to two fingers clears guides without editing artwork`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        setContent { canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val start = page.topLeft + Offset(page.width * .315f, page.height * .375f)
        onNodeWithTag("composer_stage").performTouchInput {
            down(0, start); moveBy(0, Offset(-page.width * .23f + 3f, 0f)); move()
        }
        onNodeWithTag("composer_movement_guides").assertExists()
        val before = vm.state.value.project!!.elements.single()
        onNodeWithTag("composer_stage").performTouchInput {
            down(1, Offset(400f, 500f)); moveBy(0, Offset(-10f, 0f)); moveBy(1, Offset(10f, 0f)); move()
        }
        onNodeWithTag("composer_movement_guides").assertDoesNotExist()
        assertEquals(before, vm.state.value.project!!.elements.single())
        onNodeWithTag("composer_stage").performTouchInput { up(0); up(1) }
    }

    @Test(timeout = 30_000) fun `mouse drags share the snap and release behaviour and hide guides on button release`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        setContent { canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val start = page.topLeft + Offset(page.width * .315f, page.height * .375f)
        onNodeWithTag("composer_stage").performMouseInput {
            moveTo(start); press(); moveBy(Offset(-page.width * .23f + 3f, 0f))
        }
        assertEquals(0f, vm.state.value.project!!.elements.single().transform.bounds.x, .00001f)
        onNodeWithTag("composer_movement_guides").assertExists()
        onNodeWithTag("composer_stage").performMouseInput { moveBy(Offset(8f, 0f)) }
        assertEquals(11f / page.width, vm.state.value.project!!.elements.single().transform.bounds.x, .0001f)
        onNodeWithTag("composer_stage").performMouseInput { release() }
        onNodeWithTag("composer_movement_guides").assertDoesNotExist()
    }

    @Test fun `guide strokes use independent theme roles at either page scale`() {
        for (scale in listOf(.5f, 2f)) {
            val palette = ImagoUiPalette(alignmentGuide = Color.Green, spacingGuide = Color.Magenta)
            val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
                ImagoTheme(palette) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        ComposerMovementGuides(listOf(CompositionGuide.Alignment(GuideAxis.X, .25f / scale, 0f, 1f / scale),
                            CompositionGuide.Spacing(GuideAxis.X, .1f / scale, .4f / scale, .75f / scale)),
                            Offset.Zero, 200f * scale, 200f * scale)
                    }
                }
            }
            try {
                Bitmap().use { bitmap ->
                    bitmap.allocN32Pixels(200, 200); scene.render().readPixels(bitmap)
                    val alignmentPixels = (0 until 200).count { y -> (49..50).any { x ->
                        val c = bitmap.getColor(x, y); (c ushr 8 and 255) > 100 && (c ushr 16 and 255) < 20 && (c and 255) < 20
                    } }
                    val spacingPixels = (20..80).count { x -> (149..150).any { y ->
                        val c = bitmap.getColor(x, y); (c ushr 16 and 255) > 100 && (c and 255) > 100 && (c ushr 8 and 255) < 20
                    } }
                    assertTrue(alignmentPixels > 80)
                    assertTrue(spacingPixels > 50)
                }
            } finally { scene.close() }
        }
    }

    @Test(timeout = 30_000) fun `phone and tablet previews show alignment and equal spacing during move and resize`() = runComposeUiTest {
        val a = shape.copy(id = "left", transform = ElementTransform(NormalizedRect(.1f, .34f, .1f, .12f)))
        val b = shape.copy(id = "right", transform = ElementTransform(NormalizedRect(.74f, .34f, .1f, .12f)))
        val middle = shape.copy(transform = ElementTransform(NormalizedRect(.31f, .34f, .11f, .12f)))
        val vm = model(project(a, middle, b))
        vm.selectElement(middle.id)
        vm.beginMoveGesture()
        vm.moveSelectedWithGuides(.109f, 0f, .005f, .005f)
        val phone = mutableStateOf(true)
        setContent {
            val width = if (phone.value) 420.dp else 1024.dp
            Box(Modifier.size(width, 760.dp).testTag("guides-review")) {
                ImagoTheme {
                    CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width), width, 760.dp)) {
                        key(phone.value) {
                            ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                        }
                    }
                }
            }
        }
        val output = File(System.getProperty("java.io.tmpdir"), "imago-composer-guides-review").apply { mkdirs() }
        fun capture(name: String) {
            onNodeWithTag("composer_movement_guides").assertExists()
            Image.makeFromBitmap(onNodeWithTag("guides-review").captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(output, name).writeBytes(data.bytes) }
            }
        }
        capture("guides-phone.png")
        runOnIdle { phone.value = false }
        capture("guides-tablet.png")
        runOnIdle {
            vm.endGesture(); vm.undo()
            vm.beginResizeGesture(ResizeCorner.BOTTOM_RIGHT)
            vm.resizeSelectedWithGuides(.206f, 0f, .005f, .005f)
        }
        capture("resize-guides-tablet.png")
        runOnIdle { phone.value = true }
        capture("resize-guides-phone.png")
    }

    @Test(timeout = 30_000) fun `all four resize handles show guides while keeping their opposite corner fixed`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        setContent { canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val before = shape.transform.bounds
        for (corner in ResizeCorner.entries) {
            val left = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT
            val top = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT
            val start = page.topLeft + Offset((if (left) before.x else before.right) * page.width,
                (if (top) before.y else before.bottom) * page.height)
            val target = page.topLeft + Offset(if (left) 3f else page.width - 3f, if (top) 3f else page.height - 3f)
            onNodeWithTag("composer_stage").performTouchInput { down(start); moveTo(target) }
            onNodeWithTag("composer_movement_guides").assertExists()
            val after = vm.state.value.project!!.elements.single().transform.bounds
            assertEquals(if (left) 0f else 1f, if (left) after.x else after.right, .0001f)
            assertEquals(if (top) 0f else 1f, if (top) after.y else after.bottom, .0001f)
            assertEquals(if (left) before.right else before.x, if (left) after.right else after.x, .0001f)
            assertEquals(if (top) before.bottom else before.y, if (top) after.bottom else after.y, .0001f)
            onNodeWithTag("composer_stage").performTouchInput { up() }
            onNodeWithTag("composer_movement_guides").assertDoesNotExist()
            runOnIdle { vm.undo() }
            assertEquals(shape, vm.state.value.project!!.elements.single())
        }
    }

    @Test(timeout = 30_000) fun `mouse resize can push through a guide and make a fine correction back`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        setContent { canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val before = shape.transform.bounds
        val start = page.topLeft + Offset(before.right * page.width, before.bottom * page.height)
        onNodeWithTag("composer_stage").performMouseInput {
            moveTo(start); press(); moveBy(Offset((1f - before.right) * page.width - 3f, 0f))
        }
        assertEquals(1f, vm.state.value.project!!.elements.single().transform.bounds.right, .0001f)
        onNodeWithTag("composer_movement_guides").assertExists()
        onNodeWithTag("composer_stage").performMouseInput { moveBy(Offset(-8f, 0f)) }
        assertEquals(1f - 11f / page.width, vm.state.value.project!!.elements.single().transform.bounds.right, .0001f)
        onNodeWithTag("composer_stage").performMouseInput { moveBy(Offset(8f, 0f)) }
        val fine = vm.state.value.project!!.elements.single().transform.bounds
        assertEquals(1f - 3f / page.width, fine.right, .0001f)
        assertEquals(before.x, fine.x, .0001f)
        assertEquals(before.y, fine.y, .0001f)
        onNodeWithTag("composer_stage").performMouseInput { release() }
        onNodeWithTag("composer_movement_guides").assertDoesNotExist()
    }

    @Test fun `snapped resize is one undo and guides stay out of saved and exported artwork`() {
        val source = project(shape)
        val repository = Projects(source)
        val vm = model(source, repository)
        vm.selectElement(shape.id)
        vm.beginResizeGesture(ResizeCorner.BOTTOM_RIGHT)
        vm.resizeSelectedWithGuides(.596f, .556f, .01f, .01f)
        vm.resizeSelectedWithGuides(.0001f, .0001f, .01f, .01f)
        assertTrue(vm.state.value.movementGuides.isNotEmpty())
        val after = vm.state.value.project!!.elements.single().transform.bounds
        assertEquals(shape.transform.bounds.x, after.x, .0001f)
        assertEquals(shape.transform.bounds.y, after.y, .0001f)
        assertEquals(1f, after.right, .0001f)
        assertEquals(1f, after.bottom, .0001f)
        vm.saveNow()
        val renderer = CompositionRenderer()
        val beforeEnd = renderer.render(vm.state.value.project!!, 0, emptyMap(), maxSide = 240)
        vm.endGesture()
        assertTrue(vm.state.value.movementGuides.isEmpty())
        assertArrayEquals(beforeEnd, renderer.render(vm.state.value.project!!, 0, emptyMap(), maxSide = 240))
        assertFalse(Json.encodeToString(repository.stored).contains("movementGuides"))
        vm.undo()
        assertEquals(shape, vm.state.value.project!!.elements.single())
        vm.redo()
        assertEquals(after, vm.state.value.project!!.elements.single().transform.bounds)
    }

    @Test fun `groups and multiple selections resize together through the guided handle path`() {
        for (grouped in listOf(false, true)) {
            val a = shape.copy(groupId = if (grouped) "group" else null,
                transform = ElementTransform(NormalizedRect(.2f, .25f, .12f, .17f)))
            val b = a.copy(id = "second", transform = ElementTransform(NormalizedRect(.52f, .55f, .16f, .17f)))
            val vm = model(project(a, b))
            vm.selectElement(a.id)
            if (!grouped) vm.toggleElementInSelection(b.id)
            vm.beginResizeGesture(ResizeCorner.BOTTOM_RIGHT)
            vm.resizeSelectedWithGuides(.316f, .276f, .01f, .01f)
            val bounds = vm.state.value.project!!.elements.map { it.transform.bounds }.boundingBox()!!
            assertEquals(.2f, bounds.x, .0001f)
            assertEquals(.25f, bounds.y, .0001f)
            assertEquals(1f, bounds.right, .0001f)
            assertEquals(1f, bounds.bottom, .0001f)
            assertTrue(vm.state.value.movementGuides.isNotEmpty())
            vm.endGesture()
            vm.undo()
            assertEquals(listOf(a, b), vm.state.value.project!!.elements)
        }
    }

    @Test(timeout = 30_000) fun `removing the stage during a resize clears guides and ends the gesture`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        val shown = mutableStateOf(true)
        setContent { if (shown.value) canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val bounds = shape.transform.bounds
        onNodeWithTag("composer_stage").performTouchInput {
            down(page.topLeft + Offset(bounds.right * page.width, bounds.bottom * page.height))
            moveBy(Offset((1f - bounds.right) * page.width - 3f, 0f))
        }
        onNodeWithTag("composer_movement_guides").assertExists()
        runOnIdle { shown.value = false }
        runOnIdle {
            assertTrue(vm.state.value.movementGuides.isEmpty())
            assertFalse(vm.state.value.stageGesture)
        }
    }

    @Test(timeout = 30_000) fun `two fingers cancel resize guides and navigate without further resizing`() = runComposeUiTest {
        val vm = model(project(shape))
        vm.selectElement(shape.id)
        setContent { canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val bounds = shape.transform.bounds
        onNodeWithTag("composer_stage").performTouchInput {
            down(0, page.topLeft + Offset(bounds.right * page.width, bounds.bottom * page.height))
            moveBy(0, Offset((1f - bounds.right) * page.width - 3f, 0f)); move()
        }
        onNodeWithTag("composer_movement_guides").assertExists()
        val resized = vm.state.value.project!!.elements.single()
        onNodeWithTag("composer_stage").performTouchInput {
            down(1, Offset(400f, 500f)); moveBy(0, Offset(-10f, 0f)); moveBy(1, Offset(10f, 0f)); move()
        }
        onNodeWithTag("composer_movement_guides").assertDoesNotExist()
        assertEquals(resized, vm.state.value.project!!.elements.single())
        onNodeWithTag("composer_stage").performTouchInput { up(0); up(1) }
    }
}
