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
import androidx.compose.ui.graphics.ImageBitmap
import eu.studio742.imago.core.interchange.IMAGO_FILE_EXTENSION
import eu.studio742.imago.core.interchange.PHOTO_BOUND_PARTS
import eu.studio742.imago.core.interchange.RecipeExport
import eu.studio742.imago.core.interchange.RecipeLinkError
import eu.studio742.imago.core.interchange.RecipeLinkException
import eu.studio742.imago.core.interchange.RecipeLinkService
import eu.studio742.imago.core.interchange.XmpExport
import eu.studio742.imago.core.interchange.XmpLeftOut
import eu.studio742.imago.core.interchange.encodeXmpPreset
import eu.studio742.imago.core.interchange.encodeImagoFile
import eu.studio742.imago.core.interchange.imagoFileName
import eu.studio742.imago.core.render.CubeExport
import eu.studio742.imago.core.render.CubeLeftOut
import eu.studio742.imago.core.render.encodeCubeFile
import eu.studio742.imago.core.interchange.photoBoundParts
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A file handed to the app. [bytes] is null when it could not be read or is over the size limit, and
 * [linkError] says why when it was a link that could not be opened.
 */
class RecipeFile(val name: String, val bytes: ByteArray?, val linkError: RecipeLinkError? = null)

/** What a link opens to, in the same shape as a file received from another app. */
suspend fun readRecipeLink(links: RecipeLinkService, linkId: String): RecipeFile = try {
    RecipeFile(LINK_FILE_NAME, links.open(linkId))
} catch (failure: RecipeLinkException) {
    RecipeFile(LINK_FILE_NAME, null, failure.error)
}

private const val LINK_FILE_NAME = "IMAGO.$IMAGO_FILE_EXTENSION"

/** A dropped DNG is a photo first; a preset in that format comes in through the import button. */
fun isRecipeFileName(name: String): Boolean = listOf(IMAGO_FILE_EXTENSION, "xmp", "lrtemplate", "cube", "zip").any { name.endsWith(".$it", ignoreCase = true) }

/** Reaching a file, which is all that differs between the two apps. */
internal class RecipeFileActions(
    /** Null where the system has no share menu to hand the file to. */
    val share: ((fileName: String, bytes: ByteArray) -> Unit)?,
    val saveAs: (fileName: String, bytes: ByteArray) -> Unit,
    val open: () -> Unit,
    /** Null where the system has no share menu to hand the text to. */
    val shareText: ((String) -> Unit)? = null,
    val shareImage: ((fileName: String, bytes: ByteArray) -> Unit)? = null,
)

internal expect fun ImageBitmap.encodeToPng(): ByteArray

/** As JPEG at [quality] (0–100), with no metadata: only the pixels are written. */
internal expect fun ImageBitmap.encodeToJpeg(quality: Int): ByteArray

@Composable
internal expect fun rememberRecipeFileActions(
    onOpened: (List<RecipeFile>) -> Unit,
    onFailed: () -> Unit,
): RecipeFileActions

internal enum class RecipeDelivery { SHARE, SAVE, LINK }

internal class PendingRecipeExport(val recipes: List<SavedRecipe>, val delivery: RecipeDelivery)

@Stable
internal class RecipeExporter(
    private val actions: RecipeFileActions,
    private val scope: CoroutineScope,
    private val packName: () -> String,
    private val onFailed: () -> Unit,
    /** The file for a link: what is published goes through the same choices as what is sent. */
    private val onLink: (ByteArray) -> Unit = {},
) {
    var asking by mutableStateOf<PendingRecipeExport?>(null)
        private set

    /** Recipes about to be shared with some imported among them, until the person says they may. */
    var askingRights by mutableStateOf<List<SavedRecipe>?>(null)
        private set
    private var rightsDelivery = RecipeDelivery.SHARE

    /** A preset about to be saved without something the recipe has, until the person agrees. */
    var askingXmp by mutableStateOf<XmpExport?>(null)
        private set

    /** A LUT about to be saved without something the recipe has, until the person agrees. */
    var askingCube by mutableStateOf<CubeExport?>(null)
        private set

    val canShare: Boolean get() = actions.share != null

    /** The colour of one recipe as a `.cube` table, made off the main thread: it runs the pipeline over 36 000 colours. */
    fun startCube(recipe: SavedRecipe) {
        scope.launch {
            val export = withContext(Dispatchers.Default) { encodeCubeFile(recipe, packName()) }
            if (export.leftOut.isEmpty()) actions.saveAs(export.fileName, export.bytes) else askingCube = export
        }
    }

    fun confirmCube() {
        val export = askingCube ?: return
        askingCube = null
        actions.saveAs(export.fileName, export.bytes)
    }

    /** One recipe is one preset file. Saved and not shared: it is for going on in another editor. */
    fun startXmp(recipe: SavedRecipe) {
        val export = encodeXmpPreset(recipe, packName())
        if (export.leftOut.isEmpty()) actions.saveAs(export.fileName, export.bytes) else askingXmp = export
    }

    fun confirmXmp() {
        val export = askingXmp ?: return
        askingXmp = null
        actions.saveAs(export.fileName, export.bytes)
    }

    /** Goes straight to the file unless something in it is tied to one photo, which has to be asked for. */
    fun start(recipes: List<SavedRecipe>, delivery: RecipeDelivery) {
        if (recipes.isEmpty()) return
        // Only sharing is asked about: a file saved for oneself goes to nobody.
        if (delivery != RecipeDelivery.SAVE && recipes.any { it.origin != null }) {
            rightsDelivery = delivery
            askingRights = recipes
        } else {
            choosePartsOrSend(recipes, delivery)
        }
    }

    fun confirmRights() {
        val recipes = askingRights ?: return
        askingRights = null
        choosePartsOrSend(recipes, rightsDelivery)
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
        askingXmp = null
        askingCube = null
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
                RecipeDelivery.LINK -> onLink(bytes)
            }
        }
    }
}

