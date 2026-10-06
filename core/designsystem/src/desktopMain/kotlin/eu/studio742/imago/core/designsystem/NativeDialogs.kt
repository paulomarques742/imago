package eu.studio742.imago.core.designsystem

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NFDFilterItem
import org.lwjgl.util.nfd.NativeFileDialog.NFD_CANCEL
import org.lwjgl.util.nfd.NativeFileDialog.NFD_FreePath
import org.lwjgl.util.nfd.NativeFileDialog.NFD_GetError
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Init
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OpenDialog
import org.lwjgl.util.nfd.NativeFileDialog.NFD_PickFolder
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit
import org.lwjgl.util.nfd.NativeFileDialog.NFD_SaveDialog
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * The Windows file and folder dialogs — the same as Explorer's, and not Swing's JFileChooser, which
 * looked like it came from another era.
 *
 * They run on a thread of their own: the dialog blocks its caller, and the COM Windows uses
 * underneath wants to be initialised and shut down on the same thread. The interface stays free to
 * draw behind it.
 */
object NativeDialogs {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "imago-dialogs").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    /** A file family in the dialog's filter: the name and the extensions, without the dot. */
    data class Filter(val name: String, val extensions: List<String>)

    suspend fun pickFolder(start: Path? = null): Path? = dialog { stack, out ->
        NFD_PickFolder(out, start?.toString())
    }

    suspend fun openFile(filters: List<Filter> = emptyList(), start: Path? = null): Path? = dialog { stack, out ->
        NFD_OpenDialog(out, filterItems(stack, filters), start?.toString())
    }

    suspend fun saveFile(suggestedName: String, filters: List<Filter> = emptyList(), start: Path? = null): Path? = dialog { stack, out ->
        NFD_SaveDialog(out, filterItems(stack, filters), start?.toString(), suggestedName)
    }

    private suspend fun dialog(show: (MemoryStack, org.lwjgl.PointerBuffer) -> Int): Path? = withContext(thread) {
        check(NFD_Init() == NFD_OKAY) { "Could not open the Windows dialog: ${NFD_GetError()}" }
        try {
            MemoryStack.stackPush().use { stack ->
                val out = stack.mallocPointer(1)
                when (show(stack, out)) {
                    NFD_OKAY -> try { Path.of(out.getStringUTF8(0)) } finally { NFD_FreePath(out.get(0)) }
                    NFD_CANCEL -> null
                    else -> error("The Windows dialog failed: ${NFD_GetError()}")
                }
            }
        } finally { NFD_Quit() }
    }

    private fun filterItems(stack: MemoryStack, filters: List<Filter>): NFDFilterItem.Buffer? {
        if (filters.isEmpty()) return null
        val items = NFDFilterItem.malloc(filters.size, stack)
        filters.forEachIndexed { index, filter ->
            items.get(index).name(stack.UTF8(filter.name)).spec(stack.UTF8(filter.extensions.joinToString(",")))
        }
        return items
    }
}
