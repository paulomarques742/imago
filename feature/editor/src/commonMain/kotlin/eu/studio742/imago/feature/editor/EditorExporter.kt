package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.immichRoomExportFileName
import eu.studio742.imago.feature.editor.resources.*
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

    /**
     * Saves an original as the library served it — a photo, or a video, which the editor never
     * touches — in the same place as the exports.
     *
     * @return the message to show, or null if the user gave up (closed the dialog).
     */
    suspend fun saveOriginalToDevice(file: File, fileName: String, isVideo: Boolean, createdAt: String): UiText?
}

/**
 * Renders [recipe] over [asset]'s original and saves it with the user's photos: the editor's "Save to
 * gallery", for whoever has the recipe but not the editor open.
 */
suspend fun EditorExporter.saveEditedToDevice(asset: EditorAsset, recipe: EditRecipe, onPhase: (UiText) -> Unit): UiText? {
    val jpeg = renderJpeg(asset, recipe, onPhase)
    try {
        return saveToDevice(jpeg, exportFileName(asset), asset.fileCreatedAt)
    } finally {
        jpeg.delete()
    }
}

/** The export's file name; an original without a name gets one in the app language. */
internal suspend fun exportFileName(asset: EditorAsset): String =
    immichRoomExportFileName(asset.fileName, appString(Res.string.editor_export_untitled))
