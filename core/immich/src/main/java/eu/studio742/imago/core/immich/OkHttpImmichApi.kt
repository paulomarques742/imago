package eu.studio742.imago.core.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import eu.studio742.imago.core.immich.generated.ImmichContract
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.AssetPage
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.RECENT_FILTER_DAYS
import eu.studio742.imago.core.model.ServerVersion
import java.io.IOException
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.roundToLong

class OkHttpImmichApi(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : ImmichApi {

    override suspend fun currentUserId(connection: ImmichConnection): String = withContext(Dispatchers.IO) {
        val result = executeJson<kotlinx.serialization.json.JsonObject>(
            Request.Builder().url(endpoint(connection, ImmichContract.GET_CURRENT_USER)).get().build(), connection.apiKey,
        )
        (result["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: error("The server did not return the account identity.")
    }

    override suspend fun validateConnection(connection: ImmichConnection): ServerVersion = withContext(Dispatchers.IO) {
        val version = executeJson<ServerVersionDto>(
            request = Request.Builder().url(endpoint(connection, ImmichContract.GET_SERVER_VERSION)).get().build(),
            apiKey = null,
        ).toDomain()

        if (version < ImmichCompatibility.minimum) throw ImmichApiException.UnsupportedVersion(version)

        execute(
            request = Request.Builder().url(endpoint(connection, ImmichContract.GET_CURRENT_USER)).get().build(),
            apiKey = connection.apiKey,
        ).close()
        version
    }

    override suspend fun ping(serverUrl: String, timeoutMillis: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = endpoint(ImmichConnection(serverUrl, ""), ImmichContract.PING_SERVER)
            // Without the app's interceptors: a failed ping is not a connection failure to report.
            val quick = client.newBuilder()
                .apply { interceptors().clear() }
                .callTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
            quick.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                response.isSuccessful && response.body?.string().orEmpty().contains("pong")
            }
        }.getOrDefault(false)
    }

    override suspend fun searchAssets(
        connection: ImmichConnection,
        page: Int,
        pageSize: Int,
        filter: LibraryFilter,
        month: String?,
        albumId: String?,
        query: String?,
    ): AssetPage = search(
        connection, page, pageSize, filter, month, albumId, query,
        // The library shows photos and videos; the request does not filter by type so that
        // Immich's `nextPage` keeps counting the same pages the grid goes through.
        requestedType = null, allowedTypes = setOf(AssetType.IMAGE, AssetType.VIDEO),
    )

    override suspend fun searchMedia(
        connection: ImmichConnection,
        page: Int,
        pageSize: Int,
        types: Set<AssetType>,
        query: String?,
    ): AssetPage = search(
        connection, page, pageSize, LibraryFilter.ALL, null, null, query,
        requestedType = null, allowedTypes = types,
    )

    private suspend fun search(
        connection: ImmichConnection,
        page: Int,
        pageSize: Int,
        filter: LibraryFilter,
        month: String?,
        albumId: String?,
        query: String?,
        requestedType: String?,
        allowedTypes: Set<AssetType>,
    ): AssetPage = withContext(Dispatchers.IO) {
        val monthStart = month?.let(LocalDate::parse)?.withDayOfMonth(1)
        // A chosen month is always more specific than "recent"; if both are active, the month
        // decides the range.
        val recentStart = LocalDate.now().minusDays(RECENT_FILTER_DAYS)
            .takeIf { filter == LibraryFilter.RECENT && monthStart == null }
        val payload = MetadataSearchDto(
            page = page,
            size = pageSize,
            isFavorite = true.takeIf { filter == LibraryFilter.FAVORITES },
            albumIds = albumId?.let(::listOf),
            takenAfter = (monthStart ?: recentStart)?.let { "${it}T00:00:00.000Z" },
            takenBefore = monthStart?.plusMonths(1)?.let { "${it}T00:00:00.000Z" },
            originalFileName = query?.takeIf(String::isNotBlank),
            type = requestedType,
        )
        val body = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        val response = executeJson<SearchResponseDto>(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.SEARCH_ASSETS))
                .post(body)
                .build(),
            apiKey = connection.apiKey,
        )
        AssetPage(
            items = response.assets.items.map(AssetResponseDto::toDomain).filter { it.type in allowedTypes },
            nextPage = response.assets.nextPage?.toIntOrNull(),
        )
    }

    override suspend fun getAlbums(connection: ImmichConnection): List<ImmichAlbum> = withContext(Dispatchers.IO) {
        fun load(shared: Boolean?): List<AlbumResponseDto> {
            val url = endpoint(connection, ImmichContract.GET_ALBUMS).newBuilder().apply {
                shared?.let { addQueryParameter("shared", it.toString()) }
            }.build()
            return executeJson(
                request = Request.Builder().url(url).get().build(),
                apiKey = connection.apiKey,
            )
        }
        (load(null) + load(true)).distinctBy(AlbumResponseDto::id).map { album ->
            ImmichAlbum(
                id = album.id,
                name = album.albumName,
                description = album.description,
                thumbnailAssetId = album.albumThumbnailAssetId,
                assetCount = album.assetCount,
                startDate = album.startDate,
                endDate = album.endDate,
                shared = album.shared,
            )
        }
    }

    override suspend fun getTimeBuckets(connection: ImmichConnection): List<ImmichTimeBucket> =
        withContext(Dispatchers.IO) {
            val url = endpoint(connection, ImmichContract.GET_TIME_BUCKETS).newBuilder()
                .addQueryParameter("order", ImmichContract.ASSET_ORDER_DESC)
                .addQueryParameter("withStacked", "true")
                .build()
            executeJson<List<TimeBucketDto>>(
                request = Request.Builder().url(url).get().build(),
                apiKey = connection.apiKey,
            ).map { ImmichTimeBucket(month = it.timeBucket, assetCount = it.count) }
        }

    /**
     * The whole month in columns.
     *
     * The same parameters as [getTimeBuckets] — `order` and `withStacked` — because the counts from
     * there are what tells whether this month is already in sync: requests with different filters
     * gave different numbers and the month never settled.
     */
    override suspend fun getTimeBucketAssets(
        connection: ImmichConnection,
        timeBucket: String,
    ): List<ImmichAsset> = withContext(Dispatchers.IO) {
        val url = endpoint(connection, ImmichContract.GET_TIME_BUCKET).newBuilder()
            .addQueryParameter("timeBucket", timeBucket)
            .addQueryParameter("order", ImmichContract.ASSET_ORDER_DESC)
            .addQueryParameter("withStacked", "true")
            .build()
        executeJson<TimeBucketAssetsDto>(
            request = Request.Builder().url(url).get().build(),
            apiKey = connection.apiKey,
        ).toDomain()
    }

    override suspend fun getAssetDetail(
        connection: ImmichConnection,
        assetId: String,
    ): ImmichAssetDetail = withContext(Dispatchers.IO) {
        val path = ImmichContract.GET_ASSET_INFO.replace("{id}", assetId)
        executeJson<AssetDetailResponseDto>(
            request = Request.Builder().url(endpoint(connection, path)).get().build(),
            apiKey = connection.apiKey,
        ).toDomain()
    }

    override suspend fun setFavorite(
        connection: ImmichConnection,
        assetId: String,
        isFavorite: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        updateAsset(connection, assetId, UpdateAssetDto(isFavorite))
    }

    override suspend fun deleteAsset(
        connection: ImmichConnection,
        assetId: String,
        force: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(AssetBulkDeleteDto(ids = listOf(assetId), force = force))
            .toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.DELETE_ASSETS))
                .delete(body)
                .build(),
            apiKey = connection.apiKey,
        ).close()
    }

    private fun updateAsset(connection: ImmichConnection, assetId: String, payload: UpdateAssetDto) {
        val path = ImmichContract.UPDATE_ASSET.replace("{id}", assetId)
        val body = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder().url(endpoint(connection, path)).put(body).build(),
            apiKey = connection.apiKey,
        ).close()
    }

    override fun thumbnailUrl(connection: ImmichConnection, assetId: String): String =
        assetUrl(connection, assetId, "thumbnail")

    override fun previewUrl(connection: ImmichConnection, assetId: String): String =
        assetUrl(connection, assetId, "preview")

    override fun videoPlaybackUrl(connection: ImmichConnection, assetId: String): String {
        val path = ImmichContract.PLAY_ASSET_VIDEO.replace("{id}", assetId)
        return endpoint(connection, path).toString()
    }

    override suspend fun downloadOriginal(
        connection: ImmichConnection,
        assetId: String,
        destination: File,
    ): Unit = withContext(Dispatchers.IO) {
        val path = ImmichContract.DOWNLOAD_ASSET.replace("{id}", assetId)
        execute(
            request = Request.Builder().url(endpoint(connection, path)).get().build(),
            apiKey = connection.apiKey,
        ).use { response ->
            val body = checkNotNull(response.body) { "Immich returned an empty original." }
            destination.outputStream().buffered().use { output -> body.byteStream().copyTo(output) }
        }
    }

    override suspend fun exportEditedAsset(
        connection: ImmichConnection,
        originalAssetId: String,
        jpeg: File,
        fileName: String,
        fileCreatedAt: String,
    ): ImmichExportResult = withContext(Dispatchers.IO) {
        val version = validateConnection(connection)
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("assetData", fileName, jpeg.asRequestBody(JPEG_MEDIA_TYPE))
            .addFormDataPart("fileCreatedAt", fileCreatedAt)
            .addFormDataPart("fileModifiedAt", Instant.now().toString())
            .addFormDataPart("filename", fileName)
        if (version.major < 3) {
            builder
                .addFormDataPart("deviceAssetId", "immichroom-$originalAssetId")
                .addFormDataPart("deviceId", "immichroom-android")
        }
        val uploaded = executeJson<AssetMediaResponseDto>(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.UPLOAD_ASSET))
                .post(builder.build())
                .build(),
            apiKey = connection.apiKey,
        )
        val canStack = uploaded.status != "duplicate" && uploaded.id != originalAssetId
        val stacked = if (canStack) {
            runCatching {
                val body = json.encodeToString(StackCreateDto(listOf(originalAssetId, uploaded.id)))
                    .toRequestBody(JSON_MEDIA_TYPE)
                execute(
                    request = Request.Builder()
                        .url(endpoint(connection, ImmichContract.CREATE_STACK))
                        .post(body)
                        .build(),
                    apiKey = connection.apiKey,
                ).close()
            }.isSuccess
        } else {
            false
        }
        ImmichExportResult(
            assetId = uploaded.id,
            status = uploaded.status,
            stackedWithOriginal = stacked,
            stackingFailed = canStack && !stacked,
        )
    }

    override suspend fun uploadAsset(
        connection: ImmichConnection,
        file: File,
        fileName: String,
        mimeType: String,
        fileCreatedAt: String,
    ): ImmichUploadResult = withContext(Dispatchers.IO) {
        val version = validateConnection(connection)
        // A type coming from another app may be malformed; the server decides by the extension.
        val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("assetData", fileName, file.asRequestBody(mediaType))
            .addFormDataPart("fileCreatedAt", fileCreatedAt)
            .addFormDataPart("fileModifiedAt", Instant.now().toString())
            .addFormDataPart("filename", fileName)
        if (version.major < 3) {
            builder
                .addFormDataPart("deviceAssetId", "immichroom-upload-${java.util.UUID.randomUUID()}")
                .addFormDataPart("deviceId", "immichroom-android")
        }
        val uploaded = executeJson<AssetMediaResponseDto>(
            request = Request.Builder().url(endpoint(connection, ImmichContract.UPLOAD_ASSET)).post(builder.build()).build(),
            apiKey = connection.apiKey,
        )
        ImmichUploadResult(uploaded.id, uploaded.status)
    }

    private fun assetUrl(connection: ImmichConnection, assetId: String, size: String): String {
        val path = ImmichContract.VIEW_ASSET.replace("{id}", assetId)
        return endpoint(connection, path).newBuilder().addQueryParameter("size", size).build().toString()
    }

    private fun endpoint(connection: ImmichConnection, path: String): HttpUrl {
        val base = normalizeServerUrl(connection.serverUrl)
        val apiPath = "${ImmichContract.API_BASE.trim('/')}/${path.trimStart('/')}"
        return base.resolve(apiPath) ?: throw ImmichApiException.InvalidUrl()
    }

    private inline fun <reified T> executeJson(request: Request, apiKey: String?): T =
        execute(request, apiKey).use { response ->
            val payload = response.body?.string().orEmpty()
            try {
                json.decodeFromString<T>(payload)
            } catch (error: Exception) {
                // The reason goes along. "Invalid response" alone does not tell a field that was
                // renamed from a whole contract that changed shape, and that difference is what
                // decides whether the problem is ours or the server version's.
                throw ImmichApiException.Server(
                    response.code,
                    "invalid response: ${error.message?.take(200)}",
                )
            }
        }

    private fun execute(request: Request, apiKey: String?): okhttp3.Response {
        val authenticated = request.newBuilder().apply {
            if (!apiKey.isNullOrBlank()) header(ImmichContract.API_KEY_HEADER, apiKey)
        }.build()

        val response = try {
            client.newCall(authenticated).execute()
        } catch (error: IOException) {
            throw ImmichApiException.Connection(error)
        }

        if (response.isSuccessful) return response

        val detail = response.body?.string().orEmpty().take(240)
        response.close()
        if (response.code == 401 || response.code == 403) throw ImmichApiException.Authentication()
        throw ImmichApiException.Server(response.code, detail)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}

