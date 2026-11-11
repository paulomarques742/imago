package eu.studio742.imago.feature.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import eu.studio742.imago.core.designsystem.ImagoBrand
import eu.studio742.imago.core.designsystem.ImagoColors
import kotlin.math.roundToInt

private const val CARD_DENSITY = 3f
internal const val RECIPE_CARD_WIDTH = 1080
internal const val RECIPE_CARD_HEIGHT = 1920

/** A finder pattern is seven modules across; they are drawn whole, as rounded squares. */
private const val FINDER = 7

/** The share of the code's side the symbol clears. The highest error correction recovers far more than this. */
private const val LOGO_SHARE = 0.24f

/** The words on the card, already in the app's language: the painter runs outside any composition. */
internal class RecipeCardTexts(val name: String, val kicker: String, val hint: String)

/** The modules of a link's QR code, with the redundancy that lets the symbol sit over its middle. */
internal class RecipeQrCode(url: String) {
    private val matrix: BitMatrix = QRCodeWriter().encode(
        url,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H),
    )

    val modules: Int = matrix.width

    private val logoCells: Int = ((modules * LOGO_SHARE).roundToInt() or 1)
    private val logoStart: Int = (modules - logoCells) / 2

    private fun inFinder(x: Int, y: Int) =
        (x < FINDER && y < FINDER) || (x >= modules - FINDER && y < FINDER) || (x < FINDER && y >= modules - FINDER)

    private fun inLogo(x: Int, y: Int) = x in logoStart until logoStart + logoCells && y in logoStart until logoStart + logoCells

    private fun dark(x: Int, y: Int) =
        x in 0 until modules && y in 0 until modules && matrix.get(x, y) && !inFinder(x, y) && !inLogo(x, y)

    /** Round modules that flow into their neighbours; the cell stays fully covered where it counts for a reader. */
    fun DrawScope.draw(topLeft: Offset, side: Float, foreground: Color, background: Color) {
        val cell = side / modules
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (!dark(x, y)) continue
                val left = topLeft.x + x * cell
                val top = topLeft.y + y * cell
                drawCircle(foreground, radius = cell / 2, center = Offset(left + cell / 2, top + cell / 2))
                if (dark(x + 1, y)) drawRect(foreground, Offset(left + cell / 2, top), Size(cell, cell))
                if (dark(x, y + 1)) drawRect(foreground, Offset(left, top + cell / 2), Size(cell, cell))
            }
        }
        finder(topLeft, cell, foreground, background)
        finder(topLeft + Offset((modules - FINDER) * cell, 0f), cell, foreground, background)
        finder(topLeft + Offset(0f, (modules - FINDER) * cell), cell, foreground, background)

        val hole = logoCells * cell
        val holeAt = topLeft + Offset(logoStart * cell, logoStart * cell)
        drawRoundRect(background, holeAt, Size(hole, hole), CornerRadius(hole * 0.22f))
        val symbol = hole * 0.72f
        val symbolWidth = symbol * ImagoBrand.Symbol.viewportWidth / ImagoBrand.Symbol.viewportHeight
        drawBrand(ImagoBrand.Symbol, holeAt + Offset((hole - symbolWidth) / 2, (hole - symbol) / 2), symbol, foreground)
    }

    private fun DrawScope.finder(topLeft: Offset, cell: Float, foreground: Color, background: Color) {
        fun square(inset: Float, color: Color, radius: Float) {
            val length = (FINDER - 2 * inset) * cell
            drawRoundRect(color, topLeft + Offset(inset * cell, inset * cell), Size(length, length), CornerRadius(radius * cell))
        }
        square(0f, foreground, 2.2f)
        square(1f, background, 1.4f)
        square(2f, foreground, 1f)
    }
}

/**
 * The image of a recipe to pass around: the photo with the recipe on it, in the language of the
 * «Show your edit» video, with the code that opens it. Without a [code] yet, the place it will take.
 */
internal fun paintRecipeCard(
    photo: ImageBitmap,
    texts: RecipeCardTexts,
    code: RecipeQrCode?,
    measurer: TextMeasurer,
): ImageBitmap {
    val image = ImageBitmap(RECIPE_CARD_WIDTH, RECIPE_CARD_HEIGHT)
    val backdrop = reelBackdrop(photo, RECIPE_CARD_WIDTH, RECIPE_CARD_HEIGHT)
    CanvasDrawScope().draw(
        Density(CARD_DENSITY),
        LayoutDirection.Ltr,
        Canvas(image),
        Size(RECIPE_CARD_WIDTH.toFloat(), RECIPE_CARD_HEIGHT.toFloat()),
    ) {
        drawImage(backdrop)
        drawChrome()
        drawCardPhoto(photo)
        drawPanel(texts, code, measurer)
    }
    return image
}

