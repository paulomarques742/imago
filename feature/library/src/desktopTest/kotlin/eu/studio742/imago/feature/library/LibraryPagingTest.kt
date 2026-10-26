package eu.studio742.imago.feature.library

import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPagingTest {
    @Test
    fun openingAnAlbumClearsTheTimelineEvenWhenItsFirstRequestFailsAndRetryRecovers() = runTest {
        val query = MutableStateFlow("timeline")
        val albumRequest = CompletableDeferred<Unit>()
        val failure = IOException("Album unavailable")
        var failAlbum = true
        val feeds = query.pagingForEachQuery(backgroundScope) { selected ->
            pager {
                if (selected == "album") {
                    albumRequest.await()
                    if (failAlbum) throw failure
                }
                listOf("$selected-photo")
            }
        }
        val display = display(feeds)
        val timeline = display.awaitItems(listOf("timeline-photo"))

        query.value = "album"
        runCurrent()
        val album = display.current.value!!
        assertNotSame(timeline, album)
        assertTrue(album.snapshot().items.isEmpty())
        albumRequest.complete(Unit)
        val error = album.awaitError()
        assertSame(failure, error.error)
        assertTrue(album.snapshot().items.isEmpty())

        failAlbum = false
        album.retry()
        assertSame(album, display.awaitItems(listOf("album-photo")))
    }

    @Test
    fun aFailedRefreshKeepsOnlyTheCurrentAlbumsPhotosAndReportsItsError() = runTest {
        val query = MutableStateFlow("album")
        val failure = IOException("Refresh unavailable")
        var fail = false
        var items = listOf("album-photo")
        val feeds = query.pagingForEachQuery(backgroundScope) {
            pager { if (fail) throw failure else items }
        }
        val display = display(feeds)
        val album = display.awaitItems(items)
        val sameFeed = feeds.value

        fail = true
        album.refresh()
        assertSame(failure, album.awaitError().error)
        assertSame(sameFeed, feeds.value)
        assertSame(album, display.current.value)
        assertEquals(items, album.snapshot().items)

        fail = false
        items = listOf("album-photo", "new-album-photo")
        album.retry()
        assertSame(album, display.awaitItems(items))
    }

    @Test
    fun changingTheQueryCancelsThePreviousCacheAndItsPendingRequest() = runTest {
        val query = MutableStateFlow("pending-album")
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val feeds = query.pagingForEachQuery(backgroundScope) { selected ->
            if (selected == "pending-album") flow {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            } else pager { listOf("other-album-photo") }
        }
        val display = display(feeds)
        withTimeout(5_000) { entered.await() }
        query.value = "other-album"
        display.awaitItems(listOf("other-album-photo"))
        withTimeout(5_000) { cancelled.await() }
    }

    @Test
    fun updatingAnAlbumsTitleAndCountKeepsItsPresenterAndCachedPhotos() = runTest {
        val album = AlbumUiModel("album", "Trip", "", null, 1, null, null, false, "")
        val query = MutableStateFlow<AlbumUiModel?>(album)
        var requests = 0
        val feeds = query.distinctAlbumContents().pagingForEachQuery(backgroundScope) { selected ->
            pager { requests++; listOf(selected!!.id) }
        }
        val display = display(feeds)
        val first = display.awaitItems(listOf("album"))
        val sameFeed = feeds.value

        query.value = album.copy(name = "Renamed trip", assetCount = 2, endDate = "2026-10-24")
        runCurrent()
        assertSame(sameFeed, feeds.value)
        assertSame(first, display.current.value)
        assertEquals(1, requests)

        query.value = album.copy(id = "other-album")
        assertNotSame(first, display.awaitItems(listOf("other-album")))
    }

    @Test
    fun comingBackToTheSameQueryReusesItsCache() = runTest {
        var requests = 0
        val feeds = MutableStateFlow("album").pagingForEachQuery(backgroundScope) {
            pager { requests++; listOf("album-photo") }
        }
        val first = display(feeds)
        first.awaitItems(listOf("album-photo"))
        first.job.cancelAndJoin()

        val second = display(feeds)
        second.awaitItems(listOf("album-photo"))
        assertEquals(1, requests)
    }

    private fun pager(items: suspend () -> List<String>): Flow<PagingData<String>> =
        Pager(PagingConfig(pageSize = 10, enablePlaceholders = false)) {
            object : PagingSource<Int, String>() {
                override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> = try {
                    LoadResult.Page(items(), prevKey = null, nextKey = null)
                } catch (failure: IOException) {
                    LoadResult.Error(failure)
                }
            }
        }.flow

    private class Display(val current: StateFlow<PagingDataPresenter<String>?>, val job: kotlinx.coroutines.Job) {
        suspend fun awaitItems(items: List<String>): PagingDataPresenter<String> = withTimeout(5_000) {
            current.filterNotNull().flatMapLatest { presenter ->
                presenter.loadStateFlow.filterNotNull().map { presenter to it }
            }.first { (presenter, states) ->
                states.refresh is LoadState.NotLoading && presenter.snapshot().items == items
            }.first
        }
    }

    private fun TestScope.display(feeds: StateFlow<Flow<PagingData<String>>>): Display {
        val current = MutableStateFlow<PagingDataPresenter<String>?>(null)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val job = backgroundScope.launch(dispatcher) {
            feeds.collectLatest { pages ->
                // Paging Compose remembers its presenter by the query's flow, as LibraryHost does.
                val presenter = object : PagingDataPresenter<String>(dispatcher) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<String>) = Unit
                }
                current.value = presenter
                pages.collectLatest(presenter::collectFrom)
            }
        }
        return Display(current, job)
    }

    private suspend fun PagingDataPresenter<String>.awaitError(): LoadState.Error = withTimeout(5_000) {
        loadStateFlow.filterNotNull().first { it.refresh is LoadState.Error }.refresh as LoadState.Error
    }
}
