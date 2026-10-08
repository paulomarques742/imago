package eu.studio742.imago.core.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.ImmichAsset

/**
 * A fixed set of photos, by reference, read from the catalogue once and handed out a page at a
 * time, the newest first. A photo the catalogue no longer has is left out.
 */
internal class CatalogListPagingSource(
    private val database: ImmichRoomDatabase,
    private val ids: List<String>,
) : PagingSource<Int, ImmichAsset>() {
    private var loaded: List<ImmichAsset>? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ImmichAsset> = try {
        val all = loaded ?: read().also { loaded = it }
        val start = params.key ?: 0
        val end = (start + params.loadSize).coerceAtMost(all.size)
        LoadResult.Page(all.subList(start, end), prevKey = null, nextKey = end.takeIf { it < all.size })
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    private suspend fun read(): List<ImmichAsset> = ids.map(AssetReference::parse)
        .groupBy({ it.libraryId }, { it.localId })
        .flatMap { (library, localIds) ->
            // SQLite takes so many arguments at a time.
            localIds.chunked(CHUNK).flatMap { chunk -> database.assetDao().byIds(library, chunk) }
                .map { row -> row.toDomain().copy(id = AssetReference(library, row.id).encode()) }
        }
        .sortedWith(compareByDescending<ImmichAsset> { it.fileCreatedAt }.thenByDescending { it.id })

    override fun getRefreshKey(state: PagingState<Int, ImmichAsset>): Int? = null

    private companion object {
        const val CHUNK = 500
    }
}
