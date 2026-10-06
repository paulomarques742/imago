package eu.studio742.imago.core.data.db

import androidx.room.Embedded
import androidx.room.Entity

@Entity(tableName = "saved_recipes", primaryKeys = ["libraryKey", "id"])
data class SavedRecipeEntity(
    val libraryKey: String,
    val id: String,
    val name: String,
    val collection: String,
    val recipeJson: String,
    val createdAt: String,
    val updatedAt: String,
    val isFavorite: Boolean = false,
    val usedAt: String? = null,
    @Embedded val sync: SyncState = SyncState(),
)
