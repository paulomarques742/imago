package eu.studio742.imago.core.designsystem

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The IMAGO brand, drawn in code so both apps use the same curves.
 *
 * They were Android vector drawables, which desktop cannot read; the desktop header ended up writing
 * "IMAGO" as live text with letter spacing, which the brand guidelines forbid. The geometries are the
 * brand's own, unchanged.
 */
object ImagoBrand {
    /**
     * Wordmark: width 154 · cap height 30, taken from the horizontal logo (imago-logo-horizontal.svg;
     * the group shifted by translate(66 9), where this viewport starts). Never composed with live
     * text. The curves are white and the default tint is Ivory, as in the official file; a
     * `colorFilter` at the call site swaps it.
     */
    val Wordmark: ImageVector by lazy {
        ImageVector.Builder("ImagoWordmark", 154.dp, 30.dp, 154f, 30f, tintColor = ImagoColors.Ivory, tintBlendMode = BlendMode.SrcIn).apply {
            fun letter(data: String, evenOdd: Boolean = false) = addPath(
                pathData = addPathNodes(data),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.White),
            )
            letter("M0,0h3.5v30H0z") // I
            letter("M14.5,0h3.5l10.5,30L39,0h3.5v30H39V10l-7,20h-7l-7,-20v20h-3.5z") // M
            letter("M62,0h3l12,30h-3.44l-2.2,-5.5H55.64L53.44,30H50zm1.5,4.85L56.84,21.5h13.32z", evenOdd = true) // A
            letter("M111.87,9A14.5,15.5 0 1 0 113,15h-10.5v3h6.58a10.9,12.5 0 1 1 -1.02,-9z") // G
            letter("M125,15a14.5,15.5 0 1 1 29,0 14.5,15.5 0 1 1 -29,0zm3.6,0a10.9,12.5 0 1 0 21.8,0 10.9,12.5 0 1 0 -21.8,0z", evenOdd = true) // O
        }.build()
    }

    /**
     * Symbol: 48-unit grid — bar 5 · gap 18 · interval 6 · square 12 (imago-symbol.svg). The frame is
     * made of filled rectangles and not a stroke, because the four bars have different lengths and a
     * single stroke created false joints.
     */
    val Symbol: ImageVector by lazy {
        ImageVector.Builder("ImagoSymbol", 48.dp, 48.dp, 48f, 48f).apply {
            addPath(addPathNodes("M0,0h5v48H0zM5,0h43v5H5zM43,5h5v25h-5zM5,43h25v5H5z"), fill = SolidColor(ImagoColors.Ivory))
            addPath(addPathNodes("M36,36h12v12H36z"), fill = SolidColor(ImagoColors.Ivory))
        }.build()
    }
}
