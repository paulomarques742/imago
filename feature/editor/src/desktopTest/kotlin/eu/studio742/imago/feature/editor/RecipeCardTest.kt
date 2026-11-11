package eu.studio742.imago.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The code is drawn with round modules and a symbol over it, so the proof that it works is reading it back. */
@OptIn(ExperimentalTestApi::class)
class RecipeCardTest {
    private val url = "https://imago.742studio.eu/r/CFDJRavrTk6Ut2H1JtPdLyyPxu0"

    private fun read(pixels: IntArray, width: Int, height: Int): String =
        QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text

    /** Only the code with its quiet zone, at [cell] pixels a module. */
    private fun codeAlone(cell: Int): Triple<IntArray, Int, Int> {
        val code = RecipeQrCode(url)
        val quiet = 4 * cell
        val side = code.modules * cell + 2 * quiet
        val image = ImageBitmap(side, side)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(side.toFloat(), side.toFloat())) {
            drawRect(Color.White)
            with(code) { draw(Offset(quiet.toFloat(), quiet.toFloat()), (code.modules * cell).toFloat(), Color.Black, Color.White) }
        }
        return Triple(IntArray(side * side).also { image.readPixels(it) }, side, side)
    }

    @Test
    fun `the drawn code reads back as the link at a generous size and at a small one`() {
        listOf(12, 6, 4).forEach { cell ->
            val (pixels, width, height) = codeAlone(cell)
            assertEquals("at $cell pixels a module", url, read(pixels, width, height))
        }
    }

    @Test
    fun `the code on the finished card reads back as the link, and the card is a png of the right size`() = runComposeUiTest {
        var bytes: ByteArray? = null
        var pixels: IntArray? = null
        setContent {
            val measurer = TextMeasurer(createFontFamilyResolver(), Density(3f), LayoutDirection.Ltr)
            val photo = ImageBitmap(853, 1280)
            val card = paintRecipeCard(photo, RecipeCardTexts("Warm evening", "IMAGO recipe", "Scan to open this recipe in IMAGO."), RecipeQrCode(url), measurer)
            pixels = IntArray(RECIPE_CARD_WIDTH * RECIPE_CARD_HEIGHT).also { card.readPixels(it) }
            bytes = card.encodeToPng()
        }
        waitForIdle()

        assertEquals(url, read(pixels!!, RECIPE_CARD_WIDTH, RECIPE_CARD_HEIGHT))
        val image = ImageIO.read(ByteArrayInputStream(bytes!!))
        assertEquals(RECIPE_CARD_WIDTH, image.width)
        assertEquals(RECIPE_CARD_HEIGHT, image.height)
        assertTrue(bytes!!.size > 1000)
    }

    @Test
    fun `a card without a link yet draws without a code`() = runComposeUiTest {
        var drew = false
        setContent {
            val measurer = TextMeasurer(createFontFamilyResolver(), Density(3f), LayoutDirection.Ltr)
            paintRecipeCard(ImageBitmap(100, 100), RecipeCardTexts("A name", "Kicker", "Hint"), null, measurer)
            drew = true
        }
        waitForIdle()
        assertTrue(drew)
    }
}
