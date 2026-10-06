package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.InsertableMedia
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.feature.library.AssetUiModel

/**
 * A photo or video on its way to a composition.
 *
 * It is the contract between whoever chooses and the composer: enough to build the media reference
 * without forcing the caller to know the compositions' model.
 */
data class ComposerMedia(
    val id: String,
    val checksum: String,
    val fileName: String,
    val mimeType: String? = null,
    val width: Long? = null,
    val height: Long? = null,
    val durationMs: Long? = null,
    val isVideo: Boolean = false,
)

/** What the library shows, in the format the composition receives. */
fun AssetUiModel.toComposerMedia() = ComposerMedia(
    id = id,
    checksum = checksum,
    fileName = fileName,
    width = width,
    height = height,
    durationMs = durationMs,
    isVideo = isVideo,
)

/**
 * Every photo carries with it the recipe it was left with in the editor.
 *
 * Without it the composition would show the original Immich serves, and not the version the library
 * just showed as a thumbnail.
 */
internal suspend fun List<ComposerMedia>.toInsertable(recipes: RecipeRepository): List<InsertableMedia> =
    map { item ->
        InsertableMedia(
            reference = item.toReference(),
            isVideo = item.isVideo,
            recipe = if (item.isVideo) null else runCatching { recipes.get(item.id) }.getOrNull(),
        )
    }

/** A mesma media, como a composicao a guarda. */
internal fun ComposerMedia.toReference() = MediaReference(
    assetId = id,
    checksum = checksum,
    fileName = fileName,
    mimeType = mimeType,
    width = width,
    height = height,
    durationMs = durationMs,
)
