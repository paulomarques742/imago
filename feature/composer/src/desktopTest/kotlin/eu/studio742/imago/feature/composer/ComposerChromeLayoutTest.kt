package eu.studio742.imago.feature.composer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.*
import org.junit.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ComposerChromeLayoutTest {
    private data class Viewport(val width: Int, val fontScale: Float, val showLayers: Boolean)

    @Composable private fun Navigation(showLayers: Boolean, longLabel: Boolean = false, onAdd: () -> Unit = {}) {
        ComposerNavigationBar(onAdd = onAdd,
            layers = { if (showLayers) ToolButton(stringResource(Res.string.composer_layers), Icons.Outlined.Layers) {} },
            background = { ToolButton(stringResource(Res.string.composer_background), Icons.Outlined.Wallpaper) {} },
            pages = { ToolButton(stringResource(Res.string.composer_pages), Icons.Outlined.GridView) {} },
            more = { ToolButton(stringResource(if (longLabel) Res.string.composer_save_as_template else Res.string.composer_more), Icons.Outlined.MoreHoriz) {} })
    }

    @Test fun `Add stays centred and protrudes when labels fonts and optional actions change`() = runComposeUiTest {
        var viewport by mutableStateOf(Viewport(420, 1f, true))
        setContent {
            ImagoTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, viewport.fontScale)) {
                    Box(Modifier.size(viewport.width.dp, 160.dp)) { Navigation(viewport.showLayers, longLabel = true) }
                }
            }
        }
        for (width in listOf(320, 420, 800)) for (scale in listOf(1f, 1.6f)) for (layers in listOf(true, false)) {
            runOnIdle { viewport = Viewport(width, scale, layers) }
            waitForIdle()
            val bar = onNodeWithTag("composer-navigation").fetchSemanticsNode().boundsInRoot
            val surface = onNodeWithTag("composer-navigation-surface").fetchSemanticsNode().boundsInRoot
            val add = onNodeWithTag("composer-add").fetchSemanticsNode().boundsInRoot
            assertEquals("Add must use the geometric centre", bar.center.x, add.center.x, .5f)
            assertTrue("Add must protrude above the bar", add.top < surface.top)
            assertTrue("Add must be taller than the main bar", add.height > surface.height)
        }
    }

    @Test fun `context cards wrap their item count remain centred and stop before the window edges`() = runComposeUiTest {
        var count by mutableStateOf(1)
        setContent {
            ImagoTheme {
                Box(Modifier.width(420.dp).testTag("viewport")) {
                    ComposerContextCard {
                        Row(Modifier.horizontalScroll(rememberScrollState()).testTag("context-tools")) {
                            repeat(count) { ToolButton(stringResource(Res.string.composer_text), Icons.Outlined.Image) {} }
                        }
                    }
                }
            }
        }
        val widths = mutableListOf<Float>()
        for (items in listOf(1, 2, 4, 12)) {
            runOnIdle { count = items }
            waitForIdle()
            val viewport = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val card = onNodeWithTag("composer-context-card").fetchSemanticsNode().boundsInRoot
            val tools = onNodeWithTag("context-tools").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.center.x, card.center.x, .5f)
            assertEquals(card.center.x, tools.center.x, .5f)
            assertTrue("The card must be narrower than the main bar", card.width < viewport.width)
            assertTrue(card.left > viewport.left && card.right < viewport.right)
            widths += card.width
        }
        assertTrue("More items should grow a short card", widths[0] < widths[1] && widths[1] < widths[2])
        assertTrue(widths[2] <= widths[3])
    }

    @Test fun `opening and closing a card never moves the main navigation or Add`() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            ImagoTheme {
                Box(Modifier.size(420.dp, 360.dp).background(ImagoUi.colors.workspace).testTag("chrome-preview")) {
                    ComposerNavigationChrome(open, immersive = false, contentAlpha = 1f, onHeightChanged = {},
                        context = {
                            Row {
                                ToolButton(stringResource(Res.string.composer_media), Icons.Outlined.Image) {}
                                ToolButton(stringResource(Res.string.composer_text), Icons.Outlined.TextFields) {}
                                ToolButton(stringResource(Res.string.composer_shape), Icons.Outlined.Rectangle) {}
                                ToolButton(stringResource(Res.string.composer_drawing), Icons.Outlined.Brush) {}
                            }
                        },
                        navigation = { Navigation(true) { open = !open } },
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        val initial = onNodeWithTag("composer-add").fetchSemanticsNode().boundsInRoot
        mainClock.autoAdvance = false
        onNodeWithTag("composer-add").performClick()
        for (time in listOf(64L, 80L, 160L)) {
            mainClock.advanceTimeBy(time)
            waitForIdle()
            val add = onNodeWithTag("composer-add").fetchSemanticsNode().boundsInRoot
            assertEquals(initial.center.x, add.center.x, .5f)
            assertEquals(initial.top, add.top, .5f)
            captureChrome("navigation-phone-enter-$time")
        }
        val card = onNodeWithTag("composer-context-card").fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom < initial.top)
        captureChrome("navigation-phone-open")
        onNodeWithTag("composer-add").performClick()
        mainClock.advanceTimeBy(64)
        captureChrome("navigation-phone-exit")
        mainClock.advanceTimeBy(256)
        waitForIdle()
        onNodeWithTag("composer-context-card").assertDoesNotExist()
        assertEquals(initial, onNodeWithTag("composer-add").fetchSemanticsNode().boundsInRoot)
    }

    private fun ComposeUiTest.captureChrome(name: String) {
        val output = File(System.getProperty("java.io.tmpdir"), "imago-composer-review").apply { mkdirs() }
        File(output, "$name.png").writeBytes(Image.makeFromBitmap(
            onNodeWithTag("chrome-preview").captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
