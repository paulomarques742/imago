package eu.studio742.imago.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.RemoteMediator.InitializeAction
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.CatalogMonthEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.RemoteKeyEntity
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions
import eu.studio742.imago.core.immich.requirePermission
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichPerson
import eu.studio742.imago.core.model.MapContents
import eu.studio742.imago.core.model.DayMemory
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.RECENT_FILTER_DAYS
import eu.studio742.imago.core.model.isImmichRoomExport
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomLibraryRepository @Inject constructor(
    private val database: ImmichRoomDatabase,
    private val configuration: ConfigurationRepository,
    private val api: ImmichApi,
    private val derivedAssets: DerivedAssetRepository,
) : LibraryRepository {

    /**
     * The library slices already brought from the server in this launch.
     *
     * It tells apart the two things Paging treats as one: opening the app, when we really want to
     * ask Immich what is new, and rebuilding the grid because a chip or a month was tapped or the
     * screen was rotated, when nothing already scrolled through should be lost. The repository is a
     * `@Singleton`, so this lives as long as the process.
     */
    private val refreshedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @OptIn(ExperimentalPagingApi::class)
    override fun assets(
        filter: LibraryFilter,
        month: String?,
        albumId: String?,
        query: String?,
    ): Flow<PagingData<ImmichAsset>> {
        val connection = requireConnection()
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        val trimmedQuery = query?.trim()?.takeIf(String::isNotEmpty)
        if (albumId != null) {
            return Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    // By default Paging would ask the local source for `pageSize * 3` on the first
                    // load, but the mediator only brings one page from the server at a time: the
                    // difference was settled with two immediate APPENDs right after launch.
                    initialLoadSize = PAGE_SIZE,
                    prefetchDistance = 30,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = {
                    AlbumAssetPagingSource(
                        database, api, derivedAssets, connection, libraryKey, albumId, filter, trimmedQuery,
                    )
                },
            ).flow
        }
        val monthStart = month?.let { LocalDate.parse(it).withDayOfMonth(1) }
        // "Recent" is a sliding window, not a month; when both are active the month wins, as in the
        // request to the server.
        val recentStart = LocalDate.now().minusDays(RECENT_FILTER_DAYS)
            .takeIf { filter == LibraryFilter.RECENT && monthStart == null }
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = 30,
                // The grid has the size of the catalogue, not of the pages read so far. That is what
                // gives fast scrolling a destination anywhere in the timeline — without it,
                // `scrollToItem` has nowhere to go beyond what was loaded — and the price is an
                // empty tile for the instant between getting there and the page leaving Room.
                enablePlaceholders = true,
                // A jump larger than this restarts reading at the new place instead of getting there
                // page by page. Without this threshold, dragging the handle to 2011 made Paging load
                // everything on the way — dozens of pages and their memory — to show the thirty
                // photos that fit on the screen.
                jumpThreshold = PAGE_SIZE * 3,
            ),
            remoteMediator = AssetRemoteMediator(
                database, api, derivedAssets, connection, libraryKey, filter, month, trimmedQuery,
                refreshedThisSession,
            ),
            pagingSourceFactory = {
                database.assetDao().pagingSource(
                    libraryKey = libraryKey,
                    favoritesOnly = filter == LibraryFilter.FAVORITES,
                    editedOnly = filter == LibraryFilter.EDITED,
                    monthStart = (monthStart ?: recentStart)?.let { "${it}T00:00:00.000Z" },
                    monthEnd = monthStart?.plusMonths(1)?.let { "${it}T00:00:00.000Z" },
                    query = trimmedQuery,
                )
            },
        ).flow.map { data -> data.map(AssetEntity::toDomain) }
    }

    override suspend fun albums(): List<ImmichAlbum> = api.getAlbums(requireConnection())

    override suspend fun createAlbum(name: String, assetIds: List<String>): ImmichAlbum =
        api.createAlbum(requireConnection(), name, assetIds)

    override suspend fun addToAlbum(albumId: String, assetIds: List<String>) =
        api.addToAlbum(requireConnection(), albumId, assetIds)

    override suspend fun removeFromAlbum(albumId: String, assetIds: List<String>) =
        api.removeFromAlbum(requireConnection(), albumId, assetIds)

    override suspend fun renameAlbum(albumId: String, name: String): String {
        api.renameAlbum(requireConnection(), albumId, name)
        return albumId
    }

    override suspend fun deleteAlbum(albumId: String) = api.deleteAlbum(requireConnection(), albumId)

    override suspend fun contentSearchAvailable(): Boolean = api.smartSearchAvailable(requireConnection())

    override fun searchByContent(query: String, filter: LibraryFilter, month: String?, albumId: String?): Flow<PagingData<ImmichAsset>> {
        val connection = requireConnection()
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        return Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = 30, enablePlaceholders = false),
            pagingSourceFactory = {
                SmartSearchPagingSource(database, api, derivedAssets, connection, libraryKey, query.trim(), filter, month, albumId)
            },
        ).flow
    }

    override val hasTrash: Boolean get() = true

    override val hasPeople: Boolean get() = true

    override val hasMap: Boolean get() = true

    override val canArchive: Boolean get() = true

    override suspend fun setArchived(assetIds: List<String>, archived: Boolean) {
        val connection = requireConnection()
        api.setArchived(connection, assetIds, archived)
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        assetIds.chunked(500).forEach { database.assetDao().setArchived(libraryKey, it, archived) }
    }

    /**
     * The server's archive, page by page into the catalogue, marked; the timeline's sync never
     * brings it, and without this an archived photo would have nowhere to be opened from.
     */
    override suspend fun refreshArchive() {
        val connection = requireConnection()
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        var page: Int? = 1
        var read = 0
        while (page != null && read < ARCHIVE_LIMIT) {
            val result = api.archivedAssets(connection, page, PAGE_SIZE)
            database.withTransaction {
                database.upsertFromSearch(libraryKey, result.items.map { it.copy(isArchived = true) })
                database.assetDao().restoreLocalRecipeFlags(libraryKey)
            }
            read += result.items.size
            page = result.nextPage
        }
    }

    override fun archivedAssets(): Flow<PagingData<ImmichAsset>> {
        val connection = requireConnection()
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        return kotlinx.coroutines.flow.flow {
            refreshArchive()
            emitAll(
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) { database.assetDao().archivedPagingSource(libraryKey) }
                    .flow.map { page -> page.map(AssetEntity::toDomain) },
            )
        }
    }

    /** The server's own memories; their photos go into the catalogue, where the grid reads them. */
    override suspend fun onThisDay(today: LocalDate): List<DayMemory> {
        val connection = requireConnection()
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        val memories = api.onThisDay(connection, today)
        val derivedIds = derivedAssets.derivedIds()
        val visible = memories.map { memory -> memory.year to memory.assets.filterNot { it.isAppExport(derivedIds) } }.filter { it.second.isNotEmpty() }
        database.withTransaction {
            database.upsertFromSearch(libraryKey, visible.flatMap { (_, assets) -> assets })
            database.assetDao().restoreLocalRecipeFlags(libraryKey)
        }
        return visible.map { (year, assets) -> DayMemory(year, assets.map { it.id }) }
    }

    override fun mapContents(): Flow<MapContents> = kotlinx.coroutines.flow.flow {
        emit(MapContents(api.mapMarkers(requireConnection())))
    }

    override suspend fun people(): List<ImmichPerson> = api.people(requireConnection())

    override fun personThumbnailUrl(personId: String): String = api.personThumbnailUrl(requireConnection(), personId)

    /** Straight from the server, like an album: the catalogue does not know who is in each photo. */
    override fun personAssets(personId: String, filter: LibraryFilter): Flow<PagingData<ImmichAsset>> {
        val connection = requireConnection()
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        return Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = 30, enablePlaceholders = false),
            pagingSourceFactory = {
                AlbumAssetPagingSource(database, api, derivedAssets, connection, libraryKey, null, filter, null, personId)
            },
        ).flow
    }

    /** Page after page, up to a limit a phone screen can still show at once. */
    override suspend fun trash(): TrashContents {
        val connection = requireConnection()
        val items = mutableListOf<TrashedAsset>()
        var page: Int? = 1
        while (page != null && items.size < TRASH_LIMIT) {
            val result = api.trashedAssets(connection, page, TRASH_PAGE)
            items += result.items.map { TrashedAsset(it) }
            page = result.nextPage
        }
        return TrashContents(items, api.trashDays(connection))
    }

    /** Restored photos are back in their months: the catalogue reads the ones that changed. */
    override suspend fun restoreFromTrash(assetIds: List<String>) {
        api.restoreFromTrash(requireConnection(), assetIds)
        runCatching { syncCatalog() }
    }

    override suspend fun deleteForever(assetIds: List<String>) = api.deleteAssets(requireConnection(), assetIds, force = true)

    override suspend fun emptyTrash() = api.emptyTrash(requireConnection())
    override suspend fun timeBuckets(): List<ImmichTimeBucket> {
        val connection = requireConnection()
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        val remote = runCatching { api.getTimeBuckets(connection) }
        val cached = database.assetDao().timeBuckets(libraryKey).map {
            ImmichTimeBucket(month = it.month, assetCount = it.assetCount)
        }
        return remote.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: cached.takeIf { it.isNotEmpty() }
            ?: remote.getOrThrow()
    }

    private val catalogSyncState = MutableStateFlow(CatalogSyncState())
    override val catalogSync: StateFlow<CatalogSyncState> = catalogSyncState.asStateFlow()

    /** One sync at a time: two in parallel wrote the same month over each other. */
    private val catalogSyncLock = Mutex()

    override suspend fun syncCatalog() {
        val connection = configuration.currentConnection() ?: return
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        catalogSyncLock.withLock {
            // Without the buckets there is nothing to sync, and a network failure here is not an
            // error to show: the library keeps working with what it has.
            val buckets = runCatching { api.getTimeBuckets(connection) }
                .onFailure { syncLog("W", "no buckets: ${it.message}") }
                .getOrNull() ?: return
            val known = database.catalogMonthDao().all(libraryKey).associate { it.month to it.assetCount }
            val pending = buckets.filter { known[it.month] != it.assetCount }
            syncLog("I", "months on the server: ${buckets.size}, to sync: ${pending.size}")
            if (pending.isEmpty()) {
                catalogSyncState.value = CatalogSyncState()
                return
            }
            catalogSyncState.value = CatalogSyncState(syncing = true, done = 0, total = pending.size)
            try {
                pending.forEachIndexed { index, bucket ->
                    syncMonth(connection, libraryKey, bucket)
                    catalogSyncState.value = catalogSyncState.value.copy(done = index + 1)
                }
                syncLog("I", "cronologia sincronizada")
            } catch (error: Exception) {
                // Interrupting the sync must not bring the library down: what came in stays, and the
                // next launch carries on from where this one stopped.
                syncLog("W", "sync interrupted: ${error.message}")
            } finally {
                catalogSyncState.value = CatalogSyncState()
            }
        }
    }

    /**
     * Rewrites a month with what the server has now.
     *
     * Deleting and writing again, instead of inserting over, is what makes a photo deleted elsewhere
     * disappear here. What was already known beyond that about the photos that stay — name,
     * checksum, real dimensions — is read first and goes back in with them, or every sync would undo
     * what browsing had fetched.
     */
    override suspend fun loadMonth(month: String) {
        val connection = configuration.currentConnection() ?: return
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        catalogSyncLock.withLock {
            // The bucket id is the server's, not ours: the list is requested and its own id used.
            // Building a "2019-03-01" here would assume a format the version on the other side may
            // write differently — and a month that does not exist silently returns empty.
            val bucket = runCatching { api.getTimeBuckets(connection) }.getOrNull()
                ?.firstOrNull { it.month.take(MONTH_PREFIX) == month.take(MONTH_PREFIX) }
                ?: return
            syncMonth(connection, libraryKey, bucket)
        }
    }

    private suspend fun syncMonth(
        connection: ImmichConnection,
        libraryKey: String,
        bucket: ImmichTimeBucket,
    ) {
        val assets = runCatching { api.getTimeBucketAssets(connection, bucket.month) }
            .onFailure { syncLog("W", "month ${bucket.month} failed: ${it.message}") }
            .getOrNull() ?: return
        val derivedIds = derivedAssets.derivedIds()
        val month = bucket.month.take(MONTH_PREFIX)
        database.withTransaction {
            val existing = database.assetDao().byMonth(libraryKey, month).associateBy { it.id }
            val rows = assets
                .filter { it.type in LIBRARY_ASSET_TYPES && it.id !in derivedIds }
                .map { asset -> AssetEntity.fromDomain(libraryKey, asset).keeping(existing[asset.id]) }
            database.assetDao().deleteMonth(libraryKey, month)
            database.assetDao().upsertAll(rows)
            database.assetDao().restoreLocalRecipeFlags(libraryKey)
            database.catalogMonthDao().upsert(
                CatalogMonthEntity(
                    libraryKey = libraryKey,
                    month = bucket.month,
                    assetCount = bucket.assetCount,
                    syncedAt = Instant.now().toString(),
                ),
            )
        }
    }

    override suspend fun indexOfAsset(
        assetId: String,
        filter: LibraryFilter,
        month: String?,
        query: String?,
    ): Int? {
        val connection = configuration.currentConnection() ?: return null
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        val createdAt = database.assetDao().createdAt(libraryKey, assetId) ?: return null
        val monthStart = month?.let { LocalDate.parse(it).withDayOfMonth(1) }
        val recentStart = LocalDate.now().minusDays(RECENT_FILTER_DAYS)
            .takeIf { filter == LibraryFilter.RECENT && monthStart == null }
        return database.assetDao().countBefore(
            libraryKey = libraryKey,
            favoritesOnly = filter == LibraryFilter.FAVORITES,
            editedOnly = filter == LibraryFilter.EDITED,
            monthStart = (monthStart ?: recentStart)?.let { "${it}T00:00:00.000Z" },
            monthEnd = monthStart?.plusMonths(1)?.let { "${it}T00:00:00.000Z" },
            query = query?.trim()?.takeIf(String::isNotEmpty),
            anchorCreatedAt = createdAt,
            anchorId = assetId,
        )
    }

    override suspend fun indexOfDate(
        date: LocalDate,
        filter: LibraryFilter,
        month: String?,
        query: String?,
    ): Int? {
        val connection = configuration.currentConnection() ?: return null
        val libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        val monthStart = month?.let { LocalDate.parse(it).withDayOfMonth(1) }
        val recentStart = LocalDate.now().minusDays(RECENT_FILTER_DAYS)
            .takeIf { filter == LibraryFilter.RECENT && monthStart == null }
        val trimmedQuery = query?.trim()?.takeIf(String::isNotEmpty)
        return database.assetDao().countNewerThan(
            libraryKey = libraryKey,
            favoritesOnly = filter == LibraryFilter.FAVORITES,
            editedOnly = filter == LibraryFilter.EDITED,
            monthStart = (monthStart ?: recentStart)?.let { "${it}T00:00:00.000Z" },
            monthEnd = monthStart?.plusMonths(1)?.let { "${it}T00:00:00.000Z" },
            query = trimmedQuery,
            // Everything after the end of this day is more recent than the destination; what is left
            // starts exactly on it.
            boundary = "${date.plusDays(1)}T00:00:00.000Z",
        )
    }

    override fun thumbnailUrl(assetId: String): String = api.thumbnailUrl(requireConnection(), assetId)
    override fun previewUrl(assetId: String): String = api.previewUrl(requireConnection(), assetId)
    override fun videoPlaybackUrl(assetId: String): String = api.videoPlaybackUrl(requireConnection(), assetId)
    override fun apiKey(assetId: String): String = requireConnection().apiKey

    /**
     * The detail, and on the way what the catalogue row was missing.
     *
     * The skeleton that comes from the buckets has no name or checksum; this is the only call that
     * knows them, and writing them here makes the catalogue complete itself as one browses, instead
     * of waiting for a heavy sync nobody asked for.
     */
    override suspend fun assetDetail(assetId: String): ImmichAssetDetail {
        val connection = requireConnection()
        val detail = api.getAssetDetail(connection, assetId)
        val asset = detail.asset
        if (asset.originalFileName.isNotBlank()) {
            runCatching {
                database.assetDao().enrich(
                    libraryKey = connection.libraryId ?: libraryKeyOf(connection.serverUrl),
                    assetId = assetId,
                    checksum = asset.checksum,
                    originalFileName = asset.originalFileName,
                    width = asset.width,
                    height = asset.height,
                    isEdited = asset.isEdited,
                    mimeType = asset.mimeType,
                )
            }
        }
        return detail
    }

    // Immich is the source of truth: the local catalogue only changes after it accepts. If the call
    // fails, the exception goes up and the UI restores the previous state.
    override suspend fun setFavorite(assetId: String, isFavorite: Boolean) {
        val connection = requireConnection()
        api.setFavorite(connection, assetId, isFavorite)
        database.assetDao().setFavorite(connection.libraryId ?: libraryKeyOf(connection.serverUrl), assetId, isFavorite)
    }

    override suspend fun deleteAsset(assetId: String) {
        val connection = requireConnection()
        api.deleteAsset(connection, assetId)
        database.assetDao().delete(connection.libraryId ?: libraryKeyOf(connection.serverUrl), assetId)
    }

    override suspend fun setFavorites(assetIds: List<String>, isFavorite: Boolean) {
        val connection = requireConnection()
        api.setFavorites(connection, assetIds, isFavorite)
        val library = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        assetIds.forEach { database.assetDao().setFavorite(library, it, isFavorite) }
    }

    override suspend fun deleteAssets(assetIds: List<String>) {
        val connection = requireConnection()
        api.deleteAssets(connection, assetIds)
        val library = connection.libraryId ?: libraryKeyOf(connection.serverUrl)
        assetIds.forEach { database.assetDao().delete(library, it) }
    }

    override suspend fun checkCanDelete(assetId: String) =
        api.requirePermission(requireConnection(), ImmichKeyPermissions.DELETE_ASSETS)

    override suspend fun downloadOriginal(assetId: String, destination: File) =
        api.downloadOriginal(requireConnection(), assetId, destination)

    private fun requireConnection(): ImmichConnection =
        checkNotNull(configuration.currentConnection()) { "Immich connection is not configured" }

    private companion object {
        const val PAGE_SIZE = 100
        const val TRASH_PAGE = 500
        const val TRASH_LIMIT = 5_000
        const val ARCHIVE_LIMIT = 10_000

        /** "2024-05" — enough to identify the month, whether the bucket comes with a date or an instant. */
        const val MONTH_PREFIX = 7

        /** What to filter in logcat to watch the timeline sync happen. */
        const val SYNC_TAG = "ImagoCatalogSync"
    }
}

