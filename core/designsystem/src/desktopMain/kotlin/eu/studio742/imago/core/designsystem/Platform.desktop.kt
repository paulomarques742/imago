package eu.studio742.imago.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * "Show animations in Windows" turned off. AWT exposes it as a desktop property; outside Windows, or
 * if the property does not exist, animations stay on.
 */
@Composable
internal actual fun rememberSystemReducedMotion(): Boolean = remember {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.anim.animationsEnabled") == false
    }.getOrDefault(false)
}

/** The size of the window the composition lives in — resizing the window recomposes with the new step. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun currentImagoWindow(): ImagoWindow {
    val size = LocalWindowInfo.current.containerSize
    val (width, height) = with(LocalDensity.current) { size.width.toDp() to size.height.toDp() }
    return ImagoWindow(ImagoBreakpoints.classify(width), width, height)
}
