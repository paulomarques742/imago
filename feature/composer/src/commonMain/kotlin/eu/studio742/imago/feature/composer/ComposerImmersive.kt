package eu.studio742.imago.feature.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoMotion
import eu.studio742.imago.core.designsystem.imagoTween

/**
 * The adjustment under the finger: what the focused mode's ruler shows while the panel leaves the
 * scene.
 *
 * It carries the value with it, and not just the key, because whoever draws the ruler — the screen —
 * does not know which adjustment it is: the composer's sliders touch dozens of different properties,
 * and each one only knows itself.
 */
internal data class SliderFocus(
    val key: Any,
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val neutral: Float,
)

/**
 * Which adjustment is in progress, for the whole composer screen.
 *
 * It is a local and not a parameter because this is ambient, like haptic feedback: more than twenty
 * sliders, spread across the element inspector, the background's and the text controls, would all
 * have to drag the same pair of arguments through three levels of composition just to tell the
 * screen "it is me being moved".
 */
internal class ComposerFocusState {
    var focus by mutableStateOf<SliderFocus?>(null)
}

internal val LocalComposerFocus = staticCompositionLocalOf { ComposerFocusState() }

/**
 * What the composer's bars cover of the stage, at the top and bottom.
 *
 * The stage draws edge to edge, under them: a panel that turns transparent has to have a page
 * underneath, or the transparency shows nothing. These insets are what make the page settle between
 * the bars while they are in view.
 */
internal data class ComposerStageInsets(val top: Dp = 0.dp, val bottom: Dp = 0.dp)

/**
 * How much of an inspector's chrome is seen: the headers, the rows of buttons, the chips.
 *
 * Zero while a value is being dragged — only the active adjustment stays over the page — and one in a
 * side column, where the page is beside it and not underneath.
 */
@Composable
internal fun inspectorChromeAlpha(rail: Boolean): Float {
    val focused = LocalComposerFocus.current.focus != null && !rail
    val alpha by animateFloatAsState(
        targetValue = if (focused) 0f else 1f,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "inspectorChrome",
    )
    return alpha
}