/**
 * Writes a search's photos into the catalogue without erasing the stack the timeline gave each one,
 * and returns them carrying it — a search knows nothing of stacks, and without this a cover opened
 * from an album lost its badge.
 */
internal suspend fun ImmichRoomDatabase.upsertFromSearch(libraryKey: String, assets: List<ImmichAsset>): List<ImmichAsset> {
    if (assets.isEmpty()) return assets
    val known = assets.map { it.id }.chunked(SQL_VARIABLES_PER_QUERY)
        .flatMap { ids -> assetDao().byIds(libraryKey, ids) }
        .associateBy { it.id }
    val rows = assets.map { AssetEntity.fromDomain(libraryKey, it).keepingStackOf(known[it.id]) }
    assetDao().upsertAll(rows)
    return assets.zip(rows) { asset, row -> asset.copy(stackId = row.stackId, stackCount = row.stackCount) }
}

/** Under SQLite's limit on the variables of one statement, which older Androids keep at 999. */
private const val SQL_VARIABLES_PER_QUERY = 500

/**
 * A photo that went from this app to Immich. It reappears in the library's next reads as an
 * independent asset, next to the original — and that is what is filtered here.
 *
 * The local record is the reliable source; the file name is the safety net for exports made on
 * another device or before the record existed.
 */
