package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.model.EditRecipe
import java.io.File

/**
 * The export of the edited photo, on each platform.
 *
 * Uploading to Immich is the same on both and stays in the ViewModel; what changes is how the original
 * is read at full resolution and where the "on this device" copy goes — Android's gallery, a file
 * chosen on desktop.
 */
interface EditorExporter {
    /** The photo with the recipe, at full resolution, in a temporary JPEG the caller deletes. */
    suspend fun renderJpeg(asset: EditorAsset, recipe: EditRecipe, onPhase: (UiText) -> Unit): File

    /**
     * Saves the JPEG with the user's photos.
     *
     * @return the message to show, or null if the user gave up (closed the dialog).
     */
    suspend fun saveToDevice(jpeg: File, fileName: String, createdAt: String): UiText?
}
