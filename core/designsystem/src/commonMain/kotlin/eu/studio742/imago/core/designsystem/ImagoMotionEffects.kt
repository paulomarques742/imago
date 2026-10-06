package eu.studio742.imago.core.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * True when the user turned off system animations.
 *
 * Provided at the top by [ImagoTheme] so there is not one read of `Settings.Global` per animation.
 * The value is read once per root composition; changing the setting on Android requires recreating
 * the activity, so there is no need to observe it.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** The system preference: the animation scale on Android, "Show animations" on Windows. */
@Composable
internal expect fun rememberSystemReducedMotion(): Boolean

/**
 * A `tween` that collapses to [snap] with Reduce Motion on. The final state is always the same —
 * only the path disappears.
 */
@Composable
fun <T> imagoTween(durationMillis: Int = ImagoMotion.Fast): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(durationMillis)

@Composable
fun <T> imagoAnimationSpec(durationMillis: Int = ImagoMotion.Default): AnimationSpec<T> =
    imagoTween(durationMillis)
