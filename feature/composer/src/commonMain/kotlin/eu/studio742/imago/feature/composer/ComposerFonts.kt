package eu.studio742.imago.feature.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.getFontResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import eu.studio742.imago.core.composition.CompositionFont
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.feature.composer.resources.Res
import eu.studio742.imago.feature.composer.resources.bebas_neue
import eu.studio742.imago.feature.composer.resources.dancing_script
import eu.studio742.imago.feature.composer.resources.great_vibes
import eu.studio742.imago.feature.composer.resources.inter
import eu.studio742.imago.feature.composer.resources.jetbrains_mono
import eu.studio742.imago.feature.composer.resources.lora
import eu.studio742.imago.feature.composer.resources.montserrat
import eu.studio742.imago.feature.composer.resources.oswald
import eu.studio742.imago.feature.composer.resources.playfair_display

/**
 * The file of each family in the catalogue.
 *
 * The resources library's `Font(resource, …)` is not used: on desktop it builds the font without the
 * variation settings, and a variable family comes out in the file's default weight — Montserrat in
 * Thin, and no other weight. Instead the bytes are read and each platform builds its typeface already
 * at the requested weight ([platformFontFamily]); the stage and the exporters share that same
 * typeface, and that is what makes the exported text the text that was seen.
 */
internal val CompositionFontResources: Map<String, FontResource> by lazy {
    mapOf(
        CompositionFonts.Inter.id to Res.font.inter,
        CompositionFonts.Montserrat.id to Res.font.montserrat,
        CompositionFonts.PlayfairDisplay.id to Res.font.playfair_display,
        CompositionFonts.Lora.id to Res.font.lora,
        CompositionFonts.Oswald.id to Res.font.oswald,
        CompositionFonts.BebasNeue.id to Res.font.bebas_neue,
        CompositionFonts.DancingScript.id to Res.font.dancing_script,
        CompositionFonts.GreatVibes.id to Res.font.great_vibes,
        CompositionFonts.JetBrainsMono.id to Res.font.jetbrains_mono,
    )
}

/** The bytes of each file, read once per process. */
internal object ComposerFontFiles {
    private val mutex = Mutex()
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** [font]'s bytes if they have already been read, without waiting. It is what the stage uses on the first frame. */
    fun loadedOrNull(font: CompositionFont): ByteArray? = loaded[font.id]

    @OptIn(ExperimentalResourceApi::class)
    suspend fun bytes(font: CompositionFont): ByteArray = mutex.withLock {
        loaded.getOrPut(font.id) {
            getFontResourceBytes(getSystemResourceEnvironment(), CompositionFontResources.getValue(font.id))
        }
    }
}

/**
 * The stage family for a catalogue font, built from the file's [bytes] at the already resolved
 * weight. The implementations keep the result: the same font at the same weight is the same typeface
 * everywhere.
 */
internal expect fun platformFontFamily(font: CompositionFont, weight: Int, bytes: ByteArray): FontFamily

/**
 * The families already built, so a text's first frame does not go through the system font when the
 * family is already in memory. Only read and written on the main thread.
 */
private val ReadyFamilies = HashMap<String, FontFamily>()

/**
 * The family the stage draws a text saved with [fontName] at [weight] with.
 *
 * It accepts any name [CompositionFonts.resolve] accepts. While the file is being read — only the
 * first time in each process — the text appears in the default font.
 */
@Composable
internal fun rememberCompositionFontFamily(fontName: String?, weight: Int): FontFamily {
    val font = remember(fontName) { CompositionFonts.resolve(fontName) }
    val resolvedWeight = font.weight(weight)
    val key = "${font.id}@$resolvedWeight"
    val family by produceState(ReadyFamilies[key] ?: FontFamily.Default, key) {
        value = ReadyFamilies[key] ?: withContext(Dispatchers.Default) {
            platformFontFamily(font, resolvedWeight, ComposerFontFiles.bytes(font))
        }.also { ReadyFamilies[key] = it }
    }
    return family
}

/**
 * The bytes of [fontName]'s file, so the stage draws the text with the same typeface as the exporters.
 * It returns `null` only while the file is read for the first time in the process.
 */
@Composable
internal fun rememberCompositionFontBytes(fontName: String?): ByteArray? {
    val font = remember(fontName) { CompositionFonts.resolve(fontName) }
    val bytes by produceState(ComposerFontFiles.loadedOrNull(font), font) {
        value = withContext(Dispatchers.Default) { ComposerFontFiles.bytes(font) }
    }
    return bytes
}
