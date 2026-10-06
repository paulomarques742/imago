package eu.studio742.imago.core.data

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The desktop data layer, given by the app at the top of the window.
 *
 * This is where the shared screens get their dependencies on desktop — Hilt's role on Android.
 */
val LocalDesktopDataGraph = staticCompositionLocalOf<DesktopDataGraph> {
    error("The desktop app has to provide LocalDesktopDataGraph.")
}
