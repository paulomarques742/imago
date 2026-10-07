package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.FolderTransfer
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.UserText
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * How photos go into one of this device's albums when the person asked not to be asked again;
     * null asks every time. A library without folder albums always asks, and never is.
     */
    val rememberedTransfer: StateFlow<FolderTransfer?> get() = NeverRemembered

    fun rememberTransfer(transfer: FolderTransfer?) = Unit

    /** The files other apps open with IMAGO, as a library; null where nothing opens it. */
    val openedFiles: LibraryRepository? get() = null

    /**
     * What another app asked IMAGO to show, ready for the detail: a photo of this device's gallery
     * with its album around it (only it when [neighbours] is false), or the files themselves. The
     * ids are already library references. Null when none of them can be read.
     */
    suspend fun openFromOtherApp(uris: List<String>, neighbours: Boolean): OpenedView? = null
}

/** The photos to show and which one is first. */
data class OpenedView(val assets: List<ImmichAsset>, val index: Int)

private val NeverRemembered: StateFlow<FolderTransfer?> = MutableStateFlow(null)
