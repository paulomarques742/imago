package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.StateFlow

/** Interface roles only. Saved artwork and renderers must not read this palette. */
@Immutable
data class ImagoUiPalette(
    /** Light surfaces with dark text: what the system bars and the Material scheme have to follow. */
    val isLight: Boolean = false,
    val background: Color = ImagoColors.Background,
    /** Around the photo and the composer's page. Neutral in every theme, so that it does not colour how a photo reads. */
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
    /** What stands out from the text on a surface: the icon of a row, a spinner, a selected tab, a primary button. */
    val emphasis: Color = ImagoColors.Ivory,
    val onEmphasis: Color = ImagoColors.BrandBlack,
    val border: Color = ImagoColors.BorderVisible,
    val subtleBorder: Color = ImagoColors.BorderSubtle,
    val strongBorder: Color = ImagoColors.BorderStrong,
    val accent: Color = ImagoColors.Gold,
    val onAccent: Color = ImagoColors.BrandBlack,
    val error: Color = ImagoColors.Error,
    val danger: Color = ImagoColors.Danger,
    /** The red as a fill, for where red alone would not read, as on the workspace. Deeper than [danger], which is text. */
    val dangerFill: Color = Color(0xFFD92D30),
    val onDanger: Color = ImagoColors.BrandWhite,
    val scrim: Color = ImagoColors.Scrim,
    /**
     * What is drawn on a photo: badges, guides, handles. A photo is the same in every theme, so these
     * two are as well; only they can promise a contrast with pixels the theme knows nothing about.
     */
    val onImage: Color = ImagoColors.BrandWhite,
    /** The dark side of [onImage], given its alpha where it is used: a chip's fill, a guide's halo, a gradient. */
    val imageShade: Color = Color.Black,
    /** The accent on a photo: the gold reads there in every theme, where the light theme's darker one would not. */
    val imageAccent: Color = ImagoColors.Gold,
    val outsidePageContentAlpha: Float = .4f,
    val alignmentGuide: Color = ImagoColors.Gold,
    val spacingGuide: Color = ImagoColors.Gold,
)

val ImagoDarkPalette = ImagoUiPalette()

val ImagoLightPalette = ImagoUiPalette(
    isLight = true,
    background = Color(0xFFF5F5F2),
    workspace = Color(0xFF8E8E8E),
    surface = Color(0xFFFBFBF9),
    elevated = Color(0xFFECECE8),
    navigation = Color(0xFFE6E6E3),
    glass = Color(0xF2F5F5F2),
    selectedSurface = Color(0xFFDADAD6),
    choiceSurface = Color(0xFFE6E6E3),
    selectedChoiceSurface = ImagoColors.BrandBlack,
    onSelectedChoiceSurface = ImagoColors.BrandWhite,
    text = ImagoColors.BrandBlack.copy(alpha = .94f),
    secondaryText = ImagoColors.BrandBlack.copy(alpha = .72f),
    tertiaryText = ImagoColors.BrandBlack.copy(alpha = .62f),
    disabledText = ImagoColors.BrandBlack.copy(alpha = .34f),
    emphasis = ImagoColors.Charcoal,
    onEmphasis = ImagoColors.BrandWhite,
    border = ImagoColors.BrandBlack.copy(alpha = .18f),
    subtleBorder = ImagoColors.BrandBlack.copy(alpha = .10f),
    strongBorder = ImagoColors.BrandBlack.copy(alpha = .30f),
    // The gold of the dark theme is 1.9:1 on these surfaces: as text it would not read.
    accent = Color(0xFF7F6400),
    onAccent = ImagoColors.BrandWhite,
    error = Color(0xFFB3261E),
    danger = Color(0xFFC4201F),
    dangerFill = Color(0xFFC4201F),
    scrim = Color.Black.copy(alpha = .32f),
)

val LocalImagoUiPalette = staticCompositionLocalOf { ImagoDarkPalette }

object ImagoUi {
    val colors: ImagoUiPalette
        @Composable @ReadOnlyComposable get() = LocalImagoUiPalette.current
}

/** What the person chose in Settings. [SYSTEM] follows the device, and is what no choice at all reads as. */
enum class AppearancePreference {
    SYSTEM,
    LIGHT,
    DARK;

    fun palette(systemIsDark: Boolean): ImagoUiPalette = when (this) {
        SYSTEM -> if (systemIsDark) ImagoDarkPalette else ImagoLightPalette
        LIGHT -> ImagoLightPalette
        DARK -> ImagoDarkPalette
    }

    companion object {
        /** A stored choice: nothing stored, or a value from another version, is [SYSTEM]. */
        fun of(stored: String?): AppearancePreference = entries.firstOrNull { it.name == stored } ?: SYSTEM
    }
}

/** Where each platform keeps the choice. It belongs to the device, not to the account or to a project. */
interface AppearanceSettings {
    val preference: StateFlow<AppearancePreference>
    fun choose(preference: AppearancePreference)
}

/** The platform's appearance settings, given by the app root. Null in tests and previews, which stay dark. */
val LocalAppearanceSettings = staticCompositionLocalOf<AppearanceSettings?> { null }

/** The palette the person chose, or the one the system asks for. */
@Composable
internal fun chosenPalette(): ImagoUiPalette {
    val settings = LocalAppearanceSettings.current ?: return ImagoDarkPalette
    val preference by settings.preference.collectAsState()
    return preference.palette(systemIsDark = isSystemInDarkTheme())
}
