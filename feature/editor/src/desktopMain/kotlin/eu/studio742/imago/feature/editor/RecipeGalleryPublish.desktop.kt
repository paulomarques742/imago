package eu.studio742.imago.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import eu.studio742.imago.core.designsystem.NativeDialogs
import eu.studio742.imago.core.interchange.RecipeGalleryLimits
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.render.decodePixels
import eu.studio742.imago.core.render.toImageBitmap
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.nio.file.Files

/** The photographs a desktop library holds; RAW files go through the editor, not here. */
private val PHOTO_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "heic", "heif")

/** Above this a file is not a photo to send to a gallery, and reading it whole would be wasteful. */
private const val PHOTO_FILE_MAX_BYTES = 80L * 1024 * 1024

@Composable
internal actual fun rememberGalleryPhotoPicker(onPicked: (ImageBitmap?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val typeName = stringResource(Res.string.editor_gallery_source_own)
    return {
        scope.launch {
            val path = NativeDialogs.openFile(listOf(NativeDialogs.Filter(typeName, PHOTO_EXTENSIONS))) ?: return@launch
            val photo = try {
                withContext(Dispatchers.IO) {
                    require(Files.size(path) <= PHOTO_FILE_MAX_BYTES)
                    // Skia applies the orientation; drawing the pixels again keeps none of the file's metadata.
                    checkNotNull(decodePixels(Files.readAllBytes(path))).toImageBitmap().fittedTo(RecipeGalleryLimits.PHOTO_SIDE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalDiagnostics.failure(DiagnosticArea.SYNC, error, DiagnosticOperation.GALLERY)
                null
            }
            picked(photo)
        }
    }
}
