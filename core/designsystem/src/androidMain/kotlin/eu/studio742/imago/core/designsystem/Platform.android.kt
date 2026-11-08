package eu.studio742.imago.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The system animation scale at zero. Read once per root composition; changing the setting on
 * Android requires recreating the activity, so there is no need to observe it.
 */
@Composable
internal actual fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Set here and not by `enableEdgeToEdge()`, which reads the system and not what the person chose. */
@Composable
internal actual fun SystemBarsFollow(palette: ImagoUiPalette) {
    val context = LocalContext.current
    SideEffect {
        val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        context.activity()?.window?.insetsController?.setSystemBarsAppearance(if (palette.isLight) bars else 0, bars)
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * `screenWidthDp` is the width of the **app window**, not of the physical screen: in split screen
 * or windowed mode it already comes reduced, which is exactly what is wanted.
 */
@Composable
internal actual fun currentImagoWindow(): ImagoWindow {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    return ImagoWindow(
        widthClass = ImagoBreakpoints.classify(width),
        width = width,
        height = configuration.screenHeightDp.dp,
    )
}