private fun ImmichAsset.isAppExport(derivedIds: Set<String>): Boolean =
    id in derivedIds || isImmichRoomExport(originalFileName)

/**
 * What the library shows.
 *
 * Videos come in next to the photos, but only for viewing: the editor works on bitmaps and has
 * nothing to say about them. Audio and loose files stay out — there is no view that knows how to
 * present them.
 */
private val LIBRARY_ASSET_TYPES = setOf(AssetType.IMAGE, AssetType.VIDEO)

@OptIn(ExperimentalPagingApi::class)
private class AssetRemoteMediator(
    private val database: ImmichRoomDatabase,
    private val api: ImmichApi,
    private val derivedAssets: DerivedAssetRepository,
    private val connection: ImmichConnection,
    private val libraryKey: String,
    private val filter: LibraryFilter,
    private val month: String?,
    private val query: String?,
    private val refreshedThisSession: MutableSet<String>,
) : RemoteMediator<Int, AssetEntity>() {

    /**
     * One sync per slice and per launch — not one each time the grid is born.
     *
     * By default Paging launches a REFRESH whenever a `Pager` is created, and one is created on every
     * change of chip, month or search, and again when going back. As this view's REFRESH clears the
     * catalogue and goes back to page 1, everything one had scrolled through disappeared: scrolling
     * down a thousand photos, tapping a chip and coming back returned the first hundred.
     *
     * From the second time on the local catalogue is already synced up to a known page and scrolling
     * carries on from there. Whoever really wants to ask the server again has pull-to-refresh and
     * "Refresh library" in the menu, which call `refresh()` and do not go through here.
     */
    override suspend fun initialize(): InitializeAction = when {
        refreshedThisSession.add(sessionKey) -> InitializeAction.LAUNCH_INITIAL_REFRESH
        database.remoteKeyDao().get(libraryKey, queryKey) != null -> InitializeAction.SKIP_INITIAL_REFRESH
        else -> InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: androidx.paging.PagingState<Int, AssetEntity>,
    ): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> 1
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> database.remoteKeyDao().get(libraryKey, queryKey)?.nextPage
                ?: return MediatorResult.Success(endOfPaginationReached = true)
        }

        return try {
            val result = api.searchAssets(
                connection, page, state.config.pageSize, filter, month = month, query = query,
            )
            val derivedIds = derivedAssets.derivedIds()
            database.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    database.remoteKeyDao().clear(libraryKey, queryKey)
                    // The catalogue is no longer cleared here. The per-month sync is now in charge of
                    // it: it rewrites, month by month, what the server has, and it is what makes
                    // whatever is no longer there disappear. Clearing it on every launch meant
                    // deleting at once the whole timeline it had brought, only to ask for it again a
                    // hundred photos at a time.
                    database.assetDao().purgeAppExports(libraryKey)
                }
                database.upsertFromSearch(
                    libraryKey,
                    result.items.filter { it.type in LIBRARY_ASSET_TYPES && !it.isAppExport(derivedIds) },
                )
                database.assetDao().restoreLocalRecipeFlags(libraryKey)
                database.remoteKeyDao().upsert(RemoteKeyEntity(libraryKey, queryKey, result.nextPage))
            }
            MediatorResult.Success(endOfPaginationReached = result.nextPage == null)
        } catch (error: Exception) {
            MediatorResult.Error(error)
        }
    }

    private val queryKey = "${filter.name}|${month.orEmpty()}|${query.orEmpty()}"

    /** The same key, prefixed by the library: this is how [refreshedThisSession] stores it. */
    private val sessionKey = "$libraryKey|$queryKey"
}

