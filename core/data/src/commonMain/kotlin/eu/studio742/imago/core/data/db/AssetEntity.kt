package eu.studio742.imago.core.data.db

import androidx.room.Entity
import androidx.room.Index
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichAsset

/**
 * The index is the grid's order.
 *
 * With the whole timeline in the catalogue — tens of thousands of rows — every page Paging reads
 * would have to sort the whole table to return a hundred photos, and the same to count how many
 * come before a date. With it, it is an index traversal.
 */
@Entity(
    tableName = "assets",
    primaryKeys = ["libraryKey", "id"],
    indices = [
        Index(value = ["libraryKey", "fileCreatedAt", "id"]),
        // The candidates for "the same photo" are looked up by size and date, without reading files.
        Index(value = ["libraryKey", "sizeBytes", "fileCreatedAt"]),
        // The unified library finds the same photo on the phone and on the server by its name.
        Index(value = ["libraryKey", "originalFileName"]),
    ],
)
data class AssetEntity(
    val libraryKey: String,
    val id: String,
    val checksum: String,
    val originalFileName: String,
    val fileCreatedAt: String,
    val localDateTime: String,
    val width: Long?,
    val height: Long?,
    val isFavorite: Boolean,
    val isEdited: Boolean,
    val hasLocalRecipe: Boolean,
    val type: String,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val folderId: String? = null,
    val folderName: String? = null,
    /** The file size, when the source gives it: today only MediaStore. */
    val sizeBytes: Long? = null,
) {
    fun toDomain() = ImmichAsset(
        id = id,
        checksum = checksum,
        originalFileName = originalFileName,
        fileCreatedAt = fileCreatedAt,
        localDateTime = localDateTime,
        width = width,
        height = height,
        isFavorite = isFavorite,
        isEdited = isEdited,
        hasLocalRecipe = hasLocalRecipe,
        type = runCatching { AssetType.valueOf(type) }.getOrDefault(AssetType.OTHER),
        mimeType = mimeType,
        durationMs = durationMs,
    )

    /**
     * This row, without losing what was already known about the same photo.
     *
     * The month bucket brings the timeline but no name or checksum, and it brings the aspect ratio
     * instead of the real dimensions. A row that already went through the search or the detail knows
     * more than it: that row rules those fields. What comes from the bucket and always rules is what
     * it knows better than anyone — that the photo exists, when it was taken and whether it is a
     * favourite.
     */
    fun keeping(previous: AssetEntity?): AssetEntity {
        if (previous == null) return this
        val describedBefore = previous.originalFileName.isNotBlank()
        return copy(
            checksum = checksum.ifBlank { previous.checksum },
            originalFileName = originalFileName.ifBlank { previous.originalFileName },
            localDateTime = localDateTime.ifBlank { previous.localDateTime },
            width = if (describedBefore) previous.width ?: width else width ?: previous.width,
            height = if (describedBefore) previous.height ?: height else height ?: previous.height,
            isEdited = isEdited || previous.isEdited,
            hasLocalRecipe = hasLocalRecipe || previous.hasLocalRecipe,
            mimeType = mimeType ?: previous.mimeType,
            durationMs = durationMs ?: previous.durationMs,
            sizeBytes = sizeBytes ?: previous.sizeBytes,
        )
    }

    companion object {
        fun fromDomain(libraryKey: String, asset: ImmichAsset) = AssetEntity(
            libraryKey = libraryKey,
            id = asset.id,
            checksum = asset.checksum,
            originalFileName = asset.originalFileName,
            fileCreatedAt = asset.fileCreatedAt,
            localDateTime = asset.localDateTime,
            width = asset.width,
            height = asset.height,
            isFavorite = asset.isFavorite,
            isEdited = asset.isEdited,
            hasLocalRecipe = asset.hasLocalRecipe,
            type = asset.type.name,
            mimeType = asset.mimeType,
            durationMs = asset.durationMs,
        )
    }
}
