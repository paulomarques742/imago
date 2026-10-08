package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.OPENED_LIBRARY_ID
import eu.studio742.imago.core.render.immichEdits
import kotlinx.coroutines.CancellationException

/**
 * Keeps Immich's own edits of a photo equal to the geometry of its recipe.
 *
 * Immich applies crop, rotation and mirror itself when it makes thumbnails and previews, so a photo
 * cropped in IMAGO looks cropped in Immich's web interface and in its official app — without an
 * export. Tone and colour have no such place on the server and only reach it by exporting.
 *
 * The local recipe wins: whatever the server holds that differs from it is rewritten, and the
 * difference is logged. It only runs for a photo that has a recipe here, so opening a photo cropped in
 * Immich's web interface and never touched in IMAGO does not undo that crop.
 *
 * It never fails the editor. A key without the edit permissions, a photo Immich will not edit (a
 * video, a live photo, a panorama) or a server out of reach leave the recipe as it is, and the next
 * time the photo is opened it tries again.
 */
internal class ImmichGeometryMirror(
    private val immichApi: ImmichApi,
    private val configuration: ConfigurationRepository,
    private val log: (String) -> Unit = { System.err.println("[ImmichRoom/geometry] $it") },
) {
    suspend fun mirror(recipe: EditRecipe) {
        val reference = runCatching { AssetReference.parse(recipe.assetId) }.getOrNull() ?: return
        if (reference.isRemote || reference.libraryId == DEVICE_LIBRARY_ID || reference.libraryId == OPENED_LIBRARY_ID) return
        try {
            val connection = configuration.source(reference.libraryId).connection()
            val detail = immichApi.getAssetDetail(connection, reference.localId)
            if (detail.asset.type != AssetType.IMAGE) return
            val size = detail.exif.serverImageSize()
            if (size == null) {
                log("skipped ${reference.localId}: Immich does not know its dimensions")
                return
            }
            val wanted = recipe.immichEdits(size)
            val current = immichApi.getAssetEdits(connection, reference.localId)
            if (current == wanted) return
            if (current.isNotEmpty()) log("divergence on ${reference.localId}: server had $current, recipe wants $wanted")
            immichApi.replaceAssetEdits(connection, reference.localId, wanted)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("could not mirror ${reference.localId}: ${error::class.simpleName} ${error.message.orEmpty().take(200)}")
        }
    }
}