@Serializable
private data class AssetMediaResponseDto(val id: String, val status: String)

@Serializable
private data class StackCreateDto(val assetIds: List<String>)

@Serializable
private data class ServerVersionDto(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    fun toDomain() = ServerVersion(major, minor, patch)
}

@Serializable
private data class MetadataSearchDto(
    val page: Int,
    val size: Int,
    val order: String = "desc",
    val type: String? = "IMAGE",
    val isFavorite: Boolean? = null,
    val withStacked: Boolean = true,
    val albumIds: List<String>? = null,
    val takenAfter: String? = null,
    val takenBefore: String? = null,
    val originalFileName: String? = null,
)

@Serializable
private data class UpdateAssetDto(val isFavorite: Boolean)

@Serializable
private data class AssetBulkDeleteDto(val ids: List<String>, val force: Boolean)

@Serializable
private data class ExifResponseDto(
    val fNumber: Float? = null,
    val exposureTime: String? = null,
    val iso: Int? = null,
    val focalLength: Float? = null,
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val description: String? = null,
) {
    fun toDomain() = AssetExif(
        fNumber = fNumber,
        exposureTime = exposureTime,
        iso = iso,
        focalLength = focalLength,
        make = make?.takeIf(String::isNotBlank),
        model = model?.takeIf(String::isNotBlank),
        lensModel = lensModel?.takeIf(String::isNotBlank),
        description = description?.takeIf(String::isNotBlank),
    )
}

