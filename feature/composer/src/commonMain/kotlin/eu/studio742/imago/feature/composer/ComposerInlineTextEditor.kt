package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoUi
import kotlin.math.roundToInt

/** Native input supplies selection, clipboard and IME; the export painter remains the visible ink. */
@Composable
internal fun ComposerInlineTextEditor(session: ComposerTextSession, pageWidth: Float, pageHeight: Float,
    origin: Offset, actions: ComposerEditorViewModel, onCursor: (NormalizedRect) -> Unit) {
    val text = session.draft
    val bounds = text.transform.bounds
    val density = LocalDensity.current
    val ui = ImagoUi.colors
    val scale = pageWidth / REFERENCE_PAGE_WIDTH
    val width = bounds.width * pageWidth
    val height = bounds.height * pageHeight
    var value by remember(text.id) { mutableStateOf(TextFieldValue(text.text,
        if (session.original == null) TextRange(0, text.text.length) else TextRange(text.text.length))) }
    val font = rememberCompositionFontBytes(text.fontFamily)
    val family = rememberCompositionFontFamily(text.fontFamily, text.fontWeight)
    if (font == null) return
    val layout = remember(text, width, height, font) { measureCompositionText(text, width, height, scale, font) }
    val mapping = remember(text.text, text.allCaps, layout) { text.visualMapping(layout.lines) }
    val visual = remember(mapping) { VisualTransformation {
        TransformedText(AnnotatedString(mapping.text), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = mapping.originalToVisual[offset.coerceIn(mapping.originalToVisual.indices)]
            override fun transformedToOriginal(offset: Int) = mapping.visualToOriginal[offset.coerceIn(mapping.visualToOriginal.indices)]
        })
    } }
    var inputLayout by remember(text.id) { mutableStateOf<TextLayoutResult?>(null) }
    val inputBaseline = inputLayout?.firstBaseline ?: 0f
    val focus = remember(text.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(text.id) { focus.requestFocus(); if (session.fontBeforePreview == null) keyboard?.show() else keyboard?.hide() }
    LaunchedEffect(font) { if (font != null) actions.refreshTextMetrics(text.id) }
    DisposableEffect(text.id) { onDispose { keyboard?.hide() } }
    val inset = if (text.backgroundArgb != null) text.backgroundPadding * scale else 0f
    val firstBaseline = layout.lines.firstOrNull()?.baseline ?: -layout.metrics.ascent
    val inputY = firstBaseline - inputBaseline
    LaunchedEffect(value.selection, inputLayout, text.transform, inputY) {
        inputLayout?.let { result ->
            if (result.layoutInput.text.text != mapping.text) return@let
            val caret = result.getCursorRect(mapping.originalToVisual[value.selection.end.coerceIn(mapping.originalToVisual.indices)])
            val localX = (inset + caret.center.x - width / 2f) * if (text.transform.mirrorHorizontal) -1f else 1f
            val localY = (inputY + caret.center.y - height / 2f) * if (text.transform.mirrorVertical) -1f else 1f
            val (x, y) = rotateVector(localX, localY, text.transform.rotationDegrees)
            onCursor(NormalizedRect(bounds.x + bounds.width / 2f + x / pageWidth - .002f,
                bounds.y + bounds.height / 2f + y / pageHeight - caret.height / pageHeight / 2f,
                .004f, (caret.height / pageHeight).coerceAtLeast(.001f)))
        }
    }
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(ui.accent, ui.accent.copy(alpha = .3f))) {
        Box(Modifier.offset { IntOffset((origin.x + bounds.x * pageWidth).roundToInt(), (origin.y + bounds.y * pageHeight).roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .graphicsLayer {
                rotationZ = text.transform.rotationDegrees
                scaleX = if (text.transform.mirrorHorizontal) -1f else 1f
                scaleY = if (text.transform.mirrorVertical) -1f else 1f
            }) {
            BasicTextField(value = value, onValueChange = {
                value = it
                if (it.text != text.text) actions.updateTextDraft(it.text)
            }, modifier = Modifier.offset { IntOffset(inset.roundToInt(), inputY.roundToInt()) }
                .width(with(density) { (width - inset * 2).coerceAtLeast(1f).toDp() })
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when {
                        it.key == Key.Escape -> { actions.cancelTextEditing(); true }
                        it.key == Key.Enter && it.isCtrlPressed -> { actions.confirmTextEditing(); true }
                        else -> false
                    }
                }
                .focusRequester(focus).testTag("composer_inline_text"),
                textStyle = TextStyle(color = ui.text.copy(alpha = 0f), fontFamily = family,
                    fontWeight = FontWeight(CompositionFonts.resolve(text.fontFamily).weight(text.fontWeight)),
                    fontSize = with(density) { (text.fontSize * scale).toSp() },
                    letterSpacing = with(density) { (text.letterSpacing * text.fontSize * scale).toSp() },
                    lineHeight = with(density) { ((layout.metrics.descent - layout.metrics.ascent) * text.lineSpacing).toSp() },
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None),
                    textAlign = when (text.alignment) { TextAlignment.START -> TextAlign.Left; TextAlignment.CENTER -> TextAlign.Center; TextAlignment.END -> TextAlign.Right }),
                cursorBrush = SolidColor(ui.accent), visualTransformation = visual, readOnly = session.fontBeforePreview != null,
                onTextLayout = { inputLayout = it })
        }
    }
}
