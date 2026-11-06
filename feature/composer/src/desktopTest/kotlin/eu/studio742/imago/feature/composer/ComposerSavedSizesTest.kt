package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.LocalCompositionFormatRepository
import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ComposerSavedSizesTest {
    private class Preferences : SecurePreferences {
        val values = mutableMapOf<String, String>()
        var refuse = false
        @Synchronized override fun getString(key: String) = values[key]
        @Synchronized override fun write(changes: Map<String, String?>): Boolean {
            if (refuse) return false
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
    }
    private val preferences = Preferences()
    private val repository = LocalCompositionFormatRepository(preferences)
    private var custom = ""; private var rename = ""; private var delete = ""; private var sameFile = ""
    private var social = ""; private var square = ""; private var title = ""
    @Composable private fun dialog(vm: ComposerFormatsViewModel, onCreate: (PageFormat, BuiltInLayout) -> Unit,
        file: PageFormat? = null, width: Int = 420, height: Int = 800, palette: ImagoUiPalette = ImagoUiPalette()) {
        custom = stringResource(Res.string.composer_custom)
        rename = stringResource(Res.string.composer_rename)
        delete = stringResource(Res.string.composer_delete)
        sameFile = stringResource(Res.string.composer_same_as_file)
        social = stringResource(PageFormatCategory.SOCIAL.labelRes())
        square = stringResource(PageFormatPreset.SQUARE_1_1.labelRes())
        title = stringResource(Res.string.composer_new_composition)
        ImagoTheme(palette) {
            CompositionLocalProvider(LocalImagoWindow provides ImagoWindow(ImagoBreakpoints.classify(width.dp), width.dp, height.dp)) {
                Box(Modifier.size(width.dp, height.dp)) { NewCompositionDialog({}, onCreate, file, vm) }
            }
        }
    }
    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val folder = File(System.getProperty("java.io.tmpdir"), "imago-composer-sizes-review").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, name).writeBytes(data.bytes) }
        }
    }
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test(timeout = 45_000) fun `a named size can be saved reopened reused renamed and deleted without changing the chosen dimensions`() = runComposeUiTest {
        var vm by mutableStateOf(ComposerFormatsViewModel(repository))
        var showing by mutableStateOf(true)
        var created: PageFormat? = null
        setContent { if (showing) key(vm) { dialog(vm, { format, _ -> created = format }) } }
        waitUntil { !vm.state.value.isLoading }
        onNodeWithText(custom).performScrollTo().performClick()
        onNodeWithTag("composer-custom-width").performTextReplacement("1234")
        onNodeWithTag("composer-custom-height").performTextReplacement("2345")
        onNodeWithTag("composer-save-size").performScrollTo().performClick()
        onNodeWithTag("composer-confirm-size").assertIsNotEnabled()
        onNodeWithTag("composer-size-name").performTextReplacement("Poster")
        onNodeWithTag("composer-confirm-size").performClick()
        waitUntil { vm.state.value.formats.size == 1 && !vm.state.value.isWorking }
        onNodeWithTag("composer-size-name").assertDoesNotExist()
        val saved = vm.state.value.formats.single()
        runOnIdle { showing = false }
        waitForIdle()
        runOnIdle { vm = ComposerFormatsViewModel(LocalCompositionFormatRepository(preferences)); showing = true }
        waitUntil { !vm.state.value.isLoading }
        onNodeWithText(custom).performScrollTo().performClick()
        onNodeWithTag("composer-saved-size-${saved.id}").performScrollTo().performClick()
        onNodeWithTag("composer-custom-width").assertTextContains("1234")
        onNodeWithTag("composer-custom-height").assertTextContains("2345")
        onNodeWithTag("composer-save-size").assertDoesNotExist()
        onNodeWithTag("composer-custom-width").performTextReplacement("1")
        onNodeWithTag("composer-layout-BLANK").assertIsNotEnabled()
        onNodeWithTag("composer-saved-size-${saved.id}").performScrollTo().performClick()
        onNodeWithTag("composer-custom-width").assertTextContains("1234")
        onNodeWithTag("composer-layout-BLANK").assertIsEnabled()
        onNodeWithTag("composer-custom-width").performTextReplacement("1600")
        onNodeWithTag("composer-custom-height").performTextReplacement("900")
        onNodeWithTag("composer-size-options-${saved.id}").performScrollTo().performClick()
        onNodeWithText(rename).performClick()
        onNodeWithTag("composer-size-name").performTextReplacement("Exhibition")
        onNodeWithTag("composer-confirm-size").performClick()
        waitUntil { vm.state.value.formats.single().name == "Exhibition" && !vm.state.value.isWorking }
        onNodeWithTag("composer-custom-width").assertTextContains("1600")
        onNodeWithTag("composer-custom-height").assertTextContains("900")
        onNodeWithTag("composer-saved-size-${saved.id}").performScrollTo().performClick()
        onNodeWithText(title).performScrollTo()
        capture(onNodeWithTag("new-composition-dialog"), "saved-size-phone.png")
        onNodeWithTag("composer-size-options-${saved.id}").performScrollTo().performClick()
        onNodeWithText(delete).performClick()
        onNodeWithTag("composer-confirm-delete-size").performClick()
        waitUntil { vm.state.value.formats.isEmpty() && !vm.state.value.isWorking }
        onNodeWithTag("composer-custom-width").assertTextContains("1234")
        onNodeWithTag("composer-custom-height").assertTextContains("2345")
        onNodeWithTag("composer-layout-BLANK").performScrollTo().performClick()
        assertEquals(saved.format, created)
    }

    @Test(timeout = 30_000) fun `invalid manual dimensions block save and creation while presets and same file keep working`() = runComposeUiTest {
        val vm = ComposerFormatsViewModel(repository)
        var created: PageFormat? = null
        val file = PageFormat.custom(1600, 900)
        setContent { dialog(vm, { format, _ -> created = format }, file) }
        waitUntil { !vm.state.value.isLoading }
        onNodeWithTag("composer-layout-BLANK").performScrollTo().performClick()
        assertEquals(file, created)
        onNodeWithText(custom).performScrollTo().performClick()
        for (invalid in listOf("239", "4097", "10000", "")) {
            onNodeWithTag("composer-custom-width").performTextReplacement(invalid)
            onNodeWithTag("composer-save-size").assertIsNotEnabled()
            onNodeWithTag("composer-layout-BLANK").assertIsNotEnabled()
        }
        onNodeWithTag("composer-custom-width").performTextReplacement("4096")
        onNodeWithTag("composer-custom-height").performTextReplacement("4096")
        onNodeWithTag("composer-save-size").assertIsEnabled()
        onNodeWithText(sameFile).performScrollTo().performClick()
        onNodeWithTag("composer-layout-BLANK").performScrollTo().performClick()
        assertEquals(file, created)
        onNodeWithText(social).performScrollTo().performClick()
        onNodeWithText(square).performScrollTo().performClick()
        onNodeWithTag("composer-layout-BLANK").performScrollTo().performClick()
        assertEquals(PageFormatPreset.SQUARE_1_1.format, created)
    }

    @Test(timeout = 30_000) fun `a failed storage write keeps the name editor and reports the failure before a successful retry`() = runComposeUiTest {
        val vm = ComposerFormatsViewModel(repository)
        setContent { dialog(vm, { _, _ -> }) }
        waitUntil { !vm.state.value.isLoading }
        onNodeWithText(custom).performScrollTo().performClick()
        onNodeWithTag("composer-save-size").performScrollTo().performClick()
        onNodeWithTag("composer-size-name").performTextReplacement("Poster")
        preferences.refuse = true
        onNodeWithTag("composer-confirm-size").performClick()
        waitUntil { vm.state.value.error != null && !vm.state.value.isWorking }
        onNodeWithTag("composer-size-name").assertExists()
        assertTrue(vm.state.value.formats.isEmpty())
        preferences.refuse = false
        onNodeWithTag("composer-confirm-size").performClick()
        waitUntil { vm.state.value.formats.size == 1 && !vm.state.value.isWorking }
        assertNull(vm.state.value.error)
        onNodeWithTag("composer-size-name").assertDoesNotExist()
    }

    @Test(timeout = 30_000) fun `saved sizes remain readable in tablet landscape and alternate themes`() = runComposeUiTest {
        runBlocking {
            repository.save("Panorama", 3000, 1000)
            repository.save("Portrait print", 1200, 1500)
            repository.save("Landscape card", 1600, 900)
        }
        val vm = ComposerFormatsViewModel(repository)
        var width by mutableStateOf(1024)
        var height by mutableStateOf(760)
        var alternate by mutableStateOf(false)
        val palette = ImagoUiPalette(surface = Color(0xFF203840), text = Color(0xFFE0FFFF),
            selectedChoiceSurface = Color.Cyan, onSelectedChoiceSurface = Color(0xFF102028),
            choiceSurface = Color(0xFF304C54), elevated = Color(0xFF304C54), border = Color.Cyan)
        setContent { dialog(vm, { _, _ -> }, width = width, height = height, palette = if (alternate) palette else ImagoUiPalette()) }
        waitUntil { !vm.state.value.isLoading }
        onNodeWithText(custom).performScrollTo().performClick()
        onNodeWithText(title).performScrollTo()
        capture(onNodeWithTag("new-composition-dialog"), "saved-sizes-tablet.png")
        runOnIdle { alternate = true }
        capture(onNodeWithTag("new-composition-dialog"), "saved-sizes-alternate.png")
        val bitmap = onNodeWithTag("new-composition-dialog").captureToImage().asSkiaBitmap()
        assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getColor(x, y) == palette.selectedChoiceSurface.toArgb() } })
        runOnIdle { width = 760; height = 360 }
        onNodeWithTag("composer-layout-BLANK").performScrollTo().assertIsDisplayed()
        capture(onNodeWithTag("new-composition-dialog"), "saved-sizes-landscape.png")
        assertEquals(3, vm.state.value.formats.size)
    }
}
