package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.EditRecipe
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * What the recipe does to the photo, as a short fingerprint: equal for two recipes that render the same,
 * whatever their dates or the export they point to.
 *
 * The save instant cannot stand for it: sending an edit to Immich writes the recipe again with the new
 * export's id and a new date, and the save to the phone that followed rendered the same photo again.
 */
fun EditRecipe.lookFingerprint(): String {
    val look = copy(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "", derivedAssetId = null)
    return MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(EditRecipe.serializer(), look).toByteArray())
        .joinToString("") { "%02x".format(it) }
}
