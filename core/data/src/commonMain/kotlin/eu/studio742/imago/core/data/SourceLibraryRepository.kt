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
        val source = configuration.source(id)
        requireUser(source.isConnected, UserMessage.LIBRARY_DISCONNECTED, source.messageName())
        return providers.getOrPut(source) {
            val bound = SourceConfiguration(configuration, source)
            RoomLibraryRepository(database, bound, api, RoomDerivedAssetRepository(database, bound))
        }
    }
    override val catalogSync: StateFlow<CatalogSyncState> = configuration.activeSource()
        .flatMapLatest { provider(it.id).catalogSync }.stateIn(scope, SharingStarted.Eagerly, CatalogSyncState())
    override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?): Flow<PagingData<ImmichAsset>> {
        val id = configuration.selectedLibraryId.value
        return provider(id).assets(filter, month, albumId?.let { AssetReference.parse(it).localId }, query)
            .map { page -> page.map { it.copy(id = AssetReference(id, it.id).encode()) } }
    }
    override suspend fun albums(): List<ImmichAlbum> {
        val id = configuration.selectedLibraryId.value
        return provider(id).albums().map { it.copy(id = AssetReference(id, it.id).encode(),
            thumbnailAssetId = it.thumbnailAssetId?.let { raw -> AssetReference(id, raw).encode() }) }
    }
    override suspend fun timeBuckets() = provider(configuration.selectedLibraryId.value).timeBuckets()
    override suspend fun syncCatalog() = provider(configuration.selectedLibraryId.value).syncCatalog()
    override suspend fun loadMonth(month: String) = provider(configuration.selectedLibraryId.value).loadMonth(month)
    override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? {
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
    override suspend fun downloadOriginal(assetId: String, destination: File) {
        val ref = AssetReference.parse(assetId); provider(ref.libraryId).downloadOriginal(ref.localId, destination)
    }
}
