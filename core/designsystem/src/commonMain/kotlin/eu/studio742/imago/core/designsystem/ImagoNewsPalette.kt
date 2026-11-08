package eu.studio742.imago.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours of the news cards: the brand treatment of the site's open-source section, not the app's
 * theme. The cards are [ImagoNewsDark] in both themes, and turn [ImagoNewsLight] only once a release
 * has announced the light mode.
 */
@Immutable
data class ImagoNewsPalette(
    val isLight: Boolean,
    /** Under everything, and what the colour field fades into at the top, the bottom and the text side. */
    val background: Color,
    /** The colour field: twelve soft glows on a 3 × 4 grid, row by row from the top. */
    val backdrop: List<Color>,
    val title: Color,
    val body: Color,
    /** A way out that should not compete with the main button: Skip. */
    val quiet: Color,
    /** The eyebrow, the bullets, a badge's icon and edge. */
    val accent: Color,
    val badge: Color,
    val border: Color,
    /** The site's pill button, and the dot of the current page. */
    val action: Color,
    val onAction: Color,
    val dot: Color,
    /** The lead page: its glow, its pill, its frame. */
    val featured: Color = ImagoColors.Gold,
    val onFeatured: Color = ImagoColors.Background,
    val featuredTitle: Color = ImagoColors.Gold,
    val featuredBadge: List<Color> = listOf(Color(0xFFF2D77A), ImagoColors.Gold, Color(0xFF9C7A1C)),
    val featuredBadgeEdge: Color = Color(0xCCFFECAA),
) {
    init { require(backdrop.size == 12) { "The colour field has twelve glows." } }
}

val ImagoNewsDark = ImagoNewsPalette(
    isLight = false,
    background = ImagoColors.Background,
    // The site's colours, already saturated ×1.5 and darkened ×0.5 as it does: sky blue, warm brown,
    // then dark olive and teal.
    backdrop = listOf(
        Color(0xFF4C5F7B), Color(0xFF3C5471), Color(0xFF345274),
        Color(0xFF342916), Color(0xFF382A17), Color(0xFF3C301A),
        Color(0xFF1D190C), Color(0xFF172322), Color(0xFF2D2B0E),
        Color(0xFF0B1921), Color(0xFF22250F), Color(0xFF382E00),
    ),
    title = ImagoColors.BrandWhite,
    body = Color.White.copy(alpha = 0.78f),
    quiet = Color.White.copy(alpha = 0.72f),
    accent = ImagoColors.Gold,
    badge = Color.White.copy(alpha = 0.08f),
    border = Color.White.copy(alpha = 0.08f),
    action = ImagoColors.Ivory,
    onAction = ImagoColors.Background,
    dot = Color.White.copy(alpha = 0.3f),
)

/**
 * The same field in daylight: the hues of [ImagoNewsDark], pale. Every glow is light enough for the
 * text to stay above 4.5:1 on it without the fades, the darker gold included.
 */
val ImagoNewsLight = ImagoNewsPalette(
    isLight = true,
    background = ImagoColors.BrandWhite,
    backdrop = listOf(
        Color(0xFFC9D6E8), Color(0xFFBFD0E6), Color(0xFFC1D3EA),
        Color(0xFFEBDCC2), Color(0xFFEED9BE), Color(0xFFF0DFC0),
        Color(0xFFE3DDC2), Color(0xFFC9DEDB), Color(0xFFE6E0B8),
        Color(0xFFC2D9E3), Color(0xFFDDE2C2), Color(0xFFF0E0A8),
    ),
    title = ImagoColors.BrandBlack,
    body = ImagoColors.BrandBlack.copy(alpha = 0.78f),
    quiet = ImagoColors.BrandBlack.copy(alpha = 0.72f),
    // Darker than the light theme's gold, which is 3.7:1 on the blue glows.
    accent = Color(0xFF6B5400),
    badge = ImagoColors.BrandBlack.copy(alpha = 0.06f),
    border = ImagoColors.BrandBlack.copy(alpha = 0.10f),
    action = ImagoColors.BrandBlack,
    onAction = ImagoColors.BrandWhite,
    dot = ImagoColors.BrandBlack.copy(alpha = 0.25f),
    featuredTitle = Color(0xFF6B5400),
)
