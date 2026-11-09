package eu.studio742.imago.feature.editor

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.studio742.imago.core.interchange.IMAGO_FILE_EXTENSION
import eu.studio742.imago.core.interchange.PHOTO_BOUND_PARTS
import eu.studio742.imago.core.interchange.RecipeExport
import eu.studio742.imago.core.interchange.encodeImagoFile
import eu.studio742.imago.core.interchange.imagoFileName
import eu.studio742.imago.core.interchange.photoBoundParts
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A file handed to the app. [bytes] is null when it could not be read or is over the size limit. */
class RecipeFile(val name: String, val bytes: ByteArray?)

/** A dropped DNG is a photo first; a preset in that format comes in through the import button. */
fun isRecipeFileName(name: String): Boolean = listOf(IMAGO_FILE_EXTENSION, "xmp", "zip").any { name.endsWith(".$it", ignoreCase = true) }

/** Reaching a file, which is all that differs between the two apps. */
internal class RecipeFileActions(
    /** Null where the system has no share menu to hand the file to. */
    val share: ((fileName: String, bytes: ByteArray) -> Unit)?,
    val saveAs: (fileName: String, bytes: ByteArray) -> Unit,
    val open: () -> Unit,
)

@Composable
internal expect fun rememberRecipeFileActions(
    onOpened: (List<RecipeFile>) -> Unit,
    onFailed: () -> Unit,
): RecipeFileActions

internal enum class RecipeDelivery { SHARE, SAVE }

internal class PendingRecipeExport(val recipes: List<SavedRecipe>, val delivery: RecipeDelivery)

@Stable
internal class RecipeExporter(
    private val actions: RecipeFileActions,
    private val scope: CoroutineScope,
    private val packName: () -> String,
    private val onFailed: () -> Unit,
) {
    var asking by mutableStateOf<PendingRecipeExport?>(null)
        private set

    /** Recipes about to be shared with some imported among them, until the person says they may. */
    var askingRights by mutableStateOf<List<SavedRecipe>?>(null)
        private set

    val canShare: Boolean get() = actions.share != null

    /** Goes straight to the file unless something in it is tied to one photo, which has to be asked for. */
    fun start(recipes: List<SavedRecipe>, delivery: RecipeDelivery) {
        if (recipes.isEmpty()) return
        // Only sharing is asked about: a file saved for oneself goes to nobody.
        if (delivery == RecipeDelivery.SHARE && recipes.any { it.origin != null }) {
            askingRights = recipes
        } else {
            choosePartsOrSend(recipes, delivery)
        }
    }

    fun confirmRights() {
        val recipes = askingRights ?: return
        askingRights = null
        choosePartsOrSend(recipes, RecipeDelivery.SHARE)
    }

    private fun choosePartsOrSend(recipes: List<SavedRecipe>, delivery: RecipeDelivery) {
        if (recipes.any { it.photoBoundParts().isNotEmpty() }) {
            asking = PendingRecipeExport(recipes, delivery)
        } else {
            send(recipes, RecipePart.entries.toSet() - PHOTO_BOUND_PARTS, delivery)
        }
    }

    fun confirm(parts: Set<RecipePart>) {
        val pending = asking ?: return
        asking = null
        send(pending.recipes, parts, pending.delivery)
    }

    fun cancel() {
        asking = null
        askingRights = null
    }

    private fun send(recipes: List<SavedRecipe>, chosen: Set<RecipePart>, delivery: RecipeDelivery) {
        val exports = recipes.map { RecipeExport(it, it.appliedParts intersect chosen) }.filter { it.parts.isNotEmpty() }
        if (exports.isEmpty()) return onFailed()
        val fileName = imagoFileName(exports.singleOrNull()?.recipe?.name, packName())
        scope.launch {
            val bytes = withContext(Dispatchers.Default) { encodeImagoFile(exports) }
            when (delivery) {
                RecipeDelivery.SHARE -> actions.share?.invoke(fileName, bytes)
                RecipeDelivery.SAVE -> actions.saveAs(fileName, bytes)
            }
        }
    }
}

@Composable
internal fun rememberRecipeExporter(actions: RecipeFileActions, onFailed: () -> Unit): RecipeExporter {
    val scope = rememberCoroutineScope()
    val packName = stringResource(Res.string.editor_recipe_file_default_name)
    val currentPackName by rememberUpdatedState(packName)
    val currentOnFailed by rememberUpdatedState(onFailed)
    return remember(actions, scope) { RecipeExporter(actions, scope, { currentPackName }, { currentOnFailed() }) }
}

/** The boxes of the parts, with what is tied to one photo left off until the person ticks it. */
@Composable
internal fun RecipeExportDialog(exporter: RecipeExporter) {
    exporter.askingRights?.let { recipes ->
        val imported = recipes.count { it.origin != null }
        AlertDialog(
            onDismissRequest = exporter::cancel,
            title = { Text(stringResource(Res.string.editor_recipe_share_rights_title)) },
            text = { Text(pluralStringResource(Res.plurals.editor_recipe_share_rights, imported, imported)) },
            confirmButton = { TextButton(onClick = exporter::confirmRights) { Text(stringResource(Res.string.editor_recipe_share)) } },
            dismissButton = { TextButton(onClick = exporter::cancel) { Text(stringResource(Res.string.editor_cancel)) } },
        )
        return
    }
    val pending = exporter.asking ?: return
    val initial = remember(pending) { pending.recipes.flatMapTo(mutableSetOf()) { it.appliedParts } - PHOTO_BOUND_PARTS }
    var selected by remember(pending) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = exporter::cancel,
        title = { Text(stringResource(Res.string.editor_recipe_export_parts_title)) },
        text = { RecipePartsPicker(selected, { selected = it }, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { exporter.confirm(selected) }, enabled = selected.isNotEmpty()) {
                Text(
                    stringResource(
                        if (pending.delivery == RecipeDelivery.SHARE) Res.string.editor_recipe_share else Res.string.editor_recipe_save_file,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = exporter::cancel) { Text(stringResource(Res.string.editor_cancel)) } },
    )
}
