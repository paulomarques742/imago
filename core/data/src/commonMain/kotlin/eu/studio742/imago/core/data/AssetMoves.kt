package eu.studio742.imago.core.data

import eu.studio742.imago.core.data.db.ImmichRoomDatabase

/**
 * What this app keeps about a photo follows it to its new id: the recipe, the content hash that
 * syncs it, the versions that lost a conflict. On a computer a file's id is its path, which changes
 * when the file goes to another folder; the photo is still the same.
 */
suspend fun ImmichRoomDatabase.moveAssetRecords(libraryKey: String, moves: Map<String, String>) {
    if (moves.isEmpty()) return
    withTransaction {
        moves.forEach { (from, to) ->
            recipeDao().moveAsset(libraryKey, from, to)
            contentHashDao().moveAsset(libraryKey, from, to)
            recipeConflictDao().moveAsset(libraryKey, from, to)
            candidateRejectionDao().moveAsset(libraryKey, from, to)
        }
    }
}
