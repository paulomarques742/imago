package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.requireUser
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.*
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SourceLibraryRepository @Inject constructor(
    private val configuration: ConfigurationRepository,
    private val database: ImmichRoomDatabase,
    private val api: ImmichApi,
    private val device: DeviceLibrary,
) : LibraryRepository {
    private val providers = mutableMapOf<LibrarySource, LibraryRepository>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized private fun provider(id: String): LibraryRepository {
        if (id == DEVICE_LIBRARY_ID) return device
        if (id == OPENED_LIBRARY_ID) return checkNotNull(device.openedFiles) { "Nothing opens files here" }
        if (id == UNIFIED_LIBRARY_ID) return unified()
        val source = configuration.source(id)
        requireUser(source.isConnected, UserMessage.LIBRARY_DISCONNECTED, source.messageName())
        return providers.getOrPut(source) {
            val bound = SourceConfiguration(configuration, source)
            RoomLibraryRepository(database, bound, api, RoomDerivedAssetRepository(database, bound))
        }
    }
    private var unifiedLibrary: Pair<String, UnifiedLibrary>? = null

    /** The unified library with the partner chosen now; a new one when the partner changes. */
    @Synchronized private fun unified(): UnifiedLibrary {
        val partner = configuration.unifiedPartnerId ?: throw UserMessageException(UserMessage.LIBRARY_DISCONNECTED, listOf(""))
        unifiedLibrary?.takeIf { it.first == partner }?.let { return it.second }
        return UnifiedLibrary(database, device, provider(partner), partner).also { unifiedLibrary = partner to it }
    }

    private val unifiedSelected get() = configuration.selectedLibraryId.value == UNIFIED_LIBRARY_ID

    override suspend fun counterpartOf(assetId: String): String? = if (unifiedSelected) unified().counterpartOf(assetId) else null

    override val catalogSync: StateFlow<CatalogSyncState> = configuration.activeSource()
        .flatMapLatest { provider(it.id).catalogSync }.stateIn(scope, SharingStarted.Eagerly, CatalogSyncState())
    override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?): Flow<PagingData<ImmichAsset>> {
        val id = configuration.selectedLibraryId.value
        if (id == UNIFIED_LIBRARY_ID) return unified().assets(filter, month, albumId, query)
        return provider(id).assets(filter, month, albumId?.let { AssetReference.parse(it).localId }, query)
            .map { page -> page.map { it.copy(id = AssetReference(id, it.id).encode()) } }
    }
    override suspend fun albums(): List<ImmichAlbum> {
        val id = configuration.selectedLibraryId.value
        if (id == UNIFIED_LIBRARY_ID) return unified().albums()
        return provider(id).albums().map { it.encodedFor(id) }
    }
    /** In the unified library a new album with photos of the server is the server's. */
    override suspend fun createAlbum(name: String, assetIds: List<String>): ImmichAlbum {
        val id = configuration.selectedLibraryId.value.let { if (it == UNIFIED_LIBRARY_ID) unifiedPartner() else it }
        return provider(id).createAlbum(name, localIdsIn(id, assetIds)).encodedFor(id)
    }

    private fun unifiedPartner() =
        configuration.unifiedPartnerId ?: throw UserMessageException(UserMessage.LIBRARY_DISCONNECTED, listOf(""))

    override suspend fun addToAlbum(albumId: String, assetIds: List<String>): AlbumAddition {
        val album = AssetReference.parse(albumId)
        return provider(album.libraryId).addToAlbum(album.localId, localIdsIn(album.libraryId, assetIds))
    }

    override suspend fun removeFromAlbum(albumId: String, assetIds: List<String>): Int {
        val album = AssetReference.parse(albumId)
        return provider(album.libraryId).removeFromAlbum(album.localId, localIdsIn(album.libraryId, assetIds))
    }

    override suspend fun renameAlbum(albumId: String, name: String): String {
        val album = AssetReference.parse(albumId)
        return AssetReference(album.libraryId, provider(album.libraryId).renameAlbum(album.localId, name)).encode()
    }

    override suspend fun deleteAlbum(albumId: String) {
        val album = AssetReference.parse(albumId); provider(album.libraryId).deleteAlbum(album.localId)
    }

    override suspend fun fileIntoAlbum(albumId: String, assetIds: List<String>, transfer: FolderTransfer): AlbumAddition {
        val album = AssetReference.parse(albumId)
        return provider(album.libraryId).fileIntoAlbum(album.localId, localIdsIn(album.libraryId, assetIds), transfer)
    }

    // Only this device has folder albums, whichever library shows its photos: its own or the unified one.
    override suspend fun createFolderAlbum(name: String, assetIds: List<String>, transfer: FolderTransfer, place: String?): ImmichAlbum =
        device.createFolderAlbum(name, localIdsIn(DEVICE_LIBRARY_ID, assetIds), transfer, place).encodedFor(DEVICE_LIBRARY_ID)

    override suspend fun albumPlaces(assetIds: List<String>): List<AlbumPlace> = device.albumPlaces(localIdsIn(DEVICE_LIBRARY_ID, assetIds))

    override suspend fun contentSearchAvailable(): Boolean =
        runCatching { provider(configuration.selectedLibraryId.value).contentSearchAvailable() }.getOrDefault(false)

    override fun searchByContent(query: String, filter: LibraryFilter, month: String?, albumId: String?): Flow<PagingData<ImmichAsset>> {
        val id = configuration.selectedLibraryId.value
        if (id == UNIFIED_LIBRARY_ID) return unified().searchByContent(query, filter, month, albumId)
        return provider(id).searchByContent(query, filter, month, albumId?.let { AssetReference.parse(it).localId })
            .map { page -> page.map { it.copy(id = AssetReference(id, it.id).encode()) } }
    }

    override val hasTrash: Boolean
        get() = runCatching { provider(configuration.selectedLibraryId.value).hasTrash }.getOrDefault(false)

    override suspend fun trash(): TrashContents {
        val id = configuration.selectedLibraryId.value
        val contents = provider(id).trash()
        return contents.copy(items = contents.items.map { it.copy(asset = it.asset.copy(id = AssetReference(id, it.asset.id).encode())) })
    }

    override suspend fun restoreFromTrash(assetIds: List<String>) = byLibrary(assetIds) { ids -> restoreFromTrash(ids) }
    override suspend fun deleteForever(assetIds: List<String>) = byLibrary(assetIds) { ids -> deleteForever(ids) }
    override suspend fun emptyTrash() = provider(configuration.selectedLibraryId.value).emptyTrash()

    /** An album lives in one library: a photo of another one could never go into it. */
    private fun localIdsIn(libraryId: String, assetIds: List<String>): List<String> =
        assetIds.map(AssetReference::parse).filter { it.libraryId == libraryId }.map { it.localId }

    private fun ImmichAlbum.encodedFor(libraryId: String) = copy(
        id = AssetReference(libraryId, id).encode(),
        thumbnailAssetId = thumbnailAssetId?.let { AssetReference(libraryId, it).encode() },
    )

    override suspend fun timeBuckets() = provider(configuration.selectedLibraryId.value).timeBuckets()
    override suspend fun syncCatalog() = provider(configuration.selectedLibraryId.value).syncCatalog()
    override suspend fun loadMonth(month: String) = provider(configuration.selectedLibraryId.value).loadMonth(month)
    override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? {
        if (unifiedSelected) return unified().indexOfAsset(assetId, filter, month, query)
        val ref = AssetReference.parse(assetId)
        return provider(ref.libraryId).indexOfAsset(ref.localId, filter, month, query)
    }
    override suspend fun indexOfDate(date: LocalDate, filter: LibraryFilter, month: String?, query: String?) =
        provider(configuration.selectedLibraryId.value).indexOfDate(date, filter, month, query)
    // A removed source yields an unresolvable URI in previews, not a composition-time exception.
    private fun location(assetId: String, get: LibraryRepository.(String) -> String): String {
        val ref = AssetReference.parse(assetId)
        return runCatching { provider(ref.libraryId).get(ref.localId) }.getOrDefault("imago-unavailable://media")
    }
    override fun thumbnailUrl(assetId: String) = location(assetId) { thumbnailUrl(it) }
    override fun previewUrl(assetId: String) = location(assetId) { previewUrl(it) }
    override fun videoPlaybackUrl(assetId: String) = location(assetId) { videoPlaybackUrl(it) }
    override fun apiKey(assetId: String): String = configuration.source(AssetReference.parse(assetId).libraryId).apiKey.orEmpty()
    override suspend fun assetDetail(assetId: String): ImmichAssetDetail {
        val ref = AssetReference.parse(assetId)
        val detail = provider(ref.libraryId).assetDetail(ref.localId)
        return detail.copy(asset = detail.asset.copy(id = assetId))
    }
    override suspend fun setFavorite(assetId: String, isFavorite: Boolean) {
        val ref = AssetReference.parse(assetId); provider(ref.libraryId).setFavorite(ref.localId, isFavorite)
    }
    override suspend fun deleteAsset(assetId: String) {
        val ref = AssetReference.parse(assetId); provider(ref.libraryId).deleteAsset(ref.localId)
    }
    override suspend fun setFavorites(assetIds: List<String>, isFavorite: Boolean) =
        byLibrary(assetIds) { ids -> setFavorites(ids, isFavorite) }
    override suspend fun deleteAssets(assetIds: List<String>) = byLibrary(assetIds) { ids -> deleteAssets(ids) }

    /** A selection can mix libraries; each one gets its own ids in a single call. */
    private suspend fun byLibrary(assetIds: List<String>, action: suspend LibraryRepository.(List<String>) -> Unit) {
        assetIds.map(AssetReference::parse).groupBy { it.libraryId }.forEach { (libraryId, refs) ->
            provider(libraryId).action(refs.map { it.localId })
        }
    }
    override suspend fun checkCanDelete(assetId: String) {
        val ref = AssetReference.parse(assetId); provider(ref.libraryId).checkCanDelete(ref.localId)
    }
    override suspend fun downloadOriginal(assetId: String, destination: File) {
        val ref = AssetReference.parse(assetId); provider(ref.libraryId).downloadOriginal(ref.localId, destination)
    }
}