private fun DrawScope.drawChrome() {
    val symbol = 16.dp.toPx()
    val wordHeight = 9.dp.toPx()
    val origin = Offset(20.dp.toPx(), 26.dp.toPx())
    drawBrand(ImagoBrand.Symbol, origin, symbol, ImagoColors.Ivory.copy(alpha = 0.9f))
    drawBrand(
        ImagoBrand.Wordmark,
        Offset(origin.x + symbol + 7.dp.toPx(), origin.y + (symbol - wordHeight) / 2),
        wordHeight,
        ImagoColors.Ivory.copy(alpha = 0.9f),
    )
}

private fun DrawScope.drawCardPhoto(photo: ImageBitmap) {
    val box = Rect(16.dp.toPx(), 64.dp.toPx(), size.width - 16.dp.toPx(), PANEL_TOP.dp.toPx() - 16.dp.toPx())
    val scale = minOf(box.width / photo.width, box.height / photo.height)
    val width = photo.width * scale
    val height = photo.height * scale
    val rect = Rect(Offset(box.center.x - width / 2, box.center.y - height / 2), Size(width, height))
    val clip = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(16.dp.toPx()))) }
    clipPath(clip) {
        drawImage(
            photo,
            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

private const val PANEL_TOP = 432

private fun DrawScope.drawPanel(texts: RecipeCardTexts, code: RecipeQrCode?, measurer: TextMeasurer) {
    val top = PANEL_TOP.dp.toPx()
    val radius = 20.dp.toPx()
    drawPath(
        Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = top,
                    right = size.width,
                    bottom = size.height,
                    topLeftCornerRadius = CornerRadius(radius),
                    topRightCornerRadius = CornerRadius(radius),
                ),
            )
        },
        ImagoColors.Surface.copy(alpha = 0.96f),
    )
    drawLine(
        ImagoColors.BorderVisible,
        Offset(size.width / 2 - 18.dp.toPx(), top + 10.dp.toPx()),
        Offset(size.width / 2 + 18.dp.toPx(), top + 10.dp.toPx()),
        strokeWidth = 4.dp.toPx(),
        cap = StrokeCap.Round,
    )

    val margin = 20.dp.toPx()
    val tile = 164.dp.toPx()
    val tileAt = Offset(margin, top + 28.dp.toPx())
    drawRoundRect(ImagoColors.BrandWhite, tileAt, Size(tile, tile), CornerRadius(14.dp.toPx()))
    val quiet = 12.dp.toPx()
    if (code != null) {
        with(code) { draw(tileAt + Offset(quiet, quiet), tile - 2 * quiet, ImagoColors.BrandBlack, ImagoColors.BrandWhite) }
    } else {
        val symbol = tile * 0.3f
        val width = symbol * ImagoBrand.Symbol.viewportWidth / ImagoBrand.Symbol.viewportHeight
        drawBrand(ImagoBrand.Symbol, tileAt + Offset((tile - width) / 2, (tile - symbol) / 2), symbol, ImagoColors.BrandBlack.copy(alpha = 0.18f))
    }

    val textLeft = tileAt.x + tile + 16.dp.toPx()
    val textWidth = size.width - margin - textLeft
    var y = tileAt.y + 2.dp.toPx()
    val kicker = layout(measurer, texts.kicker, KICKER, textWidth, 1)
    drawText(kicker, topLeft = Offset(textLeft, y))
    y += kicker.size.height + 8.dp.toPx()
    val name = layout(measurer, texts.name, NAME, textWidth, 3)
    drawText(name, topLeft = Offset(textLeft, y))
    y += name.size.height + 10.dp.toPx()
    drawText(layout(measurer, texts.hint, HINT, textWidth, 4), topLeft = Offset(textLeft, y))
}

private fun DrawScope.layout(measurer: TextMeasurer, text: String, style: TextStyle, width: Float, lines: Int): TextLayoutResult =
    measurer.measure(
        AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = lines,
        constraints = Constraints(maxWidth = width.toInt()),
        layoutDirection = LayoutDirection.Ltr,
        density = this,
    )

private val KICKER = TextStyle(color = ImagoColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Medium)
private val NAME = TextStyle(color = ImagoColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
private val HINT = TextStyle(color = ImagoColors.TextSecondary, fontSize = 13.sp)
