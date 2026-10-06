package eu.studio742.imago.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window's usable width, in three steps.
 *
 * Three, and not "phone or tablet", because what decides the layout is the window and not the
 * device: a tablet in split screen has a phone's width, and a phone held sideways is wider than it
 * is tall. Asking about the device gave the wrong answer in both cases.
 */
enum class ImagoWidthClass {
    /** One column. The layout the app had until now. */
    Compact,

    /** A second, narrow column fits — a list beside the content. */
    Medium,

    /** Content and a tool panel fit side by side, without either covering the other. */
    Expanded,
}

/** The Material 3 thresholds, in dp of window width. */
object ImagoBreakpoints {
    val MediumMin = 600.dp
    val ExpandedMin = 840.dp

    fun classify(width: Dp): ImagoWidthClass = when {
        width < MediumMin -> ImagoWidthClass.Compact
        width < ExpandedMin -> ImagoWidthClass.Medium
        else -> ImagoWidthClass.Expanded
    }
}

/**
 * What each screen needs to know about the space it has.
 *
 * Besides the step, the raw width: some decisions are not about steps but about counting — how many
 * columns fit in the library grid — and those want the number.
 */
data class ImagoWindow(
    val widthClass: ImagoWidthClass,
    val width: Dp,
    val height: Dp,
) {
    /**
     * True when the tools can live in a side column instead of a bottom drawer. It is the question the
     * editor and the composer ask; neither should compare [widthClass] by hand.
     */
    val prefersSidePanel: Boolean get() = widthClass == ImagoWidthClass.Expanded
}

/**
 * The same window, reduced to a pane's width.
 *
 * A screen inside a side pane has to decide by the width it got, not by the device's: the library
 * grid in a 480dp pane wants 480dp columns, not the six a 1280dp window would justify. Whoever opens
 * a pane provides this in [LocalImagoWindow], and everything inside that asks about the window gets
 * the right answer.
 */
fun ImagoWindow.withWidth(width: Dp): ImagoWindow =
    copy(widthClass = ImagoBreakpoints.classify(width), width = width)

/**
 * The current window, provided at the top by [ImagoTheme].
 *
 * The default is a phone: a screen composed outside the theme — a `@Preview`, a test — gets the
 * single-column layout, which is what already existed.
 */
val LocalImagoWindow: ProvidableCompositionLocal<ImagoWindow> = compositionLocalOf {
    ImagoWindow(ImagoWidthClass.Compact, 360.dp, 800.dp)
}

/**
 * Measures the app window — on Android through `Configuration`, on desktop through the window size.
 * It sits in a single place so that switching the source of the measurement touches no screen.
 */
@Composable
internal expect fun currentImagoWindow(): ImagoWindow