/**
 * Immich's same `AssetResponseDto`, but requested by id and with `exifInfo` filled in.
 *
 * It is kept apart from the search DTO because `description` lives in `exifInfo` in the response
 * and at the top level of the write request — merging the two would only make both harder to read.
 */
@Serializable
private data class AssetDetailResponseDto(
    val id: String,
    val checksum: String = "",
    val originalFileName: String,
    val fileCreatedAt: String,
    val localDateTime: String = "",
    val width: Long? = null,
    val height: Long? = null,
    val isFavorite: Boolean = false,
    val isEdited: Boolean = false,
    val type: String,
    val originalMimeType: String? = null,
    val duration: JsonElement? = null,
    val exifInfo: ExifResponseDto? = null,
) {
    fun toDomain() = ImmichAssetDetail(
        asset = ImmichAsset(
            id = id,
            checksum = checksum,
            originalFileName = originalFileName,
            fileCreatedAt = fileCreatedAt,
            localDateTime = localDateTime,
            width = width,
            height = height,
            isFavorite = isFavorite,
            isEdited = isEdited,
            type = when (type.uppercase()) {
                "IMAGE" -> AssetType.IMAGE
                "VIDEO" -> AssetType.VIDEO
                "AUDIO" -> AssetType.AUDIO
                else -> AssetType.OTHER
            },
            mimeType = originalMimeType,
            durationMs = duration.toDurationMs(),
        ),
        exif = exifInfo?.toDomain() ?: AssetExif(),
    )
}

