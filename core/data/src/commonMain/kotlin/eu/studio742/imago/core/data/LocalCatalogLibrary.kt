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
    protected suspend fun replaceCatalog(rows: List<AssetEntity>) {
        database.withTransaction {
            database.assetDao().deleteLibrary(DEVICE_LIBRARY_ID)
            database.assetDao().upsertAll(rows)
            database.assetDao().restoreLocalRecipeFlags(DEVICE_LIBRARY_ID)
            database.assetDao().restoreArchivedFlags(DEVICE_LIBRARY_ID)
        }
        val present = rows.map { it.id }.toSet()
        settleStacks(gone = database.stackMemberDao().all(DEVICE_LIBRARY_ID).map { it.assetId }.filterNot(present::contains).toSet())
    }

    /**
     * The rows of these photos as the source has them now — MediaStore on Android, the files on the
     * computer. The catalogue leaves out the photos under a stack's cover; this is how one comes back
     * when it leaves the stack, or is shown in the stack's strip.
     */
    protected open suspend fun rowsOf(ids: Collection<String>): List<AssetEntity> = emptyList()

    /**
     * Puts the catalogue in line with this device's stacks: the photos in [gone] leave them, a stack
     * of one is undone, each cover carries its count, this app's exports leave the grid, and a photo
     * out of every stack is back in the catalogue if it had left it.
     *
     * The photos under a cover stay in the catalogue; the grid's queries leave them out. Taking them
     * out of it would let the unified library show the server's copy of each in its place — it hides
     * a server photo only when the phone's copy is there.
     *
     * The exports are told apart by their name: on this device they have no record to go by — the
     * records sync between devices, and an id of this device's means nothing on another.
     */
    private suspend fun settleStacks(gone: Set<String>) {
        val dao = database.stackMemberDao()
        val before = dao.all(DEVICE_LIBRARY_ID)
        val members = settleLocalStacks(before, gone)
        val stacked = members.map { it.assetId }.toSet()
        val covers = members.filter { it.isCover }
        val freed = before.map { it.assetId }.filterNot { it in stacked || it in gone }
        val catalogued = (covers.map { it.assetId } + freed).chunked(500)
            .flatMap { database.assetDao().byIds(DEVICE_LIBRARY_ID, it) }
            .map { it.id }
            .toSet()
        val back = rowsOf((covers.map { it.assetId } + freed).filterNot(catalogued::contains))
        val sizes = members.groupingBy { it.stackId }.eachCount()
        database.withTransaction {
            dao.clear(DEVICE_LIBRARY_ID)
            dao.insertAll(members)
            database.assetDao().upsertAll(back)
            database.assetDao().purgeAppExports(DEVICE_LIBRARY_ID)
            database.assetDao().clearStacks(DEVICE_LIBRARY_ID)
            covers.forEach { database.assetDao().setStack(DEVICE_LIBRARY_ID, it.assetId, it.stackId, sizes.getValue(it.stackId)) }
            database.assetDao().restoreLocalRecipeFlags(DEVICE_LIBRARY_ID)
            database.assetDao().restoreArchivedFlags(DEVICE_LIBRARY_ID)
        }
    }

    override val canStack: Boolean get() = true

    override suspend fun stackTogether(assetIds: List<String>) {
        val dao = database.stackMemberDao()
        val members = stackedTogether(DEVICE_LIBRARY_ID, assetIds, dao.all(DEVICE_LIBRARY_ID), java.util.UUID.randomUUID().toString())
        database.withTransaction {
            dao.clear(DEVICE_LIBRARY_ID)
            dao.insertAll(members)
        }
        settleStacks(gone = emptySet())
    }

    override suspend fun makeStackCover(assetId: String) {
        val dao = database.stackMemberDao()
        val stack = dao.ofAssets(DEVICE_LIBRARY_ID, listOf(assetId)).firstOrNull()?.stackId ?: error("The photo is in no stack")
        val members = dao.ofStacks(DEVICE_LIBRARY_ID, listOf(stack))
        database.withTransaction {
            dao.clearStack(DEVICE_LIBRARY_ID, stack)
            dao.insertAll(members.map { it.copy(primaryAssetId = assetId) })
        }
        settleStacks(gone = emptySet())
    }

    override suspend fun removeFromStack(assetId: String) {
        val dao = database.stackMemberDao()
        val member = dao.ofAssets(DEVICE_LIBRARY_ID, listOf(assetId)).firstOrNull() ?: error("The photo is in no stack")
        val rest = dao.ofStacks(DEVICE_LIBRARY_ID, listOf(member.stackId)).filterNot { it.assetId == assetId }
        val cover = rest.firstOrNull { it.isCover }?.assetId ?: rest.firstOrNull()?.assetId
        database.withTransaction {
            dao.clearStack(DEVICE_LIBRARY_ID, member.stackId)
            dao.insertAll(rest.map { it.copy(primaryAssetId = cover ?: it.primaryAssetId) })
        }
        settleStacks(gone = emptySet())
    }

    override suspend fun unstack(assetId: String) {
        val dao = database.stackMemberDao()
        val stack = dao.ofAssets(DEVICE_LIBRARY_ID, listOf(assetId)).firstOrNull()?.stackId ?: error("The photo is in no stack")
        dao.clearStack(DEVICE_LIBRARY_ID, stack)
        settleStacks(gone = emptySet())
    }

    override suspend fun stackMembers(assetId: String): List<ImmichAsset> {
        val dao = database.stackMemberDao()
        val stack = dao.ofAssets(DEVICE_LIBRARY_ID, listOf(assetId)).firstOrNull()?.stackId ?: return emptyList()
        val members = dao.ofStacks(DEVICE_LIBRARY_ID, listOf(stack)).sortedByDescending { it.isCover }
        if (members.size < 2) return emptyList()
        val ids = members.map { it.assetId }
        val known = database.assetDao().byIds(DEVICE_LIBRARY_ID, ids).associateBy { it.id }
        val read = rowsOf(ids.filterNot(known::containsKey)).associateBy { it.id }
        return ids.mapNotNull { (known[it] ?: read[it])?.toDomain() }
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
    protected suspend fun replaceRows(ids: Collection<String>, rows: List<AssetEntity>) {
        val found = rows.map { it.id }.toSet()
        database.withTransaction {
            ids.filterNot(found::contains).forEach { database.assetDao().delete(DEVICE_LIBRARY_ID, it) }
            database.assetDao().upsertAll(rows)
            database.assetDao().restoreLocalRecipeFlags(DEVICE_LIBRARY_ID)
            database.assetDao().restoreArchivedFlags(DEVICE_LIBRARY_ID)
        }
        settleStacks(gone = ids.filterNot(found::contains).toSet())
    }

    override val canArchive: Boolean get() = true

    /** The app's own archive: neither Android nor the folders have one, so the other apps still show these. */
    override suspend fun setArchived(assetIds: List<String>, archived: Boolean) = database.withTransaction {
        if (archived) database.archivedAssetDao().archive(assetIds.map { ArchivedAssetEntity(DEVICE_LIBRARY_ID, it) })
        else assetIds.chunked(500).forEach { database.archivedAssetDao().unarchive(DEVICE_LIBRARY_ID, it) }
        assetIds.chunked(500).forEach { database.assetDao().setArchived(DEVICE_LIBRARY_ID, it, archived) }
    }

    override fun archivedAssets(): Flow<PagingData<ImmichAsset>> =
        Pager(PagingConfig(pageSize = 100, enablePlaceholders = false)) { database.assetDao().archivedPagingSource(DEVICE_LIBRARY_ID) }
            .flow.map { page -> page.map(AssetEntity::toDomain) }
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

    override val hasMap: Boolean get() = true

    override suspend fun onThisDay(today: LocalDate): List<DayMemory> =
        database.assetDao().onThisDay(DEVICE_LIBRARY_ID, "%02d-%02d".format(today.monthValue, today.dayOfMonth), today.year.toString())
            .groupBy { it.localDateTime.take(4).toInt() }
            .map { (year, rows) -> DayMemory(year, rows.map { it.id }) }
            .sortedByDescending { it.year }

    /** Whether the places in the files can be read now; Android hides them until it is allowed. */
    protected open fun canReadLocations(): Boolean = true

    /** Where the photo was taken, from its file; null when it does not say. */
    protected open suspend fun readLocation(assetId: String): Pair<Double, Double>? = null

    private val locationLock = Mutex()

    override fun mapContents(): Flow<MapContents> = flow {
        val dao = database.assetLocationDao()
        suspend fun known() = dao.located(DEVICE_LIBRARY_ID).map { MapMarker(it.assetId, it.latitude, it.longitude) }
        if (!canReadLocations()) {
            emit(MapContents(known(), needsLocationAccess = true))
            return@flow
        }
        locationLock.withLock {
            val total = dao.unreadCount(DEVICE_LIBRARY_ID)
            var done = 0
            emit(MapContents(known(), reading = MapReading(0, total).takeIf { total > 0 }))
            while (true) {
                val batch = dao.unread(DEVICE_LIBRARY_ID, LOCATION_BATCH)
                if (batch.isEmpty()) break
                dao.upsertAll(batch.map { row ->
                    val place = readLocationOrNull(row.id)
                    AssetLocationEntity(DEVICE_LIBRARY_ID, row.id, row.checksum, place?.first, place?.second)
                })
                done += batch.size
                if (done < total) emit(MapContents(known(), reading = MapReading(done, total)))
            }
        }
        emit(MapContents(known()))
    }

    /**
     * A file that cannot be read keeps no place, and is not read again until it changes. Losing the
     * permission halfway is not that: it stops the reading, and nothing is written for the rest.
     */
    private suspend fun readLocationOrNull(assetId: String): Pair<Double, Double>? = try {
        readLocation(assetId)?.takeUnless { (latitude, longitude) -> latitude == 0.0 && longitude == 0.0 }
    } catch (error: SecurityException) {
        throw error
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    override fun thumbnailUrl(assetId: String) = assetId
    override fun previewUrl(assetId: String) = assetId
    override fun videoPlaybackUrl(assetId: String) = assetId
    override fun apiKey(assetId: String) = ""

    private companion object {
        const val LOCATION_BATCH = 300
    }
}
