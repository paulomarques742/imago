package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.designsystem.ImagoUiPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.Bitmap
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ComposerPageAppearanceTest {
    private val background = Color(0xFF202020)
    private val page = Color(0xFFF0F0F0)
    private val pageBounds = Rect(100f, 100f, 500f, 500f)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun render(palette: ImagoUiPalette = ImagoUiPalette(), content: @Composable () -> Unit): Bitmap {
        val scene = ImageComposeScene(width = 600, height = 600, density = Density(1f)) {
            ImagoTheme(palette) { content() }
        }
        return try { Bitmap().also { it.allocN32Pixels(600, 600); scene.render().readPixels(it) } }
        finally { scene.close() }
    }

    private fun artwork(bounds: Rect, rotation: Float = 0f, opacity: Float = 1f, hollow: Boolean = false,
        enabled: Boolean = true, palette: ImagoUiPalette = ImagoUiPalette(), clip: Rect = pageBounds): Bitmap = render(palette) {
        val alpha = ImagoUi.colors.outsidePageContentAlpha
        Box(Modifier.fillMaxSize().background(background)) {
            Box(Modifier.offset(100.dp, 100.dp).size(400.dp).background(page))
            Canvas(Modifier.offset(bounds.left.dp, bounds.top.dp).size(bounds.width.dp, bounds.height.dp)
                .then(if (enabled) Modifier.composerPageAppearance(
                    clip.translate(-bounds.left, -bounds.top),
                    Rect(-bounds.left, -bounds.top, 600f - bounds.left, 600f - bounds.top), alpha) else Modifier)
                .graphicsLayer { rotationZ = rotation; this.alpha = opacity }) {
                if (hollow) drawRect(Color.Red, style = Stroke(40f)) else drawRect(Color.Red)
            }
        }
    }

    private fun assertGrey(argb: Int) {
        val red = argb ushr 16 and 255
        val green = argb ushr 8 and 255
        val blue = argb and 255
        assertTrue("Outside artwork must be desaturated", abs(red - green) <= 1 && abs(green - blue) <= 1)
        assertNotEquals(background.toArgb(), argb)
    }

    @Test fun `only the exterior part is grey on each of the four page edges`() {
        for ((bounds, outside, inside) in listOf(
            Triple(Rect(60f, 180f, 240f, 300f), Offset(80f, 240f), Offset(150f, 240f)),
            Triple(Rect(420f, 180f, 600f, 300f), Offset(550f, 240f), Offset(450f, 240f)),
            Triple(Rect(180f, 60f, 300f, 240f), Offset(240f, 80f), Offset(240f, 150f)),
            Triple(Rect(180f, 420f, 300f, 600f), Offset(240f, 550f), Offset(240f, 450f)),
        )) {
            artwork(bounds).use { image ->
                assertGrey(image.getColor(outside.x.toInt(), outside.y.toInt()))
                assertEquals(0xFFFF0000.toInt(), image.getColor(inside.x.toInt(), inside.y.toInt()))
            }
        }
    }

    @Test fun `the boundary stays on the page when artwork rotates and corners are not cropped`() {
        artwork(Rect(50f, 200f, 200f, 400f), rotation = 45f).use { image ->
            assertGrey(image.getColor(95, 300))
            assertEquals(0xFFFF0000.toInt(), image.getColor(105, 300))
            assertEquals(0xFFFF0000.toInt(), image.getColor(220, 300))
        }
    }

    @Test fun `hollow artwork preserves transparent interiors and applies the treatment to its outside stroke`() {
        artwork(Rect(60f, 200f, 200f, 400f), hollow = true).use { image ->
            assertGrey(image.getColor(70, 250))
            assertEquals(0xFF202020.toInt(), image.getColor(85, 300))
            assertEquals(0xFFF0F0F0.toInt(), image.getColor(150, 300))
            assertEquals(0xFFFF0000.toInt(), image.getColor(160, 205))
        }
    }

    @Test fun `element opacity is preserved inside and multiplies the outside attenuation`() {
        val bounds = Rect(60f, 200f, 240f, 400f)
        artwork(bounds, opacity = .5f, enabled = false).use { original ->
            artwork(bounds, opacity = .5f).use { shown ->
                assertEquals(original.getColor(150, 300), shown.getColor(150, 300))
                assertGrey(shown.getColor(80, 300))
                artwork(bounds).use { opaque ->
                    assertTrue((shown.getColor(80, 300) and 255) < (opaque.getColor(80, 300) and 255))
                }
            }
        }
    }

    @Test fun `the outside opacity comes from the supplied theme even for completely exterior artwork`() {
        val bounds = Rect(10f, 200f, 70f, 300f)
        artwork(bounds, palette = ImagoUiPalette(outsidePageContentAlpha = .2f)).use { faint ->
            artwork(bounds, palette = ImagoUiPalette(outsidePageContentAlpha = .8f)).use { strong ->
                assertGrey(faint.getColor(40, 250))
                assertGrey(strong.getColor(40, 250))
                assertTrue((strong.getColor(40, 250) and 255) > (faint.getColor(40, 250) and 255))
            }
        }
    }

    @Test fun `continuous artwork retains colour across a separator while a page specific copy is grey outside its page`() {
        val bounds = Rect(250f, 200f, 450f, 400f)
        artwork(bounds, clip = pageBounds).use { continuous ->
            assertEquals(0xFFFF0000.toInt(), continuous.getColor(290, 300))
            assertEquals(0xFFFF0000.toInt(), continuous.getColor(310, 300))
        }
        artwork(bounds, clip = Rect(100f, 100f, 300f, 500f)).use { pageCopy ->
            assertEquals(0xFFFF0000.toInt(), pageCopy.getColor(290, 300))
            assertGrey(pageCopy.getColor(310, 300))
        }
    }

    @Test fun `the real stage applies page masks to repeats and leaves saved artwork and export unchanged`() {
        val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.8f, .3f, .4f, .2f)), 0,
            ShapeKind.RECTANGLE, fillArgb = 0xFFFF0000, strokeWidth = 0f)
        val project = CompositionProject(id = "appearance", name = "Appearance", format = PageFormat.custom(240, 240),
            pages = listOf(CompositionPage("first", 0), CompositionPage("second", 1)), elements = listOf(shape),
            background = CompositionBackground.Solid(0xFFF0F0F0), createdAt = "", updatedAt = "")
        val renderer = CompositionRenderer()
        val before = renderer.render(project, 1, emptyMap())
        fun stage(shown: CompositionProject) = render {
            ComposerCanvas(shown, ComposerEditorUiState(project = shown, isLoading = false),
                false, StrokeKind.PEN, false, false, false, { "" }, { "" }, { "" }, { "" },
                FakeComposerRepos.viewModel(), {}, {}, {}, ComposerStageInsets(), Modifier.fillMaxSize())
        }
        // At this size page one starts at x=582; the sample is eight pixels into it.
        stage(project).use { continuous -> assertEquals(0xFFFF0000.toInt(), continuous.getColor(590, 240)) }
        val repeated = project.copy(elements = listOf(shape.withRepeatOnPages(setOf(0))))
        stage(repeated).use { copy ->
            val color = copy.getColor(590, 240)
            assertTrue(abs((color ushr 16 and 255) - (color ushr 8 and 255)) <= 1)
            assertNotEquals(0xFFFF0000.toInt(), color)
        }
        assertEquals(0xFFFF0000, shape.fillArgb)
        assertArrayEquals(before, renderer.render(project, 1, emptyMap()))
    }
}
