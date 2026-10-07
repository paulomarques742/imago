package eu.studio742.imago.core.data

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions
import eu.studio742.imago.core.immich.requirePermission
import eu.studio742.imago.core.model.LibraryFilter

@Singleton
class ImmichCompositionMediaRepository @Inject constructor(
    private val configuration: ConfigurationRepository,
    private val api: ImmichApi,
    private val library: LibraryRepository,
) : CompositionMediaRepository {
    override fun media(query: String?) = library.assets(LibraryFilter.ALL, query = query)
    override fun thumbnailUrl(assetId: String) = library.thumbnailUrl(assetId)
    override fun previewUrl(assetId: String) = library.previewUrl(assetId)
    override fun videoPlaybackUrl(assetId: String) = library.videoPlaybackUrl(assetId)
    override fun apiKey(assetId: String) = library.apiKey(assetId)
    override suspend fun downloadOriginal(assetId: String, destination: File) = library.downloadOriginal(assetId, destination)
    override suspend fun checkCanUpload(targetLibraryId: String) =
        api.requirePermission(configuration.source(targetLibraryId).connection(), ImmichKeyPermissions.UPLOAD_ASSET)
    override suspend fun uploadComposition(targetLibraryId: String, file: File, fileName: String, mimeType: String, createdAt: String) {
        api.uploadAsset(configuration.source(targetLibraryId).connection(), file, fileName, mimeType, createdAt)
    }
}
