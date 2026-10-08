package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.ImmichApiException
import eu.studio742.imago.core.immich.ImmichExportResult
import eu.studio742.imago.core.immich.ImmichUploadResult
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.AssetPage
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.ImmichEdit
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.LibrarySource
import eu.studio742.imago.core.model.ServerVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImmichGeometryMirrorTest {
    private val api = FakeEditsApi()
    private val logged = mutableListOf<String>()
    private val mirror = ImmichGeometryMirror(api, FakeLibraries, logged::add)

    private fun recipe(geometry: Geometry, libraryId: String = LIBRARY_ID) = EditRecipe(
        assetId = AssetReference(libraryId, "a1").encode(),
        originalChecksum = "",
        createdAt = "2026-10-07T00:00:00Z",
        updatedAt = "2026-10-07T00:00:00Z",
        geometry = geometry,
    )

    @Test
    fun writesTheRecipeGeometryToTheServer() = runBlocking {
        mirror.mirror(recipe(Geometry(rotation = 90, mirrorH = true)))

        assertEquals(
            listOf(ImmichEdit.Rotate(90), ImmichEdit.Mirror(ImmichEdit.MirrorAxis.VERTICAL)),
            api.edits,
        )
    }

    @Test
    fun theCropIsInPixelsOfTheSizeTheServerKnows() = runBlocking {
        api.exif = AssetExif(imageWidth = 4000, imageHeight = 3000, orientation = "6")

        mirror.mirror(recipe(Geometry(cropRect = CropRect(0f, 0f, 0.5f, 0.5f))))

        assertEquals(listOf<ImmichEdit>(ImmichEdit.Crop(0, 0, 1500, 2000)), api.edits)
    }

    /** Asking the server again for the same geometry writes nothing. */
    @Test
    fun anEqualServerIsLeftAlone() = runBlocking {
        api.edits = listOf(ImmichEdit.Rotate(180))

        mirror.mirror(recipe(Geometry(rotation = 180)))

        assertEquals(0, api.writes)
    }

    /** The local recipe wins, and the difference is logged. */
    @Test
    fun aDivergentServerIsRewrittenAndLogged() = runBlocking {
        api.edits = listOf(ImmichEdit.Crop(10, 10, 100, 100))

        mirror.mirror(recipe(Geometry()))

        assertEquals(emptyList<ImmichEdit>(), api.edits)
        assertTrue(logged.single().startsWith("divergence"))
    }

    @Test
    fun aDevicePhotoNeverReachesTheServer() = runBlocking {
        mirror.mirror(recipe(Geometry(rotation = 90), libraryId = DEVICE_LIBRARY_ID))

        assertEquals(0, api.reads)
    }

    @Test
    fun aVideoIsNotEdited() = runBlocking {
        api.type = AssetType.VIDEO

        mirror.mirror(recipe(Geometry(rotation = 90)))

        assertEquals(0, api.writes)
    }

    /** A key without the edit permissions leaves the editor working: it is logged, never thrown. */
    @Test
    fun aRefusedWriteDoesNotFailTheEditor() = runBlocking {
        api.refuseWrites = true

        mirror.mirror(recipe(Geometry(rotation = 90)))

        assertTrue(logged.single().startsWith("could not mirror"))
    }

    private class FakeEditsApi : ImmichApi {
        var edits: List<ImmichEdit> = emptyList()
        var exif = AssetExif(imageWidth = 4000, imageHeight = 3000, orientation = "1")
        var type = AssetType.IMAGE
        var refuseWrites = false
        var reads = 0
        var writes = 0

        override suspend fun getAssetDetail(connection: ImmichConnection, assetId: String): ImmichAssetDetail {
            reads++
            return ImmichAssetDetail(
                asset = ImmichAsset(assetId, "", "a.jpg", "", "", null, null, isFavorite = false, isEdited = false, type = type),
                exif = exif,
            )
        }

        override suspend fun getAssetEdits(connection: ImmichConnection, assetId: String): List<ImmichEdit> = edits

        override suspend fun replaceAssetEdits(connection: ImmichConnection, assetId: String, edits: List<ImmichEdit>) {
            if (refuseWrites) throw ImmichApiException.MissingPermission(listOf("asset.edit.create"), "")
            writes++
            this.edits = edits
        }

        override suspend fun validateConnection(connection: ImmichConnection) = unused()
        override suspend fun ping(serverUrl: String, timeoutMillis: Long) = unused()
        override suspend fun searchAssets(
            connection: ImmichConnection,
            page: Int,
            pageSize: Int,
            filter: LibraryFilter,
            month: String?,
            albumId: String?,
            query: String?,
        ): AssetPage = unused()
        override suspend fun searchMedia(
            connection: ImmichConnection,
            page: Int,
            pageSize: Int,
            types: Set<AssetType>,
            query: String?,
        ): AssetPage = unused()
        override suspend fun getAlbums(connection: ImmichConnection): List<ImmichAlbum> = unused()
        override suspend fun getTimeBuckets(connection: ImmichConnection): List<ImmichTimeBucket> = unused()
        override suspend fun getTimeBucketAssets(connection: ImmichConnection, timeBucket: String): List<ImmichAsset> = unused()
        override suspend fun setFavorite(connection: ImmichConnection, assetId: String, isFavorite: Boolean) = unused()
        override suspend fun deleteAsset(connection: ImmichConnection, assetId: String, force: Boolean) = unused()
        override fun thumbnailUrl(connection: ImmichConnection, assetId: String): String = unused()
        override fun previewUrl(connection: ImmichConnection, assetId: String): String = unused()
        override fun videoPlaybackUrl(connection: ImmichConnection, assetId: String): String = unused()
        override suspend fun downloadOriginal(connection: ImmichConnection, assetId: String, destination: File) = unused()
        override suspend fun exportEditedAsset(
            connection: ImmichConnection,
            originalAssetId: String,
            jpeg: File,
            fileName: String,
            fileCreatedAt: String,
        ): ImmichExportResult = unused()
        override suspend fun uploadAsset(
            connection: ImmichConnection,
            file: File,
            fileName: String,
            mimeType: String,
            fileCreatedAt: String,
        ): ImmichUploadResult = unused()
    }

    private object FakeLibraries : ConfigurationRepository {
        private val library = LibrarySource(LIBRARY_ID, "Home", listOf("https://example.test"), apiKey = "secret")
        override val libraries: StateFlow<List<LibrarySource>> = MutableStateFlow(listOf(library))
        override val selectedLibraryId: StateFlow<String> = MutableStateFlow(LIBRARY_ID)
        override fun source(id: String): LibrarySource = library
        override fun selectLibrary(id: String) = unused()
        override suspend fun saveLibrary(id: String?, name: String, serverUrl: String, apiKey: String): ServerVersion = unused()
        override suspend fun testLibrary(id: String): ServerVersion = unused()
        override suspend fun addServerUrl(id: String, serverUrl: String): ServerVersion = unused()
        override fun removeServerUrl(id: String, serverUrl: String) = unused()
        override fun moveServerUrl(id: String, serverUrl: String, offset: Int) = unused()
        override val endpointProbes: StateFlow<Map<String, Map<String, Boolean>>> = MutableStateFlow(emptyMap())
        override suspend fun refreshEndpoints() = unused()
        override fun removeLibrary(id: String) = unused()
        override var lastExportLibraryId: String? = null
        override val welcomeCompleted: StateFlow<Boolean> = MutableStateFlow(true)
        override fun completeWelcome() = unused()
        override var lastSeenAppVersion: String? = null
        override val connection: Flow<ImmichConnection?> = flowOf(null)
        override fun currentConnection(): ImmichConnection? = null
        override suspend fun validateAndSave(serverUrl: String, apiKey: String): ServerVersion = unused()
        override suspend fun clear() = unused()
    }

    private companion object {
        const val LIBRARY_ID = "home"
    }
}

private fun unused(): Nothing = error("not used by the mirror")