@Composable
internal fun rememberRecipeExporter(
    actions: RecipeFileActions,
    onFailed: () -> Unit,
    onLink: (ByteArray) -> Unit = {},
): RecipeExporter {
    val scope = rememberCoroutineScope()
    val packName = stringResource(Res.string.editor_recipe_file_default_name)
    val currentPackName by rememberUpdatedState(packName)
    val currentOnFailed by rememberUpdatedState(onFailed)
    val currentOnLink by rememberUpdatedState(onLink)
    return remember(actions, scope) { RecipeExporter(actions, scope, { currentPackName }, { currentOnFailed() }, { currentOnLink(it) }) }
}

private fun xmpLeftOutLabel(item: XmpLeftOut): StringResource = when (item) {
    XmpLeftOut.MASKS -> Res.string.editor_recipe_gap_masks
    XmpLeftOut.REMOVE -> Res.string.editor_tool_remove
    XmpLeftOut.GEOMETRY -> Res.string.editor_recipe_gap_geometry
    XmpLeftOut.DEFRINGE_RANGE -> Res.string.editor_recipe_gap_defringe_hue
    XmpLeftOut.LUT -> Res.string.editor_recipe_gap_lut
}

private fun cubeLeftOutLabel(item: CubeLeftOut): StringResource = when (item) {
    CubeLeftOut.DETAIL_AND_EFFECTS -> Res.string.editor_recipe_gap_detail_effects
    CubeLeftOut.HIGHLIGHTS_AND_SHADOWS -> Res.string.editor_recipe_gap_highlights_shadows
    CubeLeftOut.MASKS -> Res.string.editor_recipe_gap_masks
    CubeLeftOut.REMOVE -> Res.string.editor_tool_remove
    CubeLeftOut.GEOMETRY -> Res.string.editor_recipe_gap_geometry
}

/** The boxes of the parts, with what is tied to one photo left off until the person ticks it. */
@Composable
internal fun RecipeExportDialog(exporter: RecipeExporter) {
    exporter.askingCube?.let { export ->
        val names = export.leftOut.map { stringResource(cubeLeftOutLabel(it)) }.joinToString()
        AlertDialog(
            onDismissRequest = exporter::cancel,
            title = { Text(stringResource(Res.string.editor_recipe_cube_left_out_title)) },
            text = { Text(stringResource(Res.string.editor_recipe_xmp_left_out, names)) },
            confirmButton = { TextButton(onClick = exporter::confirmCube) { Text(stringResource(Res.string.editor_recipe_save_file)) } },
            dismissButton = { TextButton(onClick = exporter::cancel) { Text(stringResource(Res.string.editor_cancel)) } },
        )
        return
    }
    exporter.askingXmp?.let { export ->
        val names = export.leftOut.map { stringResource(xmpLeftOutLabel(it)) }.joinToString()
        AlertDialog(
            onDismissRequest = exporter::cancel,
            title = { Text(stringResource(Res.string.editor_recipe_xmp_left_out_title)) },
            text = { Text(stringResource(Res.string.editor_recipe_xmp_left_out, names)) },
            confirmButton = { TextButton(onClick = exporter::confirmXmp) { Text(stringResource(Res.string.editor_recipe_save_file)) } },
            dismissButton = { TextButton(onClick = exporter::cancel) { Text(stringResource(Res.string.editor_cancel)) } },
        )
        return
    }
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
                        if (pending.delivery == RecipeDelivery.SAVE) Res.string.editor_recipe_save_file else Res.string.editor_recipe_share,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = exporter::cancel) { Text(stringResource(Res.string.editor_cancel)) } },
    )
}
