package eu.studio742.imago.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Visual tokens shared by every IMAGO surface. */
object ImagoColors {
    val Background = Color(0xFF0B0D0F)
    val Surface = Color(0xFF111417)
    val SurfaceElevated = Color(0xFF171B1F)
    val Glass = Color(0xF2111417)

    /**
     * The background of the navigation and tool bars.
     *
     * One step above [SurfaceElevated], on purpose: these bars sit against the work area, which is
     * black — the photo in the editor, the stage in the composer, the grid in the library. Painted
     * black as they were, there was nowhere for the bar to end and the image to begin.
     */
    val NavBar = Color(0xFF1D2227)
    val BrandBlack = Color(0xFF0D0D0F)
    val Charcoal = Color(0xFF1A1A1D)
    val Graphite = Color(0xFF2A2A2E)
    val Ivory = Color(0xFFE6E6E3)
    val BrandWhite = Color(0xFFF5F5F2)
    val Gold = Color(0xFFD4AF37)
    val TextPrimary = Color.White.copy(alpha = 0.96f)
    val TextSecondary = Color.White.copy(alpha = 0.68f)
    val TextTertiary = Color.White.copy(alpha = 0.46f)
    val TextDisabled = Color.White.copy(alpha = 0.28f)
    val BorderSubtle = Color.White.copy(alpha = 0.08f)
    val BorderVisible = Color.White.copy(alpha = 0.14f)
    val BorderStrong = Color.White.copy(alpha = 0.24f)
    val Scrim = Color.Black.copy(alpha = 0.48f)
    val Error = Color(0xFFFFB4AB)

    /**
     * The red of actions that destroy work — "Delete all" in the history, "Delete" in the detail.
     * Distinct from [Error], which describes a state the app suffered; this is a choice the user is
     * about to make.
     */
    val Danger = Color(0xFFFF4D4F)
}

object ImagoSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp
}

object ImagoRadii {
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Panel = 20.dp
    val Pill = 999.dp
}

/**
 * Recurring dimensions. An icon may be visually small, but the area that responds to the finger
 * never goes below [TouchTarget] — the minimum the design system and TalkBack require.
 */
object ImagoSizes {
    val TouchTarget = 44.dp
    val IconSmall = 16.dp
    val IconDefault = 20.dp
    val IconLarge = 24.dp
    val IconHero = 28.dp

    /** The edit panel takes a fraction of the screen, never a fixed height. */
    const val PanelHeightFraction = 0.32f
    val PanelHeightMin = 220.dp
    val PanelHeightMax = 340.dp

    /**
     * Colour grading is the other exception, in the opposite direction to crop: the control is a
     * wheel, and a wheel cut off by the panel's edge does not say where the angle is. It gets almost
     * half the window, which is what keeps the whole wheel in view with the caption and the first
     * slider — and while dragging the panel fades out anyway.
     */
    const val WheelPanelHeightFraction = 0.44f
    val WheelPanelHeightMin = 300.dp
    val WheelPanelHeightMax = 400.dp

    /**
     * Crop is the exception: its drawer only has the row of aspect ratios.
     *
     * A fraction of the screen there would be height stolen from what is being framed — and framing
     * is done looking at the photo, not at the drawer.
     */
    val CropPanelHeight = 112.dp
}

/** The design system's reference durations. */
object ImagoMotion {
    const val Instant = 120
    const val Fast = 180
    const val Default = 260
    const val Slow = 400
}

private val ImagoTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

private val ImagoColorScheme = darkColorScheme(
    primary = ImagoColors.Ivory,
    onPrimary = ImagoColors.BrandBlack,
    primaryContainer = ImagoColors.Graphite,
    onPrimaryContainer = ImagoColors.Ivory,
    secondary = ImagoColors.TextSecondary,
    onSecondary = ImagoColors.BrandBlack,
    background = ImagoColors.Background,
    onBackground = ImagoColors.TextPrimary,
    surface = ImagoColors.Surface,
    onSurface = ImagoColors.TextPrimary,
    surfaceVariant = ImagoColors.SurfaceElevated,
    onSurfaceVariant = ImagoColors.TextSecondary,
    outline = ImagoColors.BorderVisible,
    outlineVariant = ImagoColors.BorderSubtle,
    error = ImagoColors.Error,
    scrim = ImagoColors.Scrim,
)

@Composable
fun ImagoTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalReducedMotion provides rememberSystemReducedMotion(),
        LocalImagoWindow provides currentImagoWindow(),
    ) {
        MaterialTheme(
            colorScheme = ImagoColorScheme,
            typography = ImagoTypography,
            content = content,
        )
    }
}

/** Kept as a source-compatible alias for modules not yet renamed internally. */
@Composable
fun ImmichRoomTheme(content: @Composable () -> Unit) = ImagoTheme(content)
