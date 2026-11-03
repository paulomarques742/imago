package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.ImagoUiPalette
import eu.studio742.imago.feature.composer.resources.Res
import eu.studio742.imago.feature.composer.resources.composer_add_elements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ComposerPaletteTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the selected tool surface follows a supplied palette`() {
        val palette = ImagoUiPalette(selectedSurface = Color(0xFFB8E7CF), text = Color(0xFF18231D))
        val scene = ImageComposeScene(width = 200, height = 100, density = Density(1f)) {
            ImagoTheme(palette) {
                Box(Modifier.fillMaxSize()) {
                    ToolButton(stringResource(Res.string.composer_add_elements), Icons.Outlined.Add, active = true) {}
                }
            }
        }
        try {
            val bitmap = Bitmap().also { it.allocN32Pixels(200, 100); scene.render().readPixels(it) }
            assertEquals(0xFFB8E7CF.toInt(), bitmap.getColor(10, 10))
        } finally { scene.close() }
    }

    @Test fun `phone and tablet navigation render with an alternative palette without editing content`() {
        val project = CompositionProject(id = "palette", name = "Composition", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), background = eu.studio742.imago.core.composition.CompositionBackground.Solid(0xFF375848), createdAt = "", updatedAt = "")
        val vm = FakeComposerRepos.viewModel()
        val palette = ImagoUiPalette(background = Color(0xFFF4F2ED), workspace = Color(0xFFE1DED8),
            surface = Color(0xFFFAF8F3), elevated = Color(0xFFEAE6DF), navigation = Color(0xFFEAE6DF),
            glass = Color(0xF2FAF8F3), text = Color(0xFF242424), secondaryText = Color(0xFF454545),
            tertiaryText = Color(0xFF656565), accent = Color(0xFF76601A))
        val output = File(System.getProperty("java.io.tmpdir"), "imago-composer-review").apply { mkdirs() }
        for ((name, size) in listOf("phone" to (420 to 900), "tablet" to (1200 to 800))) {
            val scene = ImageComposeScene(width = size.first, height = size.second, density = Density(1f)) {
                ImagoTheme(palette) {
                    ComposerEditorScreen(ComposerEditorUiState(project = project, isLoading = false),
                        remember { SnackbarHostState() }, {}, vm, null, {})
                }
            }
            try {
                val rendered = scene.render()
                rendered.encodeToData(EncodedImageFormat.PNG)!!.bytes.let { File(output, "$name.png").writeBytes(it) }
                val bitmap = Bitmap().also { it.allocN32Pixels(size.first, size.second); rendered.readPixels(it) }
                val contentPixels = (0 until size.second).sumOf { y -> (0 until size.first).count { x -> bitmap.getColor(x, y) == 0xFF375848.toInt() } }
                org.junit.Assert.assertTrue("The theme must preserve the page background", contentPixels > 10_000)
            } finally { scene.close() }
        }
    }
}
