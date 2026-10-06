package eu.studio742.imago.feature.composer

import android.graphics.Typeface
import android.graphics.fonts.Font
import androidx.compose.ui.text.font.FontFamily
import eu.studio742.imago.core.composition.CompositionFont
import eu.studio742.imago.core.composition.CompositionFonts
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's typefaces for the catalogue fonts, shared by the stage and the exporter.
 *
 * They are built directly from the bytes, without temporary files: `Font.Builder` accepts a direct
 * buffer and applies the `wght` axis, which is what gives the variable families the requested weight.
 */
internal object AndroidCompositionFonts {
    private val buffers = ConcurrentHashMap<String, ByteBuffer>()
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    fun typeface(font: CompositionFont, weight: Int, bytes: ByteArray): Typeface =
        typefaces.getOrPut("${font.id}@$weight") {
            // One buffer per file, shared by the weights: each typeface reads its copy from the position.
            val buffer = buffers.getOrPut(font.id) {
                ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
            }
            val builder = Font.Builder(buffer.duplicate()).setWeight(weight)
            if (font.isVariable) builder.setFontVariationSettings("'wght' $weight")
            Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(builder.build()).build())
                // A character the family does not have — an emoji, for example — comes from the system
                // instead of coming out as an empty square.
                .setSystemFallback("sans-serif")
                .build()
        }

    /** The typeface of a text saved with [fontName], for the exporter. */
    suspend fun typeface(fontName: String?, weight: Int): Typeface {
        val font = CompositionFonts.resolve(fontName)
        return typeface(font, font.weight(weight), ComposerFontFiles.bytes(font))
    }
}

internal actual fun platformFontFamily(font: CompositionFont, weight: Int, bytes: ByteArray): FontFamily =
    FontFamily(androidx.compose.ui.text.font.Typeface(AndroidCompositionFonts.typeface(font, weight, bytes)))