/**
 * The server's answer to "what is in the photo", page by page, in its order — the closest first, so
 * it cannot come from the catalogue, which is sorted by date. The answers still go into the
 * catalogue, where the detail and the editor look for them.
 */
private class SmartSearchPagingSource(
    private val database: ImmichRoomDatabase,
    private val api: ImmichApi,
    private val derivedAssets: DerivedAssetRepository,
    private val connection: ImmichConnection,
    private val libraryKey: String,
    private val query: String,
    private val filter: LibraryFilter,
    private val month: String?,
    private val albumId: String?,
) : PagingSource<Int, ImmichAsset>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ImmichAsset> {
        val page = params.key ?: 1
        return try {
            val monthStart = month?.let { LocalDate.parse(it).withDayOfMonth(1) }
            val recentStart = LocalDate.now().minusDays(RECENT_FILTER_DAYS).takeIf { filter == LibraryFilter.RECENT && monthStart == null }
            val result = api.smartSearch(
                connection = connection,
                page = page,
                pageSize = params.loadSize.coerceAtMost(100),
                query = query,
                albumId = albumId,
                favoritesOnly = filter == LibraryFilter.FAVORITES,
                takenAfter = (monthStart ?: recentStart)?.let { "${it}T00:00:00.000Z" },
                takenBefore = monthStart?.plusMonths(1)?.let { "${it}T00:00:00.000Z" },
                language = java.util.Locale.getDefault().language,
            )
            val derivedIds = derivedAssets.derivedIds()
            val visible = result.items.filterNot { it.isAppExport(derivedIds) }
            val stacked = database.withTransaction {
                database.upsertFromSearch(libraryKey, visible).also { database.assetDao().restoreLocalRecipeFlags(libraryKey) }
            }
            val recipes = database.recipeDao()
            LoadResult.Page(
                data = stacked
                    .map { asset -> asset.copy(hasLocalRecipe = recipes.get(libraryKey, asset.id) != null) }
                    // "Edited" is not a criterion Immich knows: it is applied to what came back.
                    .filter { filter != LibraryFilter.EDITED || it.isEdited || it.hasLocalRecipe },
                prevKey = null,
                nextKey = result.nextPage,
            )
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ImmichAsset>): Int? = null
}

