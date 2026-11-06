package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import eu.studio742.imago.core.composition.CompositionFonts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ComposerFontPreviewTest {
    @Test fun `each preview frame carries bytes belonging to its requested family`() = runComposeUiTest {
        val fonts = listOf(CompositionFonts.Inter, CompositionFonts.Lora, CompositionFonts.Montserrat)
        val expected = runBlocking { fonts.associate { it.id to ComposerFontFiles.bytes(it).contentHashCode() } }
        var requested by mutableStateOf(fonts.first().id)
        val frames = mutableListOf<Pair<String, Int>>()
        setContent {
            val bytes = rememberCompositionFontBytes(requested)
            SideEffect { if (bytes != null) frames += requested to bytes.contentHashCode() }
            Box {}
        }
        repeat(3) { fonts.forEach { font -> runOnIdle { requested = font.id }; waitForIdle() } }
        assertEquals(expected.keys, frames.map { it.first }.toSet())
        frames.forEach { (font, hash) -> assertEquals("Mismatched bytes for $font", expected[font], hash) }
    }
}
