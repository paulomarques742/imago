package eu.studio742.imago.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Interface roles only. Saved artwork and renderers must not read this palette. */
@Immutable
data class ImagoUiPalette(
    val background: Color = ImagoColors.Background,
    val workspace: Color = ImagoColors.BrandBlack,
    val surface: Color = ImagoColors.Surface,
    val elevated: Color = ImagoColors.SurfaceElevated,
    val navigation: Color = ImagoColors.NavBar,
    val glass: Color = ImagoColors.Glass,
    val selectedSurface: Color = ImagoColors.Graphite,
    val choiceSurface: Color = ImagoColors.Charcoal,
    val selectedChoiceSurface: Color = ImagoColors.BrandWhite,
    val onSelectedChoiceSurface: Color = ImagoColors.BrandBlack,
    val text: Color = ImagoColors.TextPrimary,
    val secondaryText: Color = ImagoColors.TextSecondary,
    val tertiaryText: Color = ImagoColors.TextTertiary,
    val disabledText: Color = ImagoColors.TextDisabled,
    val border: Color = ImagoColors.BorderVisible,
    val subtleBorder: Color = ImagoColors.BorderSubtle,
    val strongBorder: Color = ImagoColors.BorderStrong,
    val accent: Color = ImagoColors.Gold,
    val onAccent: Color = ImagoColors.BrandBlack,
    val error: Color = ImagoColors.Error,
    val danger: Color = ImagoColors.Danger,
    val scrim: Color = ImagoColors.Scrim,
    val outsidePageContentAlpha: Float = .4f,
    val alignmentGuide: Color = ImagoColors.Gold,
    val spacingGuide: Color = ImagoColors.Gold,
)

val LocalImagoUiPalette = staticCompositionLocalOf { ImagoUiPalette() }

object ImagoUi {
    val colors: ImagoUiPalette
        @Composable @ReadOnlyComposable get() = LocalImagoUiPalette.current
}
