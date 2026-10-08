package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.feature.library.PhotoRotation
import java.time.Instant

/** A quarter turn clockwise, the crop and its aspect lock with it: what the editor's button does. */
internal fun EditRecipe.rotatedClockwise(): EditRecipe = copy(
    geometry = geometry.copy(
        rotation = (geometry.rotation + 90) % 360,
        cropRect = geometry.cropRect.rotatedClockwise(),
        aspectLock = reciprocalCropAspectId(geometry.aspectLock),
    ),
)

/**
 * The editor's rotation for whoever has no editor open — the detail, the library's selection. The
 * recipe is saved as the editor would, and a server's photo gets the same edit actions the editor
 * mirrors to Immich, so its thumbnails turn too.
 */
class RecipePhotoRotation(
    private val recipes: RecipeRepository,
    immichApi: ImmichApi,
    configuration: ConfigurationRepository,
) : PhotoRotation {
    private val mirror = ImmichGeometryMirror(immichApi, configuration)

    override suspend fun rotateClockwise(assetId: String, checksum: String): EditRecipe {
        val now = Instant.now().toString()
        val current = recipes.get(assetId) ?: EditRecipe(assetId = assetId, originalChecksum = checksum, createdAt = now, updatedAt = now)
        val rotated = current.rotatedClockwise().copy(updatedAt = now)
        recipes.save(rotated)
        mirror.mirror(rotated)
        return rotated
    }
}
