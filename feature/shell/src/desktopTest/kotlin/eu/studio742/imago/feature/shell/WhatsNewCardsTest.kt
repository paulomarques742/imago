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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImmichRoomTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * The 0.12.0 news cards, on a phone-sized screen in both languages. Every card shows its title, and
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
                folder?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "news-$tag-${page + 1}.png")) }
            }
        }
    }

    @Test fun everyCardInPortuguese() = cards(Locale("pt", "PT"), "pt")

    @Test fun everyCardInEnglish() = cards(Locale.ENGLISH, "en")

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
