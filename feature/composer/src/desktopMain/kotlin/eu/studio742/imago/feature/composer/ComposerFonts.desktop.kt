package eu.studio742.imago.feature.composer

import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Typeface
import eu.studio742.imago.core.composition.CompositionFont
import eu.studio742.imago.core.composition.CompositionFonts
import java.util.concurrent.ConcurrentHashMap

/**
 * Skia's typefaces for the catalogue fonts, shared by the stage and the export renderer.
 *
 * The variable families' weight is applied with a clone of the typeface on the `wght` axis: it is the
 * step the resources library's `Font(resource, …)` skips on desktop.
 */
internal object SkiaCompositionFonts {
    private val files = ConcurrentHashMap<String, Typeface>()
    private val weighted = ConcurrentHashMap<String, Typeface>()

    fun typeface(font: CompositionFont, weight: Int, bytes: ByteArray): Typeface =
        weighted.getOrPut("${font.id}@$weight") {
            val file = files.getOrPut(font.id) { FontMgr.default.makeFromData(Data.makeFromBytes(bytes))!! }
            if (font.isVariable) file.makeClone(FontVariation("wght", weight.toFloat())) else file
        }

    /** The typeface of a text saved with [fontName], for the renderer. */
    suspend fun typeface(fontName: String?, weight: Int): Typeface {
        val font = CompositionFonts.resolve(fontName)
        return typeface(font, font.weight(weight), ComposerFontFiles.bytes(font))
    }

    /**
     * [fontName]'s typeface if it has already been loaded by [typeface]. The renderer does not suspend:
     * the fonts are loaded before drawing, like the media.
     */
    fun loaded(fontName: String?, weight: Int): Typeface? {
        val font = CompositionFonts.resolve(fontName)
        return weighted["${font.id}@${font.weight(weight)}"]
    }
}

internal actual fun platformFontFamily(font: CompositionFont, weight: Int, bytes: ByteArray): FontFamily =
    FontFamily(androidx.compose.ui.text.platform.Typeface(SkiaCompositionFonts.typeface(font, weight, bytes)))
