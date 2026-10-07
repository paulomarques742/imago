package eu.studio742.imago.core.data

import androidx.paging.PagingData
import java.io.File
import kotlinx.coroutines.flow.Flow
import eu.studio742.imago.core.model.ImmichAsset

interface CompositionMediaRepository {
    fun media(query: String? = null): Flow<PagingData<ImmichAsset>>
    fun thumbnailUrl(assetId: String): String
    fun previewUrl(assetId: String): String
    fun videoPlaybackUrl(assetId: String): String
    fun apiKey(assetId: String): String
    suspend fun downloadOriginal(assetId: String, destination: File)

    /** Fails with the permission the library's key lacks to upload, before anything is rendered. */
    suspend fun checkCanUpload(targetLibraryId: String) = Unit
    suspend fun uploadComposition(targetLibraryId: String, file: File, fileName: String, mimeType: String, createdAt: String)
}