/** An album's photos, or a person's, read from the server page by page in its order. */
private class AlbumAssetPagingSource(
    private val database: ImmichRoomDatabase,
    private val api: ImmichApi,
    private val derivedAssets: DerivedAssetRepository,
    private val connection: ImmichConnection,
    private val libraryKey: String,
    private val albumId: String?,
    private val filter: LibraryFilter,
    private val query: String?,
    private val personId: String? = null,
) : PagingSource<Int, ImmichAsset>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ImmichAsset> {
        val page = params.key ?: 1
        return try {
            val result = api.searchAssets(
                connection = connection,
                page = page,
                pageSize = params.loadSize.coerceAtMost(100),
                filter = filter,
                albumId = albumId,
                query = query,
                personId = personId,
            )
            val derivedIds = derivedAssets.derivedIds()
            val visible = result.items.filterNot { it.isAppExport(derivedIds) }
            // The catalogue receives the whole album; it is the returned list that the chip slices.
            // Filtering before the upsert would leave holes in the library when going back.
            val stacked = database.withTransaction {
                database.upsertFromSearch(libraryKey, visible).also { database.assetDao().restoreLocalRecipeFlags(libraryKey) }
            }
            val recipes = database.recipeDao()
            LoadResult.Page(
                data = stacked
                    .map { asset -> asset.copy(hasLocalRecipe = recipes.get(libraryKey, asset.id) != null) }
                    // "Edited" does not exist as a criterion in Immich and inside an album the list
                    // comes straight from the network: this is where the chip has to be applied.
                    .filter { filter != LibraryFilter.EDITED || it.isEdited || it.hasLocalRecipe },
                prevKey = null,
                nextKey = result.nextPage,
            )
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ImmichAsset>): Int? = null
}
