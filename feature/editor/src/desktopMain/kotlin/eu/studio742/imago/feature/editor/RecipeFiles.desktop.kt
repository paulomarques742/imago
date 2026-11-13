package eu.studio742.imago.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import eu.studio742.imago.core.designsystem.NativeDialogs
import eu.studio742.imago.core.interchange.RECIPE_FILE_EXTENSIONS
import eu.studio742.imago.core.interchange.RECIPE_FILE_MAX_BYTES
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

/** A file from a dialog or dropped on the window. */
fun readRecipeFile(path: Path): RecipeFile = RecipeFile(
    name = path.fileName.toString(),
    bytes = runCatching { path.takeIf { Files.size(it) <= RECIPE_FILE_MAX_BYTES }?.let(Files::readAllBytes) }.getOrNull(),
)

internal actual fun ImageBitmap.encodeToPng(): ByteArray =
    checkNotNull(Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)).bytes

internal actual fun ImageBitmap.encodeToJpeg(quality: Int): ByteArray =
    checkNotNull(Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)).bytes

@Composable
internal actual fun rememberRecipeFileActions(
    onOpened: (List<RecipeFile>) -> Unit,
    onFailed: () -> Unit,
): RecipeFileActions {
    val scope = rememberCoroutineScope()
    val opened by rememberUpdatedState(onOpened)
    val failed by rememberUpdatedState(onFailed)
    val typeName = stringResource(Res.string.editor_recipe_file_default_name)
    return remember(scope, typeName) {
        val filters = listOf(NativeDialogs.Filter(typeName, RECIPE_FILE_EXTENSIONS))
        // Saving offers the one type being written, so that the dialog cannot change the extension to another.
        fun saving(fileName: String) = listOf(NativeDialogs.Filter(typeName, listOf(fileName.substringAfterLast('.'))))
        RecipeFileActions(
            // Windows has no share menu to hand a file to; the file itself is what gets sent.
            share = null,
            saveAs = { fileName, bytes ->
                scope.launch {
                    runCatching {
                        NativeDialogs.saveFile(fileName, saving(fileName))?.let { path ->
                            withContext(Dispatchers.IO) { Files.write(path, bytes) }
                        }
                    }.onFailure { failed() }
                }
            },
            open = {
                scope.launch {
                    runCatching { NativeDialogs.openFile(filters) }
                        .onSuccess { path ->
                            if (path != null) opened(listOf(withContext(Dispatchers.IO) { readRecipeFile(path) }))
                        }
                        .onFailure { failed() }
                }
            },
        )
    }
}
