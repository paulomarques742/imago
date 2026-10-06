package eu.studio742.imago.core.data.db

import androidx.room.Embedded
import androidx.room.Entity

/**
 * A photo's recipe.
 *
 * [contentSha1] is the photo's identity outside this device: null while the hash is not computed,
 * and the recipe does not go up while it is. [hintsJson] carries the hints another device uses to
 * recognise the same device photo without reading files.
 */
@Entity(tableName = "recipes", primaryKeys = ["libraryKey", "assetId"])
data class RecipeEntity(
    val libraryKey: String,
    val assetId: String,
    val recipeJson: String,
    val updatedAt: String,
    val contentSha1: String? = null,
    val hintsJson: String? = null,
    @Embedded val sync: SyncState = SyncState(),
)
