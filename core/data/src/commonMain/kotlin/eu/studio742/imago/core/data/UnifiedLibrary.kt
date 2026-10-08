package eu.studio742.imago.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichPerson
import eu.studio742.imago.core.model.MapContents
import eu.studio742.imago.core.model.DayMemory
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.RECENT_FILTER_DAYS
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The phone and one server library in a single timeline.
 *
 * It keeps nothing of its own: the timeline is a query over the two catalogues that already exist,
 * and a photo on both sides — the same file name, dates less than a day apart, which is what the
 * Immich app keeps when it uploads — shows once, as the phone's copy, marked as being on the server
 * too. Every id it hands out is the reference of the library the photo comes from, so opening,
 * editing and deleting go where they always went.
 *
 * [device] and [server] answer with their own local ids; [serverId] is the server library's.
 */
class UnifiedLibrary(
    private val database: ImmichRoomDatabase,
    private val device: LibraryRepository,
    private val server: LibraryRepository,
    private val serverId: String,
) : LibraryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val catalogSync: StateFlow<CatalogSyncState> = combine(device.catalogSync, server.catalogSync) { phone, cloud ->
        CatalogSyncState(syncing = phone.syncing || cloud.syncing, done = phone.done + cloud.done, total = phone.total + cloud.total)
    }.stateIn(scope, SharingStarted.Eagerly, CatalogSyncState())

    private data class Range(val start: String?, val end: String?)

    private fun range(filter: LibraryFilter, month: String?): Range {
        val monthStart = month?.let { LocalDate.parse(it).withDayOfMonth(1) }
        val recentStart = LocalDate.now().minusDays(RECENT_FILTER_DAYS).takeIf { filter == LibraryFilter.RECENT && monthStart == null }
        return Range((monthStart ?: recentStart)?.let { "${it}T00:00:00.000Z" }, monthStart?.plusMonths(1)?.let { "${it}T00:00:00.000Z" })
    }

    private fun libraryOf(id: String) = if (id == DEVICE_LIBRARY_ID) device else server

    override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?): Flow<PagingData<ImmichAsset>> {
        // An album belongs to one side: it opens as that library shows it.
        if (albumId != null) {
            val album = AssetReference.parse(albumId)
            return libraryOf(album.libraryId).assets(filter, month, album.localId, query)
                .map { page -> page.map { it.copy(id = AssetReference(album.libraryId, it.id).encode()) } }
        }
        val range = range(filter, month)
        return Pager(PagingConfig(pageSize = 100, initialLoadSize = 100, enablePlaceholders = true, jumpThreshold = 300)) {
            database.assetDao().unifiedPagingSource(
                device = DEVICE_LIBRARY_ID,
                server = serverId,
                favoritesOnly = filter == LibraryFilter.FAVORITES,
                editedOnly = filter == LibraryFilter.EDITED,
                monthStart = range.start,
                monthEnd = range.end,
                query = query?.trim()?.takeIf(String::isNotEmpty),
            )
        }.flow.map { page ->
            page.map { row ->
                row.asset.toDomain().copy(id = AssetReference(row.asset.libraryKey, row.asset.id).encode(), isOnServer = row.alsoOnServer)
            }
        }
    }

    /** The phone's folders and the server's albums, each with its own library's reference. */
    override suspend fun albums(): List<ImmichAlbum> = coroutineScope {
        val phone = runCatching { device.albums() }.getOrDefault(emptyList()).map { it.encodedFor(DEVICE_LIBRARY_ID) }
        val cloud = runCatching { server.albums() }.getOrDefault(emptyList()).map { it.encodedFor(serverId) }
        phone + cloud
    }

    private fun ImmichAlbum.encodedFor(libraryId: String) = copy(
        id = AssetReference(libraryId, id).encode(),
        thumbnailAssetId = thumbnailAssetId?.let { AssetReference(libraryId, it).encode() },
    )

    override suspend fun timeBuckets(): List<ImmichTimeBucket> =
        database.assetDao().unifiedTimeBuckets(DEVICE_LIBRARY_ID, serverId).map { ImmichTimeBucket(it.month, it.assetCount) }

    override suspend fun syncCatalog() = coroutineScope {
        launch { runCatching { device.syncCatalog() } }
        server.syncCatalog()
    }

    override suspend fun loadMonth(month: String) = coroutineScope {
        launch { runCatching { device.loadMonth(month) } }
        server.loadMonth(month)
    }

    override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? {
        val reference = AssetReference.parse(assetId)
        val createdAt = database.assetDao().createdAt(reference.libraryId, reference.localId) ?: return null
        val range = range(filter, month)
        return database.assetDao().unifiedCountBefore(
            device = DEVICE_LIBRARY_ID,
            server = serverId,
            favoritesOnly = filter == LibraryFilter.FAVORITES,
            editedOnly = filter == LibraryFilter.EDITED,
            monthStart = range.start,
            monthEnd = range.end,
            query = query?.trim()?.takeIf(String::isNotEmpty),
            anchorCreatedAt = createdAt,
            anchorId = reference.localId,
        )
    }

    override suspend fun indexOfDate(date: LocalDate, filter: LibraryFilter, month: String?, query: String?): Int? {
        val range = range(filter, month)
        return database.assetDao().unifiedCountNewerThan(
            device = DEVICE_LIBRARY_ID,
            server = serverId,
            favoritesOnly = filter == LibraryFilter.FAVORITES,
            editedOnly = filter == LibraryFilter.EDITED,
            monthStart = range.start,
            monthEnd = range.end,
            query = query?.trim()?.takeIf(String::isNotEmpty),
            boundary = "${date.plusDays(1)}T00:00:00.000Z",
        )
    }

    override suspend fun counterpartOf(assetId: String): String? {
        val reference = AssetReference.parse(assetId)
        val other = if (reference.libraryId == DEVICE_LIBRARY_ID) serverId else DEVICE_LIBRARY_ID
        return database.assetDao().counterpart(reference.libraryId, reference.localId, other)?.let { AssetReference(other, it).encode() }
    }

    override suspend fun contentSearchAvailable(): Boolean = server.contentSearchAvailable()

    /** Only the server searches by content: what it finds is its own photos. */
    override fun searchByContent(query: String, filter: LibraryFilter, month: String?, albumId: String?): Flow<PagingData<ImmichAsset>> {
        val album = albumId?.let(AssetReference::parse)
        if (album != null && album.libraryId != serverId) return emptyFlow()
        return server.searchByContent(query, filter, month, album?.localId)
            .map { page -> page.map { it.copy(id = AssetReference(serverId, it.id).encode(), isOnServer = true) } }
    }

    override val hasMap: Boolean get() = true

    override val canArchive: Boolean get() = true

    /**
     * Each side archives its own; a photo on both goes on both, or the copy left behind would come
     * back into the timeline in its place.
     */
    override suspend fun setArchived(assetIds: List<String>, archived: Boolean) {
        val references = assetIds.map(AssetReference::parse)
        val phone = references.filter { it.libraryId == DEVICE_LIBRARY_ID }.map { it.localId }
        val cloud = references.filter { it.libraryId == serverId }.map { it.localId }.toMutableSet()
        phone.forEach { id -> database.assetDao().counterpart(DEVICE_LIBRARY_ID, id, serverId)?.let(cloud::add) }
        if (phone.isNotEmpty()) device.setArchived(phone, archived)
        if (cloud.isNotEmpty()) server.setArchived(cloud.toList(), archived)
    }

    override fun archivedAssets(): Flow<PagingData<ImmichAsset>> = kotlinx.coroutines.flow.flow {
        runCatching { server.refreshArchive() }
        emitAll(
            Pager(PagingConfig(pageSize = 100, enablePlaceholders = false)) { database.assetDao().unifiedArchivedPagingSource(DEVICE_LIBRARY_ID, serverId) }
                .flow.map { page ->
                    page.map { row -> row.asset.toDomain().copy(id = AssetReference(row.asset.libraryKey, row.asset.id).encode(), isOnServer = row.alsoOnServer) }
                },
        )
    }

    /** Both sides by year; a photo on both is the phone's, as in the timeline. */
    override suspend fun onThisDay(today: LocalDate): List<DayMemory> = coroutineScope {
        val phone = async { runCatching { device.onThisDay(today) }.getOrDefault(emptyList()) }
        val cloud = async { runCatching { server.onThisDay(today) }.getOrDefault(emptyList()) }
        val onPhone = database.assetDao().serverCopiesOfDevice(DEVICE_LIBRARY_ID, serverId).toHashSet()
        val byYear = LinkedHashMap<Int, MutableList<String>>()
        phone.await().forEach { memory -> byYear.getOrPut(memory.year) { mutableListOf() } += memory.assetIds.map { AssetReference(DEVICE_LIBRARY_ID, it).encode() } }
        cloud.await().forEach { memory ->
            byYear.getOrPut(memory.year) { mutableListOf() } += memory.assetIds.filterNot { it in onPhone }.map { AssetReference(serverId, it).encode() }
        }
        byYear.filterValues { it.isNotEmpty() }.map { (year, ids) -> DayMemory(year, ids) }.sortedByDescending { it.year }
    }

    /** Both sides; a photo on both is the phone's, as in the timeline. */
    override fun mapContents(): Flow<MapContents> {
        val cloud = kotlinx.coroutines.flow.flow {
            val onPhone = database.assetDao().serverCopiesOfDevice(DEVICE_LIBRARY_ID, serverId).toHashSet()
            emit(runCatching { server.mapContents().last() }.getOrDefault(MapContents(emptyList())).markers.filterNot { it.assetId in onPhone })
        }
        return combine(device.mapContents(), cloud.onStart { emit(emptyList()) }) { phone, server ->
            phone.copy(
                markers = phone.markers.map { it.copy(assetId = AssetReference(DEVICE_LIBRARY_ID, it.assetId).encode()) } +
                    server.map { it.copy(assetId = AssetReference(serverId, it.assetId).encode()) },
            )
        }
    }

    // Who is in the photos only the server knows.
    override val hasPeople: Boolean get() = server.hasPeople

    override suspend fun people(): List<ImmichPerson> = server.people().map { it.copy(id = AssetReference(serverId, it.id).encode()) }

    override fun personThumbnailUrl(personId: String) = routed(personId) { personThumbnailUrl(it) }

    override fun personAssets(personId: String, filter: LibraryFilter): Flow<PagingData<ImmichAsset>> =
        server.personAssets(AssetReference.parse(personId).localId, filter)
            .map { page -> page.map { it.copy(id = AssetReference(serverId, it.id).encode(), isOnServer = true) } }

    // Everything below is reached with a photo's own reference, and goes to its library.
    private fun <T> routed(assetId: String, block: LibraryRepository.(String) -> T): T {
        val reference = AssetReference.parse(assetId)
        return libraryOf(reference.libraryId).block(reference.localId)
    }

    override fun canRename(assetId: String) = routed(assetId) { canRename(it) }

    override fun thumbnailUrl(assetId: String) = routed(assetId) { thumbnailUrl(it) }
    override fun previewUrl(assetId: String) = routed(assetId) { previewUrl(it) }
    override fun videoPlaybackUrl(assetId: String) = routed(assetId) { videoPlaybackUrl(it) }
    override fun apiKey(assetId: String) = routed(assetId) { apiKey(it) }
    override suspend fun stackChanged(assetId: String) {
        val reference = AssetReference.parse(assetId)
        libraryOf(reference.libraryId).stackChanged(reference.localId)
    }

    override suspend fun exportOriginal(assetId: String): String? {
        val reference = AssetReference.parse(assetId)
        return libraryOf(reference.libraryId).exportOriginal(reference.localId)?.let { AssetReference(reference.libraryId, it).encode() }
    }

    override suspend fun stackMembers(assetId: String): List<ImmichAsset> {
        val reference = AssetReference.parse(assetId)
        return libraryOf(reference.libraryId).stackMembers(reference.localId)
            .map { it.copy(id = AssetReference(reference.libraryId, it.id).encode(), isOnServer = reference.libraryId != DEVICE_LIBRARY_ID) }
    }

    override suspend fun assetDetail(assetId: String): ImmichAssetDetail {
        val reference = AssetReference.parse(assetId)
        return libraryOf(reference.libraryId).assetDetail(reference.localId)
    }
    override suspend fun setFavorite(assetId: String, isFavorite: Boolean) {
        val reference = AssetReference.parse(assetId)
        libraryOf(reference.libraryId).setFavorite(reference.localId, isFavorite)
    }
    override suspend fun deleteAsset(assetId: String) {
        val reference = AssetReference.parse(assetId)
        libraryOf(reference.libraryId).deleteAsset(reference.localId)
    }
    override suspend fun downloadOriginal(assetId: String, destination: File) {
        val reference = AssetReference.parse(assetId)
        libraryOf(reference.libraryId).downloadOriginal(reference.localId, destination)
    }
}