@Serializable
private data class AlbumResponseDto(
    val id: String,
    val albumName: String,
    val description: String = "",
    val albumThumbnailAssetId: String? = null,
    val assetCount: Int = 0,
    val startDate: String? = null,
    val endDate: String? = null,
    val shared: Boolean = false,
)

@Serializable
private data class TimeBucketDto(val timeBucket: String, val count: Int)

/**
 * The month in columns, as Immich serves it.
 *
 * Each vector has one entry per photo and they all share the same index. The missing fields — file
 * name and checksum — do not exist in this response; they are left blank and filled in when the
 * photo is opened, which is when someone needs them.
 */
@Serializable
private data class TimeBucketAssetsDto(
    val id: List<String> = emptyList(),
    val fileCreatedAt: List<String> = emptyList(),
    val isFavorite: List<Boolean> = emptyList(),
    val isImage: List<Boolean> = emptyList(),
    val isTrashed: List<Boolean> = emptyList(),
    // A number of milliseconds in one version, "0:00:12.000" in another — and both show up on the
    // same server depending on what is in the month. It stays raw and `toDurationMs` decides.
    val duration: List<JsonElement?> = emptyList(),
    val ratio: List<Double?> = emptyList(),
    val localOffsetHours: List<Double?> = emptyList(),
    val visibility: List<String?> = emptyList(),
) {
    fun toDomain(): List<ImmichAsset> = id.indices.mapNotNull { index ->
        // Trash and archive do not go into the grid; letting them in here would put them there
        // through the back door, without going through the search that filters them out.
        if (isTrashed.getOrNull(index) == true) return@mapNotNull null
        val visible = visibility.getOrNull(index)
        if (visible != null && !visible.equals("timeline", ignoreCase = true)) return@mapNotNull null
        val createdAt = fileCreatedAt.getOrNull(index) ?: return@mapNotNull null
        val image = isImage.getOrNull(index) ?: true
        // The aspect ratio is what the grid needs to reserve the tile; the real dimensions come
        // with the detail. A thousand is just the scale at which the ratio is stored without losing precision.
        val ratioValue = ratio.getOrNull(index)?.takeIf { it.isFinite() && it > 0.0 }
        ImmichAsset(
            id = id[index],
            checksum = "",
            originalFileName = "",
            fileCreatedAt = createdAt,
            localDateTime = localDateTimeOf(createdAt, localOffsetHours.getOrNull(index)),
            width = ratioValue?.let { (it * BUCKET_RATIO_SCALE).toLong() },
            height = ratioValue?.let { BUCKET_RATIO_SCALE.toLong() },
            isFavorite = isFavorite.getOrNull(index) ?: false,
            isEdited = false,
            type = if (image) AssetType.IMAGE else AssetType.VIDEO,
            durationMs = duration.getOrNull(index).toDurationMs(),
        )
    }
}

