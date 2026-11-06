package eu.studio742.imago.core.composition

import kotlinx.serialization.Serializable

/** Formatting contains no words, asset identity or placement. */
@Serializable
data class CompositionTextStyle(
    val fontFamily: String = CompositionFonts.Default.id,
    val fontSize: Float = 48f,
    val fontWeight: Int = 400,
    val colorArgb: Long = 0xFFFFFFFF,
    val alignment: TextAlignment = TextAlignment.START,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1f,
    val allCaps: Boolean = false,
    val backgroundArgb: Long? = null,
    val backgroundPadding: Float = 24f,
    val backgroundCornerRadius: Float = 0f,
    val strokeArgb: Long? = null,
    val strokeWidth: Float = 0.06f,
    val shadowArgb: Long? = null,
    val shadowRadius: Float = 8f,
    val shadowOffsetX: Float = 0f,
    val shadowOffsetY: Float = 4f,
    val autoHeight: Boolean = false,
    val verticalAlignment: TextVerticalAlignment = TextVerticalAlignment.CENTER,
)

@Serializable
data class NamedCompositionTextStyle(val id: String, val name: String, val style: CompositionTextStyle)

fun CompositionElement.Text.textStyle() = CompositionTextStyle(
    fontFamily = fontFamily,
    fontSize = fontSize,
    fontWeight = fontWeight,
    colorArgb = colorArgb,
    alignment = alignment,
    letterSpacing = letterSpacing,
    lineSpacing = lineSpacing,
    allCaps = allCaps,
    backgroundArgb = backgroundArgb,
    backgroundPadding = backgroundPadding,
    backgroundCornerRadius = backgroundCornerRadius,
    strokeArgb = strokeArgb,
    strokeWidth = strokeWidth,
    shadowArgb = shadowArgb,
    shadowRadius = shadowRadius,
    shadowOffsetX = shadowOffsetX,
    shadowOffsetY = shadowOffsetY,
    autoHeight = autoHeight,
    verticalAlignment = verticalAlignment,
)

fun CompositionElement.Text.withTextStyle(style: CompositionTextStyle) = copy(
    fontFamily = style.fontFamily,
    fontSize = style.fontSize,
    fontWeight = style.fontWeight,
    colorArgb = style.colorArgb,
    alignment = style.alignment,
    letterSpacing = style.letterSpacing,
    lineSpacing = style.lineSpacing,
    allCaps = style.allCaps,
    backgroundArgb = style.backgroundArgb,
    backgroundPadding = style.backgroundPadding,
    backgroundCornerRadius = style.backgroundCornerRadius,
    strokeArgb = style.strokeArgb,
    strokeWidth = style.strokeWidth,
    shadowArgb = style.shadowArgb,
    shadowRadius = style.shadowRadius,
    shadowOffsetX = style.shadowOffsetX,
    shadowOffsetY = style.shadowOffsetY,
    autoHeight = style.autoHeight,
    verticalAlignment = style.verticalAlignment,
)
