package eu.studio742.imago.core.data

import androidx.paging.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.studio742.imago.core.data.db.*
import eu.studio742.imago.core.model.*
import java.time.LocalDate

/**
 * This device's library, read from the local catalogue.
 *
 * Everything the grid asks — the pages, the folders, the months, the position of a photo or of a
 * date — comes from the `assets` rows with the key [DEVICE_LIBRARY_ID]. What changes from platform
 * to platform is only how those rows are filled ([syncCatalog]) and how the file is reached:
 * MediaStore on Android, the chosen folders on desktop.
 */
abstract class LocalCatalogLibrary(protected val database: ImmichRoomDatabase) : DeviceLibrary {
    private val lock = Mutex()
    private val sync = MutableStateFlow(CatalogSyncState())
    override val catalogSync: StateFlow<CatalogSyncState> = sync.asStateFlow()

    /** One catalogue update at a time, with the sync state in view of the grid. */
    protected suspend fun refreshing(block: suspend () -> Unit) = lock.withLock {
        sync.value = CatalogSyncState(syncing = true)
        try { block() } finally { sync.value = CatalogSyncState() }
    }

    /** Swaps the whole catalogue in one transaction; photos with a recipe keep the mark. */
    protected suspend fun replaceCatalog(rows: List<AssetEntity>) = database.withTransaction {
        database.assetDao().deleteLibrary(DEVICE_LIBRARY_ID)
        database.assetDao().upsertAll(rows)
        database.assetDao().restoreLocalRecipeFlags(DEVICE_LIBRARY_ID)
    }

    override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?): Flow<PagingData<ImmichAsset>> {
        val start = month?.let { LocalDate.parse(it).withDayOfMonth(1) }
            ?: LocalDate.now().minusDays(RECENT_FILTER_DAYS).takeIf { filter == LibraryFilter.RECENT }
        return Pager(PagingConfig(pageSize = 100, initialLoadSize = 100, enablePlaceholders = true, jumpThreshold = 300)) {
            database.assetDao().pagingSource(DEVICE_LIBRARY_ID, filter == LibraryFilter.FAVORITES,
                filter == LibraryFilter.EDITED, start?.let { "${it}T00:00:00.000Z" },
                month?.let { "${LocalDate.parse(it).withDayOfMonth(1).plusMonths(1)}T00:00:00.000Z" }, query, albumId)
        }.flow.map { page -> page.map(AssetEntity::toDomain) }
    }
    override suspend fun albums(): List<ImmichAlbum> {
        val starts = database.assetDao().folderStarts(DEVICE_LIBRARY_ID).associate { it.folderId.orEmpty() to it.startDate }
        return database.assetDao().folders(DEVICE_LIBRARY_ID).map { folder ->
            val id = folder.folderId.orEmpty()
            ImmichAlbum(id, folder.folderName.orEmpty(), "", folder.coverId, folder.assetCount, starts[id], folder.endDate, false)
        }
    }

    /**
     * Swaps the catalogue rows of these photos for what the source says now, and drops the ones it
     * no longer has. After moving, copying, marking or deleting a few photos this is all that
     * changed — reading the whole catalogue again took seconds on a phone with tens of thousands.
     */
    protected suspend fun replaceRows(ids: Collection<String>, rows: List<AssetEntity>) = database.withTransaction {
        val found = rows.map { it.id }.toSet()
        ids.filterNot(found::contains).forEach { database.assetDao().delete(DEVICE_LIBRARY_ID, it) }
        database.assetDao().upsertAll(rows)
        database.assetDao().restoreLocalRecipeFlags(DEVICE_LIBRARY_ID)
    }
    override suspend fun timeBuckets() = database.assetDao().timeBuckets(DEVICE_LIBRARY_ID).map { ImmichTimeBucket(it.month, it.assetCount) }
    override suspend fun loadMonth(month: String) = syncCatalog()
    override suspend fun indexOfAsset(
        assetId: String,
        filter: LibraryFilter,
        month: String?,
        query: String?,
    ): Int? {
        val libraryKey = DEVICE_LIBRARY_ID
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
        val libraryKey = DEVICE_LIBRARY_ID
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

    override fun thumbnailUrl(assetId: String) = assetId
    override fun previewUrl(assetId: String) = assetId
    override fun videoPlaybackUrl(assetId: String) = assetId
    override fun apiKey(assetId: String) = ""
}