/**
 * The scale at which the bucket's aspect ratio is stored as width and height.
 *
 * A file constant and not one of the DTO: a private `companion` inside a private class is
 * inaccessible to the code Coil and coroutines generate around it, and the error only shows up at
 * runtime.
 */
private const val BUCKET_RATIO_SCALE = 1_000.0

/**
 * The local time the photo was taken, which is how Immich groups the months.
 *
 * Without this, a photo taken at 11 pm in Lisbon on the last day of the month ended up, for us, in
 * the next month — and the per-month reconciliation deleted it on every sync.
 */
private fun localDateTimeOf(fileCreatedAt: String, offsetHours: Double?): String = runCatching {
    val utc = OffsetDateTime.parse(fileCreatedAt)
    val minutes = ((offsetHours ?: 0.0) * 60).roundToLong()
    utc.plusMinutes(minutes).toLocalDateTime().toString()
}.getOrDefault("")

@Serializable
private data class SearchResponseDto(val assets: SearchAssetResponseDto)

@Serializable
private data class SearchAssetResponseDto(
    val items: List<AssetResponseDto>,
    val nextPage: String?,
)

@Serializable
private data class AssetResponseDto(
    val id: String,
    val checksum: String = "",
    val originalFileName: String,
    val fileCreatedAt: String,
    val localDateTime: String,
    val width: Long?,
    val height: Long?,
    val isFavorite: Boolean,
    val isEdited: Boolean,
    val type: String,
    val originalMimeType: String? = null,
    val duration: JsonElement? = null,
) {
    fun toDomain() = ImmichAsset(
        id = id,
        checksum = checksum,
        originalFileName = originalFileName,
        fileCreatedAt = fileCreatedAt,
        localDateTime = localDateTime,
        width = width,
        height = height,
        isFavorite = isFavorite,
        isEdited = isEdited,
        type = when (type.uppercase()) {
            "IMAGE" -> AssetType.IMAGE
            "VIDEO" -> AssetType.VIDEO
            "AUDIO" -> AssetType.AUDIO
            else -> AssetType.OTHER
        },
        mimeType = originalMimeType,
        durationMs = duration.toDurationMs(),
    )
}

/**
 * A video's duration in milliseconds, whether it comes as a number or as "0:00:12.000".
 *
 * `internal` and not `private`: the month bucket converts it from inside a lambda, which Kotlin
 * compiles to another class — and from there a private function of this file gives
 * `IllegalAccessError` at runtime, without the compiler saying anything.
 */
internal fun JsonElement?.toDurationMs(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    val value = primitive.contentOrNull ?: return null
    val parts = value.split(':')
    if (parts.size != 3) return value.toLongOrNull()
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    return ((hours * 3_600 + minutes * 60) * 1_000 + seconds * 1_000).toLong()
}
