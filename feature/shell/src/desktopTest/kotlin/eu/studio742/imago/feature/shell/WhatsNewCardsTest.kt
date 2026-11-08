package eu.studio742.imago.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImmichRoomTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * The latest news cards, on a phone-sized screen in both languages. Every card shows its title, and
 * the last one ends the deck. With `IMAGO_SCREENSHOTS` set to a folder, each card is saved there.
 */
@OptIn(ExperimentalTestApi::class)
class WhatsNewCardsTest {
    private val systemLocale = Locale.getDefault()
    private val release = Releases.first { it.pages.isNotEmpty() }

    @After fun restore() = Locale.setDefault(systemLocale)

    private fun cards(locale: Locale, tag: String) {
        Locale.setDefault(locale)
        val folder = System.getenv("IMAGO_SCREENSHOTS")?.let(::File)?.also { it.mkdirs() }
        release.pages.indices.forEach { page ->
            runDesktopComposeUiTest(width = 1082, height = 2402) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                        ImmichRoomTheme {
                            // The app behind, dimmed as a dialog dims it.
                            Box(Modifier.fillMaxSize().background(ImagoColors.Background).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                WhatsNewCards(
                                    release = release,
                                    older = emptyList(),
                                    onDone = {},
                                    initialPage = page,
                                    animate = false,
                                    modifier = Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                val title = runBlocking { getString(release.pages[page].title) }
                onNodeWithText(title).fetchSemanticsNode()
                // A page of items shows each of them.
                release.pages[page].items.forEach { item -> onNodeWithText(runBlocking { getString(item) }).fetchSemanticsNode() }
                // Semantics can be ready before every graphics layer reaches the captured frame.
                mainClock.advanceTimeBy(100)
                waitForIdle()
                if (folder != null) {
                    onRoot().captureToImage()
                    mainClock.advanceTimeByFrame()
                    waitForIdle()
                }
                folder?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "news-$tag-${page + 1}.png")) }
            }
        }
    }

    @Test fun everyCardInPortuguese() = cards(Locale("pt", "PT"), "pt")

    @Test fun everyCardInEnglish() = cards(Locale.ENGLISH, "en")

    /** A release written as lines still opens as a card, the one with its list. */
    @Test fun aReleaseOfLinesIsACardToo() {
        Locale.setDefault(Locale.ENGLISH)
        val lines = Releases.first { it.pages.isEmpty() }
        runDesktopComposeUiTest(width = 1082, height = 2402) {
            setContent { ImmichRoomTheme { WhatsNewCards(lines, emptyList(), onDone = {}, animate = false) } }
            onNodeWithText("Version ${lines.version}").fetchSemanticsNode()
            onNodeWithText(runBlocking { getString(lines.notes.first()) }).fetchSemanticsNode()
            onNodeWithText("Get started").fetchSemanticsNode()
        }
    }

    /**
     * The card that announces the light mode turns the deck light, and going back turns it dark again.
     * With `IMAGO_SCREENSHOTS` set, the circle is saved as it opens.
     */
    @Test fun theLightModeCardTurnsTheDeckLight() {
        Locale.setDefault(Locale.ENGLISH)
        val light = release.pages.indexOfFirst { it.turnsLight }
        assertTrue("the latest release announces the light mode", light > 0)
        val folder = System.getenv("IMAGO_SCREENSHOTS")?.let(::File)?.also { it.mkdirs() }
        runDesktopComposeUiTest(width = 1082, height = 2402) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                    ImmichRoomTheme {
                        Box(Modifier.fillMaxSize().background(ImagoColors.Background).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                            WhatsNewCards(
                                release = release,
                                older = emptyList(),
                                onDone = {},
                                initialPage = light - 1,
                                animate = false,
                                modifier = Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            onNodeWithTag("news-cards").assertExists()
            onNodeWithText("Next").performClick()
            if (folder != null) {
                // The page slides in first; then the circle opens over 620 ms.
                mainClock.autoAdvance = false
                var frame = 0
                repeat(40) {
                    mainClock.advanceTimeBy(50)
                    if (it >= 8 && it % 2 == 0) {
                        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "news-reveal-${frame++}.png"))
                    }
                }
                mainClock.autoAdvance = true
            }
            waitForIdle()
            onNodeWithTag("news-cards-light").assertExists()

            onNodeWithTag("news-cards-light").performTouchInput { swipeRight() }
            waitForIdle()
            onNodeWithTag("news-cards").assertExists()
        }
    }

    @Test fun theLastCardEndsTheDeck() {
        Locale.setDefault(Locale.ENGLISH)
        var done = 0
        runDesktopComposeUiTest(width = 1082, height = 2402) {
            setContent {
                ImmichRoomTheme {
                    WhatsNewCards(release, emptyList(), onDone = { done++ }, initialPage = release.pages.lastIndex, animate = false)
                }
            }
            onNodeWithText("Get started").performClick()
        }
        assertEquals(1, done)
    }
}
