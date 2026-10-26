package eu.studio742.imago.feature.library

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** Updating an album's title or count must not discard its photos during a refresh. */
internal fun Flow<AlbumUiModel?>.distinctAlbumContents(): Flow<AlbumUiModel?> = distinctUntilChanged { before, after ->
    before?.id == after?.id && before?.isPerson == after?.isPerson &&
        before?.placeAssetIds == after?.placeAssetIds && before?.isArchive == after?.isArchive
}

/**
 * Each query owns its presenter and cache. Reusing one presenter across queries leaves the old
 * photos in place if the new query's first load fails. Refreshing the same query keeps its cache.
 * The child scope also releases an old query's cache when the selection changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <Query, Item : Any> Flow<Query>.pagingForEachQuery(
    scope: CoroutineScope,
    pages: (Query) -> Flow<PagingData<Item>>,
): StateFlow<Flow<PagingData<Item>>> = transformLatest { query ->
    coroutineScope {
        emit(pages(query).cachedIn(this))
        awaitCancellation()
    }
}.stateIn(
    scope,
    SharingStarted.Lazily,
    flowOf(
        PagingData.empty(
            sourceLoadStates = LoadStates(
                refresh = LoadState.Loading,
                prepend = LoadState.NotLoading(endOfPaginationReached = true),
                append = LoadState.NotLoading(endOfPaginationReached = true),
            ),
        ),
    ),
)
