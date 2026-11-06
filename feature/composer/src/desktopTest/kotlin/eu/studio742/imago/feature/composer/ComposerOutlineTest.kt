package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.model.EditRecipe
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
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ComposerOutlineTest {
    private val blue = 0xFF204080L
    private val red = 0xFFE04020L
    private val shape = CompositionElement.Shape("shape", ElementTransform(NormalizedRect(.25f, .25f, .5f, .5f)),
        1, ShapeKind.RECTANGLE, fillArgb = 0x00204080, strokeArgb = red, strokeWidth = 120f)
    private val photo = CompositionElement.Photo("photo", shape.transform, 0,
        MediaReference("photo", "sum", "photo.png"), PhotoRecipeSnapshot(EditRecipe(
            assetId = "photo", originalChecksum = "sum", createdAt = "", updatedAt = "")))
    private val photoBytes = ByteArrayOutputStream().also { output ->
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (x in 0..31) for (y in 0..31) image.setRGB(x, y, blue.toInt())
        ImageIO.write(image, "png", output)
    }.toByteArray()

    private fun project(vararg elements: CompositionElement, format: PageFormat = PageFormatPreset.SQUARE_1_1.format) = CompositionProject(
        id = "outline", name = "Outline", format = format, pages = listOf(CompositionPage("page", 0)),
        elements = elements.toList(), background = CompositionBackground.Solid(blue), createdAt = "", updatedAt = "")
    private fun exported(source: CompositionProject, maxSide: Int = 540) = ImageIO.read(ByteArrayInputStream(
        CompositionRenderer().render(source, 0, mapOf(photo.id to photoBytes), maxSide)))
    private fun stage(source: CompositionProject): Bitmap {
        val scene = ImageComposeScene(width = 600, height = 600, density = Density(1f)) {
            ImagoTheme {
                ComposerCanvas(source, ComposerEditorUiState(project = source, isLoading = false),
                    false, StrokeKind.PEN, false, false, false, { "" }, { "" }, { "" }, { "" },
                    FakeComposerRepos.viewModel(), {}, {}, {}, ComposerStageInsets(), Modifier.fillMaxSize())
            }
        }
        return try { Bitmap().also { it.allocN32Pixels(600, 600); scene.render().readPixels(it) } }
        finally { scene.close() }
    }
    private fun assertColor(expected: Long, actual: Int, tolerance: Int = 2) {
        for (shift in listOf(0, 8, 16)) assertTrue("Channel $shift: expected ${expected.toString(16)}, actual ${actual.toUInt().toString(16)}",
            kotlin.math.abs(((expected ushr shift) and 255).toInt() - (actual ushr shift and 255)) <= tolerance)
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `an empty shape over a photo keeps its interior clear and multiplies outline and global opacity`() {
        val backdrop = photo.copy(transform = ElementTransform(NormalizedRect(0f, 0f, 1f, 1f)))
        val faded = shape.copy(transform = shape.transform.copy(opacity = .4f))
        val image = exported(project(backdrop, faded))
        assertColor(blue, image.getRGB(270, 270))
        val blend = (0xFFL shl 24) or ((224L * .4 + 32L * .6).toLong() shl 16) or
            ((64L * .4 + 64L * .6).toLong() shl 8) or (32L * .4 + 128L * .6).toLong()
        assertColor(blend, image.getRGB(105, 270))
    }

    @Test fun `a thick zero radius shape has square outside corners on the stage`() {
        stage(project(shape)).use { image -> assertColor(red, image.getColor(100, 100)) }
    }

    @Test fun `a thick zero radius photo frame has square outside corners in the exported image`() {
        val framed = photo.copy(frame = FrameStyle(borderWidth = 120f, borderArgb = red))
        val image = exported(project(framed))
        assertColor(red, image.getRGB(77, 77))
        assertColor(blue, image.getRGB(270, 270))
    }

    @Test fun `outline width prototypes render on portrait square and landscape pages`() {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-outline-review").apply { mkdirs() }
        for ((name, format) in listOf("square" to PageFormatPreset.SQUARE_1_1.format,
            "portrait" to PageFormatPreset.STORY_9_16.format, "landscape" to PageFormatPreset.LANDSCAPE_16_9.format)) {
            val sheet = BufferedImage(1200, 640, BufferedImage.TYPE_INT_RGB)
            val graphics = sheet.createGraphics()
            graphics.color = java.awt.Color(0x202020); graphics.fillRect(0, 0, sheet.width, sheet.height)
            graphics.font = Font("SansSerif", Font.PLAIN, 17)
            for ((column, width) in listOf(40f, 80f, 120f, 160f).withIndex()) {
                for (row in 0..1) {
                    val content = if (row == 0) shape.copy(strokeWidth = width)
                        else photo.copy(frame = FrameStyle(borderWidth = width, borderArgb = red))
                    val rendered = exported(project(content, format = format), 270)
                    graphics.drawImage(rendered, column * 300 + (300 - rendered.width) / 2, row * 320 + 35, null)
                    graphics.color = java.awt.Color.WHITE
                    graphics.drawString("${if (row == 0) "Shape" else "Photo"}: ${width.toInt()}", column * 300 + 10, row * 320 + 24)
                }
            }
            graphics.dispose()
            val file = File(folder, "outline-widths-$name.png")
            ImageIO.write(sheet, "png", file)
            assertTrue(file.length() > 0)
        }
    }

    @Test fun `photo frame opacity multiplies its color alpha and a shadow is painted behind the photo`() {
        val faded = photo.copy(transform = photo.transform.copy(opacity = .5f),
            frame = FrameStyle(borderWidth = 120f, borderArgb = red.withColorAlpha(.5f)))
        val image = exported(project(faded))
        val expectedAlpha = .5f * (128f / 255f)
        val blend = (0xFFL shl 24) or ((224 * expectedAlpha + 32 * (1 - expectedAlpha)).toLong() shl 16) or
            (64L shl 8) or (32 * expectedAlpha + 128 * (1 - expectedAlpha)).toLong()
        assertColor(blend, image.getRGB(105, 270))
        val shadow = exported(project(photo.copy(frame = FrameStyle(shadowRadius = 32f))))
        val plain = exported(project(photo))
        assertNotEquals(plain.getRGB(130, 270), shadow.getRGB(130, 270))
        assertColor(blue, shadow.getRGB(270, 270))
    }

    @Test fun `a rounded hollow rectangle and its thumbnail keep a transparent interior at different scales`() {
        val rounded = shape.copy(cornerRadius = .25f)
        for (side in listOf(270, 540)) {
            val image = exported(project(rounded), side)
            assertColor(blue, image.getRGB(side / 2, side / 2))
        }
        val source = project(rounded)
        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            ImagoTheme { CompositionPagePreview(source, source.pages.single(), { "" }, { "" }, Modifier.fillMaxSize()) }
        }
        try {
            Bitmap().use { bitmap ->
                bitmap.allocN32Pixels(300, 300); scene.render().readPixels(bitmap)
                assertColor(blue, bitmap.getColor(150, 150))
                assertColor(red, bitmap.getColor(60, 150))
            }
        } finally { scene.close() }
    }
}
