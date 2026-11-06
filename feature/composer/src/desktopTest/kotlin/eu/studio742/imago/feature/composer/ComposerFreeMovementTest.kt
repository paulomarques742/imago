package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.LocalImagoWindow
import eu.studio742.imago.core.designsystem.ImagoWindow
import eu.studio742.imago.core.designsystem.ImagoBreakpoints
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
import java.io.ByteArrayInputStream
import java.io.File
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class ComposerFreeMovementTest {
    private val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.2f, .3f, .3f, .2f)),
        0, ShapeKind.RECTANGLE, fillArgb = 0xFFFF0000, strokeWidth = 0f)
    private val video = CompositionElement.Video("video", shape.transform, 1, 0,
        MediaReference("clip", "sum", "clip.mp4", durationMs = 2_000), VideoTiming(trimEndMs = 2_000))
    private fun project(elements: List<CompositionElement>, pages: Int = 1) = CompositionProject(
        id = "movement", name = "Movement", format = PageFormat.custom(240, 240), pages = List(pages) { CompositionPage("page-$it", it) },
        elements = elements, background = CompositionBackground.Solid(0xFF000000), createdAt = "", updatedAt = "")

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

    @Test fun `each edge permits a whole element outside and undo and reopen retain the placement`() {
        for ((dx, dy) in listOf(-2f to 0f, 2f to 0f, 0f to -2f, 0f to 2f)) {
            for (element in listOf(shape, video, CompositionElement.Text("text", shape.transform, 0, "Text"),
                CompositionElement.MediaPlaceholder("slot", shape.transform, 0),
                CompositionElement.Photo("photo", shape.transform, 0, MediaReference("photo", "sum", "photo.jpg"),
                    PhotoRecipeSnapshot(eu.studio742.imago.core.model.EditRecipe(assetId = "photo", originalChecksum = "sum", createdAt = "", updatedAt = ""))),
                CompositionElement.Drawing("drawing", shape.transform, 0, kind = StrokeKind.PEN, points = listOf(StrokePoint(0f, 0f), StrokePoint(1f, 1f)),
                    colorArgb = 0xFFFFFFFF, strokeWidth = 2f))) {
                val source = project(listOf(element))
                val repository = Projects(source)
                val vm = model(source, repository)
                vm.selectElement(element.id)
                vm.moveSelected(dx, dy)
                val moved = vm.state.value.project!!.elements.single()
                assertEquals(element.transform.bounds.x + dx, moved.transform.bounds.x, .0001f)
                assertEquals(element.transform.bounds.y + dy, moved.transform.bounds.y, .0001f)
                vm.saveNow()
                val reopened = model(repository.stored, repository).state.value.project!!.elements.single()
                assertEquals(moved, reopened)
                vm.undo()
                assertEquals(element.transform.bounds, vm.state.value.project!!.elements.single().transform.bounds)
                vm.redo()
                assertEquals(moved, vm.state.value.project!!.elements.single())
                if (moved is CompositionElement.Video) {
                    assertEquals(video.pageIndex, moved.pageIndex)
                    assertEquals(video.timing, moved.timing)
                }
            }
        }
    }

    @Test fun `group translation rotation and resize outside the page preserve its arrangement`() {
        val a = shape.copy(groupId = "group")
        val b = shape.copy(id = "second", transform = shape.transform.copy(bounds = NormalizedRect(.7f, .3f, .3f, .2f)), groupId = "group")
        val vm = model(project(listOf(a, b)))
        vm.selectElement(a.id)
        vm.moveSelected(-3f, -2f)
        var elements = vm.state.value.project!!.elements
        assertEquals(.5f, elements[1].transform.bounds.x - elements[0].transform.bounds.x, .0001f)
        assertEquals(-2.8f, elements[0].transform.bounds.x, .0001f)
        vm.rotateSelectedBy(90f)
        elements = vm.state.value.project!!.elements
        assertEquals(.5f, elements[1].transform.bounds.y - elements[0].transform.bounds.y, .0001f)
        vm.scaleSelected(1.5f)
        elements = vm.state.value.project!!.elements
        assertEquals(.75f, elements[1].transform.bounds.y - elements[0].transform.bounds.y, .0001f)
        assertEquals(.45f, elements[0].transform.bounds.width, .0001f)
        assertEquals(90f, elements[0].transform.rotationDegrees, .0001f)
    }

    @Test fun `repeated artwork keeps an exterior offset through persistence`() {
        val repeated = shape.withRepeatOnPages(setOf(0, 1))
        val source = project(listOf(repeated), 2)
        val repository = Projects(source)
        val vm = model(source, repository)
        vm.selectElement(shape.id)
        vm.moveSelected(-1.5f, -.8f)
        vm.saveNow()
        val restored = repository.stored.elements.single()
        assertEquals(-1.3f, restored.boundsOnPage(0).x, .0001f)
        assertEquals(-.3f, restored.boundsOnPage(1).x, .0001f)
        assertEquals(-.5f, restored.boundsOnPage(0).y, .0001f)
    }

    @Test fun `a rotated corner resize outside a tall page keeps the opposite corner stationary`() {
        val rotated = shape.copy(transform = ElementTransform(NormalizedRect(-2f, -3f, .4f, .2f), 45f))
        val source = project(listOf(rotated)).copy(format = PageFormatPreset.STORY_9_16.format)
        val vm = model(source)
        fun opposite(element: CompositionElement): Pair<Float, Float> {
            val b = element.transform.bounds
            val (x, y) = rotateVector(b.width * source.format.aspectRatio / 2, b.height / 2, 45f)
            return (b.x + b.width / 2) * source.format.aspectRatio + x to b.y + b.height / 2 + y
        }
        val before = opposite(rotated)
        vm.selectElement(rotated.id)
        vm.resizeSelectedFromCorner(ResizeCorner.TOP_LEFT, -.1f, -.1f)
        val after = opposite(vm.state.value.project!!.elements.single())
        assertEquals(before.first, after.first, .0001f)
        assertEquals(before.second, after.second, .0001f)
    }

    @Test fun `a widely separated group scales uniformly until the first member reaches its size limit`() {
        val a = shape.copy(transform = ElementTransform(NormalizedRect(-20f, -3f, .4f, .2f)), groupId = "g")
        val b = shape.copy(id = "b", transform = ElementTransform(NormalizedRect(20f, -3f, .8f, .4f)), groupId = "g")
        val vm = model(project(listOf(a, b)))
        vm.selectElement(a.id)
        vm.scaleSelected(2f)
        val members = vm.state.value.project!!.elements
        assertEquals(.5f, members[0].transform.bounds.width, .0001f)
        assertEquals(.25f, members[0].transform.bounds.height, .0001f)
        assertEquals(1f, members[1].transform.bounds.width, .0001f)
        assertEquals(50f, members[1].transform.bounds.x - members[0].transform.bounds.x, .0001f)
    }

    @Test fun `reordering or deleting a page uses the clips page instead of its exterior position`() {
        val outside = video.copy(transform = video.transform.copy(bounds = NormalizedRect(2.3f, -.4f, .3f, .2f)))
        val source = project(listOf(outside), 3)
        val vm = model(source)
        vm.movePage(1)
        val moved = vm.state.value.project!!.elements.single() as CompositionElement.Video
        assertEquals(1, moved.pageIndex)
        assertEquals(3.3f, moved.transform.bounds.x, .0001f)
        vm.deleteCurrentPage()
        assertTrue(vm.state.value.project!!.elements.isEmpty())
    }

    @Test fun `exports clip outside artwork and include rotated artwork that enters a page`() {
        fun pixels(element: CompositionElement) = ImageIO.read(ByteArrayInputStream(
            CompositionRenderer().render(project(listOf(element)), 0, emptyMap(), maxSide = 100)))
        val partial = shape.copy(transform = shape.transform.copy(bounds = NormalizedRect(-.2f, .3f, .4f, .2f)))
        val image = pixels(partial)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(5, 35))
        assertEquals(0xFF000000.toInt(), image.getRGB(30, 35))
        val outside = shape.copy(transform = shape.transform.copy(bounds = NormalizedRect(-1f, .3f, .3f, .2f)))
        assertEquals(0xFF000000.toInt(), pixels(outside).getRGB(5, 35))
        val rotated = shape.copy(transform = ElementTransform(NormalizedRect(-.15f, .3f, .1f, .4f), 90f))
        assertEquals(0xFFFF0000.toInt(), pixels(rotated).getRGB(5, 50))
    }

    @Test fun `repeated exports retain negative placement and never draw on unlisted pages`() {
        val repeated = shape.withRepeatOnPages(setOf(0, 1))
            .withTransform(shape.transform.copy(bounds = NormalizedRect(-.1f, .3f, .3f, .2f)))
        val source = project(listOf(repeated), 3)
        val renderer = CompositionRenderer()
        for (page in 0..2) {
            val image = ImageIO.read(ByteArrayInputStream(renderer.render(source, page, emptyMap(), maxSide = 100)))
            assertEquals(if (page < 2) 0xFFFF0000.toInt() else 0xFF000000.toInt(), image.getRGB(5, 35))
            assertEquals(0xFF000000.toInt(), image.getRGB(95, 35))
        }
    }

    @Test fun `a continuous image still spans pages and a clip exports only on its assigned page`() {
        val buffered = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0..1) for (y in 0..1) buffered.setRGB(x, y, 0xFFFF0000.toInt())
        val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        val photo = CompositionElement.Photo("photo", ElementTransform(NormalizedRect(.8f, .3f, .4f, .2f)), 0,
            MediaReference("photo", "sum", "photo.jpg"), PhotoRecipeSnapshot(eu.studio742.imago.core.model.EditRecipe(
                assetId = "photo", originalChecksum = "sum", createdAt = "", updatedAt = "")))
        val renderer = CompositionRenderer()
        val panorama = project(listOf(photo), 2)
        for ((page, x) in listOf(0 to 90, 1 to 10)) {
            val image = ImageIO.read(ByteArrayInputStream(renderer.render(panorama, page, mapOf(photo.id to bytes), maxSide = 100)))
            assertEquals(0xFFFF0000.toInt(), image.getRGB(x, 35))
        }
        val clip = video.copy(transform = photo.transform)
        val clips = project(listOf(clip), 2)
        val page0 = ImageIO.read(ByteArrayInputStream(renderer.render(clips, 0, mapOf(clip.id to bytes), maxSide = 100)))
        val page1 = ImageIO.read(ByteArrayInputStream(renderer.render(clips, 1, mapOf(clip.id to bytes), maxSide = 100)))
        assertEquals(0xFFFF0000.toInt(), page0.getRGB(90, 35))
        assertEquals(0xFF000000.toInt(), page1.getRGB(10, 35))
    }

    @Test fun `viewport zoom preserves the content under the fingers and follows their pan`() {
        val viewport = ComposerViewport()
        val size = Size(600f, 800f)
        viewport.resize(size, 4f, 0, Size(100f, 100f), 80f, 120f)
        val pivot = Offset(270f, 360f)
        val point = (pivot - viewport.origin) / viewport.scale
        viewport.transform(1.5f, pivot, Offset(30f, -20f), 4f)
        val after = viewport.origin + point * viewport.scale
        assertEquals(pivot.x + 30f, after.x, .0001f)
        assertEquals(pivot.y - 20f, after.y, .0001f)
        val before = viewport.origin
        viewport.resize(size, 2f, 0, Size(100f, 100f), 180f, 300f)
        assertEquals(before, viewport.origin)
        assertEquals(6f, viewport.scale, .0001f)
    }

    @Composable private fun canvas(vm: ComposerEditorViewModel, insets: ComposerStageInsets = ComposerStageInsets(),
        drawing: Boolean = false, onDrawing: (List<StrokePoint>) -> Unit = {}) {
        val state = vm.state.collectAsState().value
        ComposerCanvas(state.project!!, state, drawing, StrokeKind.PEN, state.multiSelect, false, false,
            { "" }, { "" }, { "" }, { "" }, vm, onDrawing, {}, {}, insets, Modifier.size(600.dp, 800.dp))
    }

    @Test(timeout = 30_000) fun `two fingers navigate with a selected element without editing it`() = runComposeUiTest {
        val vm = model(project(listOf(shape)))
        vm.selectElement(shape.id)
        setContent { canvas(vm) }
        val before = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer_stage").performTouchInput {
            down(0, Offset(220f, 360f)); down(1, Offset(380f, 440f))
            moveBy(0, Offset(-25f, 10f)); moveBy(1, Offset(55f, 50f)); move()
            up(0); up(1)
        }
        assertEquals(shape, vm.state.value.project!!.elements.single())
        assertEquals(shape.id, vm.state.value.selectedElementId)
        val after = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width)
        assertNotEquals(before.topLeft, after.topLeft)
    }

    @Test(timeout = 30_000) fun `the visible part outside a page can be selected and dragged`() = runComposeUiTest {
        val outside = shape.copy(transform = shape.transform.copy(bounds = NormalizedRect(-.1f, .3f, .4f, .2f)))
        val vm = model(project(listOf(outside)))
        setContent { canvas(vm) }
        val page = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        val y = page.top + page.height * .4f
        onNodeWithTag("composer_stage").performTouchInput { click(Offset(5f, y)) }
        assertEquals(outside.id, vm.state.value.selectedElementId)
        onNodeWithTag("composer_stage").performTouchInput {
            down(Offset(page.left + page.width * .1f, y))
            moveBy(Offset(-40f, 30f)); up()
        }
        val moved = vm.state.value.project!!.elements.single().transform.bounds
        assertEquals(outside.transform.bounds.x - 40f / page.width, moved.x, .0001f)
        assertEquals(outside.transform.bounds.y + 30f / page.height, moved.y, .0001f)
    }

    @Test(timeout = 30_000) fun `toolbar changes keep page and artwork stationary and layers recover outside content`() = runComposeUiTest {
        val outside = shape.copy(transform = shape.transform.copy(bounds = NormalizedRect(-2f, -.5f, .3f, .2f)))
        val vm = model(project(listOf(outside)))
        val insets = mutableStateOf(ComposerStageInsets(top = 60.dp, bottom = 120.dp))
        setContent { canvas(vm, insets.value) }
        val before = onNodeWithTag("composer_page_0").getUnclippedBoundsInRoot()
        runOnIdle { insets.value = ComposerStageInsets(top = 100.dp, bottom = 240.dp) }
        assertEquals(before, onNodeWithTag("composer_page_0").getUnclippedBoundsInRoot())
        runOnIdle { vm.focusElement(outside.id) }
        onNodeWithTag("composer_element_${outside.id}").assertIsDisplayed().performTouchInput { click(center) }
        assertEquals(outside.id, vm.state.value.selectedElementId)
    }

    @Test(timeout = 30_000) fun `mouse wheel navigates with a selection and a pinch cancels a pending stroke`() = runComposeUiTest {
        val vm = model(project(listOf(shape)))
        vm.selectElement(shape.id)
        val drawing = mutableStateOf(false)
        var strokes = 0
        setContent { canvas(vm, drawing = drawing.value, onDrawing = { strokes++ }) }
        val before = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer_stage").performMouseInput { scroll(1f) }
        val after = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        assertTrue(after.top < before.top)
        assertEquals(shape, vm.state.value.project!!.elements.single())
        runOnIdle { drawing.value = true }
        onNodeWithTag("composer_stage").performTouchInput {
            down(0, Offset(220f, 360f)); down(1, Offset(380f, 440f))
            moveBy(0, Offset(-25f, 10f)); moveBy(1, Offset(55f, 50f)); up(0); up(1)
        }
        assertEquals(0, strokes)
        assertEquals(shape, vm.state.value.project!!.elements.single())
    }

    @Test(timeout = 30_000) fun `horizontal wheel navigation updates the viewed page without recentering it`() = runComposeUiTest {
        val vm = model(project(emptyList(), 2))
        setContent { canvas(vm) }
        val page0 = onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer_stage").performMouseInput { scroll(12f, ScrollWheel.Horizontal) }
        val page1 = onNodeWithTag("composer_page_1").fetchSemanticsNode().boundsInRoot
        assertEquals(1, vm.state.value.currentPage)
        assertTrue("The wheel's position was replaced by a centred page", page1.left < page0.left)
        assertEquals(page0.width, page1.width, .01f)
    }

    @Test(timeout = 30_000) fun `phone and tablet render the exterior workspace with the navigation chrome`() = runComposeUiTest {
        val outside = shape.copy(transform = shape.transform.copy(bounds = NormalizedRect(-.2f, .3f, .5f, .3f)))
        val vm = model(project(listOf(outside)).copy(background = CompositionBackground.Solid(0xFF26394C)))
        vm.selectElement(outside.id)
        val phone = mutableStateOf(true)
        setContent {
            val width = if (phone.value) 420.dp else 1024.dp
            Box(Modifier.size(width, 760.dp).testTag("movement-review")) {
                ImagoTheme {
                    CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width), width, 760.dp)) {
                        key(phone.value) {
                            ComposerEditorScreen(vm.state.collectAsState().value, remember { SnackbarHostState() }, {}, vm, null, {})
                        }
                    }
                }
            }
        }
        val output = File(System.getProperty("java.io.tmpdir"), "imago-composer-review").apply { mkdirs() }
        fun capture(name: String) {
            Image.makeFromBitmap(onNodeWithTag("movement-review").captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(output, name).writeBytes(data.bytes) }
            }
        }
        assertTrue(onNodeWithTag("composer_page_0").fetchSemanticsNode().boundsInRoot.width > 250f)
        capture("movement-phone.png")
        runOnIdle { phone.value = false }
        capture("movement-tablet.png")
    }
}
