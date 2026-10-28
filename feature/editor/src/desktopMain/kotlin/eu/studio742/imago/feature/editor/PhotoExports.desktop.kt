package eu.studio742.imago.feature.editor

import coil3.PlatformContext

// A desktop app lives while its window does; there is nothing to keep alive.
internal actual fun photoExportStarted(context: PlatformContext) = Unit
