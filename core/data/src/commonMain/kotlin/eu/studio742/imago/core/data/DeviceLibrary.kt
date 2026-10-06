package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserText
import kotlinx.coroutines.flow.StateFlow

/**
 * This device's library — the gallery on Android, the chosen folders on desktop.
 *
 * A type apart from [LibraryRepository] only so injection can tell it from the routed library,
 * which is one too.
 */
interface DeviceLibrary : LibraryRepository {
    /** Goes up whenever access changes — permissions on Android, folders on desktop. */
    val accessRevision: StateFlow<Int>

    /** A line saying what the app has access to. */
    fun accessSummary(): UserText

    /** Looks at the access and the catalogue again, after the user changed it outside the app. */
    fun refreshAccess()
}
