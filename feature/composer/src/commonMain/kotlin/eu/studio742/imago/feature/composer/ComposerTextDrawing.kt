package eu.studio742.imago.feature.composer

import androidx.compose.ui.graphics.drawscope.DrawScope
import eu.studio742.imago.core.composition.CompositionElement

/**
 * Draws a text on the stage with this platform's exporter painter: Android's `Paint` on one side,
 * Skia on the other.
 *
 * Before, the stage drew with Compose's `Text`, at another size and without the effects, and the file
 * came out different from what was seen. Now the stage and the exporter call the same code. [scale] is
 * drawing pixels per model pixel; [fontBytes] is `null` only while the font file is being read, and
 * then the text comes out in the system font.
 */
internal expect fun DrawScope.drawCompositionText(element: CompositionElement.Text, scale: Float, fontBytes: ByteArray?)

/**
 * The standard deviation of a blur with [radius] pixels.
 *
 * It is the conversion Android's `BlurMaskFilter` applies to the radius it receives. Desktop's Skia
 * asks for the standard deviation directly, and without this calculation the two shadows would come
 * out with different spreads.
 */
internal fun blurSigma(radius: Float): Float = if (radius > 0f) 0.57735f * radius + 0.5f else 0f
