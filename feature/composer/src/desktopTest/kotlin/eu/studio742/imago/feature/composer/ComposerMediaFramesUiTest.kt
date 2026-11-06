package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ComposerMediaFramesUiTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val sample by lazy {
        File(System.getProperty("java.io.tmpdir"), "imago-composer-frame-sample.png").also { target ->
            val image = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 200) for (x in 0 until 320) image.setRGB(x, y, if ((x / 40 + y / 40) % 2 == 0) 0xD97942 else 0xBC663A)
            ImageIO.write(image, "png", target)
        }
    }
    private fun model(): ComposerEditorViewModel {
        val photo = CompositionElement.Photo("photo", ElementTransform(NormalizedRect(.15f, .15f, .7f, .7f)), 0,
            MediaReference("sample", "hash", "sample.png", width = 320, height = 200),
            PhotoRecipeSnapshot(EditRecipe(assetId = "sample", originalChecksum = "hash", createdAt = "", updatedAt = "")))
        val project = CompositionProject(id = "frames-ui", name = "Frame sample", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = listOf(photo), createdAt = "", updatedAt = "")
        val f = FakeComposerRepos
        val projects = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(project))
            override suspend fun get(id: String) = project
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val media = object : CompositionMediaRepository by f.media {
            override fun thumbnailUrl(assetId: String) = sample.toPath().toUri().toString()
            override fun previewUrl(assetId: String) = sample.toPath().toUri().toString()
        }
        return ComposerEditorViewModel(projects, f.templates, media, f.recipes, f.exports, f.brandKits).also { it.open(project.id); it.selectElement(photo.id) }
    }
    private var library = ""; private var cancel = ""
    @Composable private fun screen(vm: ComposerEditorViewModel, width: Int = 420, height: Int = 800, palette: ImagoUiPalette = ImagoUiPalette(), dialog: Boolean = false) {
        library = stringResource(Res.string.composer_frame_library); cancel = stringResource(Res.string.composer_cancel)
        ImagoTheme(palette) {
            CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width.dp), width.dp, height.dp)) {
                val state by vm.state.collectAsState()
                Box(Modifier.size(width.dp, height.dp).testTag("frames-ui-review")) {
                    if (dialog) ComposerFramesDialog(state, vm, {})
                    else ComposerEditorScreen(state, remember { SnackbarHostState() }, {}, vm, null, {})
                }
            }
        }
    }
    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-frames-review").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, name).writeBytes(data.bytes) }
        }
    }
    @Before fun setUp() = Dispatchers.setMain(mainDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test(timeout = 30_000) fun `the media context opens a non destructive catalogue and cancellation retains the original`() = runComposeUiTest {
        val vm = model(); val original = vm.state.value.project
        setContent { screen(vm) }
        onNodeWithText(library).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) {
            mainDispatcher.scheduler.runCurrent()
            val pixels = onNodeWithTag("frames-preview").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 2]
            color.red > .5f && color.green < .7f && color.blue < .6f
        }
        onNodeWithTag("frame-cutout-HEART").performScrollTo().performClick()
        onNodeWithTag("frame-decoration-POLAROID").performScrollTo().performClick()
        assertEquals(original, vm.state.value.project)
        capture(onNodeWithTag("composer-frames-dialog"), "frames-polaroid-preview-phone.png")
        onNodeWithText(cancel).performClick()
        assertEquals(original, vm.state.value.project); assertFalse(vm.state.value.canUndo)
    }

    @Test(timeout = 30_000) fun `torn edges and paper parameters apply together in one undoable edit`() = runComposeUiTest {
        val vm = model()
        setContent { screen(vm, dialog = true) }
        onNodeWithTag("frame-cutout-TORN_PAPER").performScrollTo().performClick()
        onNodeWithTag("frame-decoration-TORN_PAPER").performScrollTo().performClick()
        onNodeWithTag("frame-new-edge").performScrollTo().performClick()
        onNodeWithTag("frame-roughness").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
        onNodeWithTag("frames-confirm").performClick()
        val frame = vm.state.value.project!!.elements.single().mediaFrame()!!
        assertEquals(MediaCutout.TORN_PAPER, frame.cutout); assertEquals(MediaFrameKind.TORN_PAPER, frame.decoration.kind)
        assertEquals(.8f, frame.decoration.roughness, .001f); assertNotEquals(742, frame.decoration.seed)
        vm.undo(); assertEquals(FrameStyle(), vm.state.value.project!!.elements.single().mediaFrame())
    }

    @Test(timeout = 30_000) fun `small windows keep the apply and cancel controls visible below scrolling parameters`() = runComposeUiTest {
        val vm = model()
        setContent { screen(vm, width = 356, height = 560, dialog = true) }
        onNodeWithTag("frame-decoration-POLAROID").performScrollTo().performClick()
        onNodeWithTag("frame-bottom").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        onNodeWithText(cancel).assertIsDisplayed(); onNodeWithTag("frames-confirm").assertIsDisplayed()
        capture(onNodeWithTag("composer-frames-dialog"), "frames-controls-small-window.png")
        onNodeWithTag("frames-confirm").performClick()
        assertEquals(3f, vm.state.value.project!!.elements.single().mediaFrame()!!.decoration.bottomWeight, .001f)
    }

    @Test(timeout = 30_000) fun `an alternate palette changes the catalogue without changing exported frame content`() = runComposeUiTest {
        val vm = model(); vm.setMediaFrameDesign(MediaCutout.CIRCLE, MediaFrameDecoration(MediaFrameKind.DOUBLE_LINE, width = 45f))
        val project = vm.state.value.project!!; val source = mapOf("sample" to sample.readBytes())
        val before = CompositionRenderer().render(project, 0, source, 240)
        val palette = ImagoUiPalette(surface = Color(0xFFF1EDE5), elevated = Color(0xFFE3DBCD), selectedSurface = Color(0xFFD2DBE1),
            text = Color(0xFF202A32), secondaryText = Color(0xFF53616A), accent = Color(0xFF23627E))
        setContent { screen(vm, width = 1000, palette = palette, dialog = true) }
        waitUntil(timeoutMillis = 10_000) {
            mainDispatcher.scheduler.runCurrent()
            val pixels = onNodeWithTag("frames-preview").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 2]
            color.red > .5f && color.green < .7f && color.blue < .6f
        }
        capture(onNodeWithTag("composer-frames-dialog"), "frames-preview-tablet-alternate.png")
        assertArrayEquals(before, CompositionRenderer().render(vm.state.value.project!!, 0, source, 240))
    }

    @Test(timeout = 30_000) fun `element opacity retains the paper outside the photo manipulation box`() = runComposeUiTest {
        val vm = model(); vm.setMediaFrameDesign(MediaCutout.RECTANGLE, MediaFrameDecoration(MediaFrameKind.POLAROID, width = 90f))
        vm.setSelectedOpacity(.5f)
        setContent { screen(vm, dialog = true) }
        waitUntil(timeoutMillis = 10_000) {
            mainDispatcher.scheduler.runCurrent()
            val pixels = onNodeWithTag("frames-preview").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.red > center.blue + .15f
        }
        val pixels = onNodeWithTag("frames-preview").captureToImage().toPixelMap()
        val pageWidth = minOf(pixels.width, pixels.height)
        val paper = pixels[(pixels.width - pageWidth) / 2 + pageWidth / 9, pixels.height / 2]
        assertTrue(paper.red > .4f && paper.red < .65f)
        assertTrue(kotlin.math.abs(paper.red - paper.blue) < .03f)
        capture(onNodeWithTag("composer-frames-dialog"), "frames-opacity-phone.png")
    }
}
