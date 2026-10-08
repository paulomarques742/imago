package eu.studio742.imago.core.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions
import eu.studio742.imago.core.model.ImmichPerson
import eu.studio742.imago.core.model.MapMarker
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.AssetPage
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.AlbumAddition
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.ImmichEdit
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
            ImmichKeyPermissions.GET_MY_USER,
        )
        (result["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: error("The server did not return the account identity.")
    }

    override suspend fun validateConnection(connection: ImmichConnection): ServerVersion = withContext(Dispatchers.IO) {
        val version = executeJson<ServerVersionDto>(
            request = Request.Builder().url(endpoint(connection, ImmichContract.GET_SERVER_VERSION)).get().build(),
            apiKey = null,
            permission = null,
        ).toDomain()

        if (version < ImmichCompatibility.minimum) throw ImmichApiException.UnsupportedVersion(version)

        execute(
            request = Request.Builder().url(endpoint(connection, ImmichContract.GET_CURRENT_USER)).get().build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.GET_MY_USER,
        ).close()
        version
    }

    override suspend fun keyPermissions(connection: ImmichConnection): Set<String> = withContext(Dispatchers.IO) {
        // Blocking OkHttp calls ignore coroutine cancellation: the limit has to be the call's own.
        val quick = client.newBuilder().callTimeout(KEY_PERMISSIONS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        executeJson<ApiKeyDto>(
            request = Request.Builder().url(endpoint(connection, ImmichContract.GET_MY_API_KEY)).get().build(),
            apiKey = connection.apiKey,
            permission = null,
            client = quick,
        ).permissions.toSet()
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
        personId: String?,
    ): AssetPage = search(
        connection, page, pageSize, filter, month, albumId, query,
        // The library shows photos and videos; the request does not filter by type so that
        // Immich's `nextPage` keeps counting the same pages the grid goes through.
        requestedType = null, allowedTypes = setOf(AssetType.IMAGE, AssetType.VIDEO), personId = personId,
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
        personId: String? = null,
        visibility: String? = null,
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
            personIds = personId?.let(::listOf),
            visibility = visibility,
        )
        val body = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        val response = executeJson<SearchResponseDto>(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.SEARCH_ASSETS))
                .post(body)
                .build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.SEARCH_ASSETS,
        )
        AssetPage(
            items = response.assets.items.map(AssetResponseDto::toDomain).filter { it.type in allowedTypes },
            nextPage = response.assets.nextPage?.toIntOrNull(),
        )
    }

    override suspend fun trashedAssets(connection: ImmichConnection, page: Int, pageSize: Int): AssetPage = withContext(Dispatchers.IO) {
        val payload = MetadataSearchDto(
            page = page,
            size = pageSize,
            // Photos and videos alike: without a type the search does not filter by one.
            type = null,
            withDeleted = true,
            trashedAfter = "1970-01-01T00:00:00.000Z",
        )
        val response = executeJson<SearchResponseDto>(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.SEARCH_ASSETS))
                .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.SEARCH_ASSETS,
        )
        AssetPage(
            items = response.assets.items.map(AssetResponseDto::toDomain).filter { it.type == AssetType.IMAGE || it.type == AssetType.VIDEO },
            nextPage = response.assets.nextPage?.toIntOrNull(),
        )
    }

    override suspend fun smartSearch(
        connection: ImmichConnection,
        page: Int,
        pageSize: Int,
        query: String,
        albumId: String?,
        favoritesOnly: Boolean,
        takenAfter: String?,
        takenBefore: String?,
        language: String?,
    ): AssetPage = withContext(Dispatchers.IO) {
        val payload = SmartSearchDto(
            query = query,
            page = page,
            size = pageSize,
            albumIds = albumId?.let(::listOf),
            isFavorite = true.takeIf { favoritesOnly },
            takenAfter = takenAfter,
            takenBefore = takenBefore,
            language = language,
        )
        val response = executeJson<SearchResponseDto>(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.SEARCH_SMART))
                .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.SEARCH_SMART,
        )
        AssetPage(
            items = response.assets.items.map(AssetResponseDto::toDomain).filter { it.type == AssetType.IMAGE || it.type == AssetType.VIDEO },
            nextPage = response.assets.nextPage?.toIntOrNull(),
        )
    }

    override suspend fun smartSearchAvailable(connection: ImmichConnection): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            executeJson<ServerFeaturesDto>(
                request = Request.Builder().url(endpoint(connection, ImmichContract.GET_SERVER_FEATURES)).get().build(),
                apiKey = connection.apiKey,
                permission = null,
            ).smartSearch
        }.getOrDefault(false)
    }

    override suspend fun stacks(connection: ImmichConnection): List<eu.studio742.imago.core.model.ImmichStack> =
        withContext(Dispatchers.IO) {
            executeJson<List<StackResponseDto>>(
                request = Request.Builder().url(endpoint(connection, ImmichContract.SEARCH_STACKS)).get().build(),
                apiKey = connection.apiKey,
                permission = ImmichKeyPermissions.SEARCH_STACKS,
            ).map(StackResponseDto::toDomain)
        }

    override suspend fun stack(connection: ImmichConnection, stackId: String): eu.studio742.imago.core.model.ImmichStack =
        withContext(Dispatchers.IO) {
            executeJson<StackResponseDto>(
                request = Request.Builder().url(endpoint(connection, ImmichContract.GET_STACK.replace("{id}", stackId))).get().build(),
                apiKey = connection.apiKey,
                permission = ImmichKeyPermissions.GET_STACK,
            ).toDomain()
        }

    override suspend fun people(connection: ImmichConnection): List<ImmichPerson> = withContext(Dispatchers.IO) {
        val people = mutableListOf<PersonResponseDto>()
        var page = 1
        do {
            val url = endpoint(connection, ImmichContract.GET_ALL_PEOPLE).newBuilder()
                .addQueryParameter("withHidden", "false")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("size", PEOPLE_PAGE.toString())
                .build()
            val response = executeJson<PeopleResponseDto>(
                request = Request.Builder().url(url).get().build(),
                apiKey = connection.apiKey,
                permission = ImmichKeyPermissions.GET_ALL_PEOPLE,
            )
            people += response.people
            page++
        } while (response.hasNextPage && people.size < PEOPLE_LIMIT)
        // The server's order within each group: as in its own app, the people with a name come first.
        people.filterNot { it.isHidden }.sortedBy { it.name.isBlank() }.map { ImmichPerson(it.id, it.name.trim()) }
    }

    /**
     * Asked with the day: v2 takes it as a date-time and v3 only as a date, and a date is what v3
     * accepts. A server that refuses it is asked for all its memories, and the ones showing on [day]
     * are kept here, by the window each one carries.
     */
    override suspend fun onThisDay(connection: ImmichConnection, day: LocalDate): List<ServerDayMemory> = withContext(Dispatchers.IO) {
        fun ask(forDay: Boolean): List<MemoryResponseDto> {
            val url = endpoint(connection, ImmichContract.SEARCH_MEMORIES).newBuilder().apply {
                addQueryParameter("type", "on_this_day")
                if (forDay) addQueryParameter("for", day.toString())
            }.build()
            return executeJson(
                request = Request.Builder().url(url).get().build(),
                apiKey = connection.apiKey,
                permission = ImmichKeyPermissions.SEARCH_MEMORIES,
            )
        }
        val memories = try {
            ask(forDay = true)
        } catch (error: ImmichApiException.Server) {
            if (error.status != 400) throw error
            ask(forDay = false)
        }
        val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val end = day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        memories
            .filter { memory ->
                val shows = memory.showAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                val hides = memory.hideAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                (shows == null || shows < end) && (hides == null || hides > start)
            }
            .mapNotNull { memory ->
                val year = memory.data?.year ?: return@mapNotNull null
                val assets = memory.assets.map(AssetResponseDto::toDomain).filter { it.type == AssetType.IMAGE || it.type == AssetType.VIDEO }
                ServerDayMemory(year, assets).takeIf { assets.isNotEmpty() }
            }
            .groupBy { it.year }
            .map { (year, sameYear) -> ServerDayMemory(year, sameYear.flatMap { it.assets }.distinctBy { it.id }) }
            .sortedByDescending { it.year }
    }

    override suspend fun mapMarkers(connection: ImmichConnection): List<MapMarker> = withContext(Dispatchers.IO) {
        executeJson<List<MapMarkerResponseDto>>(
            request = Request.Builder().url(endpoint(connection, ImmichContract.GET_MAP_MARKERS)).get().build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.GET_MAP_MARKERS,
        ).map { MapMarker(it.id, it.lat, it.lon, it.city?.takeIf(String::isNotBlank)) }
    }

    override fun personThumbnailUrl(connection: ImmichConnection, personId: String): String =
        endpoint(connection, ImmichContract.GET_PERSON_THUMBNAIL.replace("{id}", personId)).toString()

    override suspend fun trashDays(connection: ImmichConnection): Int? = withContext(Dispatchers.IO) {
        runCatching {
            executeJson<ServerConfigDto>(
                request = Request.Builder().url(endpoint(connection, ImmichContract.GET_SERVER_CONFIG)).get().build(),
                apiKey = connection.apiKey,
                permission = null,
            ).trashDays
        }.getOrNull()
    }

    override suspend fun restoreFromTrash(connection: ImmichConnection, assetIds: List<String>): Unit = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext
        execute(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.RESTORE_ASSETS))
                .post(json.encodeToString(BulkIdsDto(assetIds)).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.RESTORE_ASSETS,
        ).close()
    }

    override suspend fun emptyTrash(connection: ImmichConnection): Unit = withContext(Dispatchers.IO) {
        execute(
            request = Request.Builder().url(endpoint(connection, ImmichContract.EMPTY_TRASH)).post(ByteArray(0).toRequestBody(null)).build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.EMPTY_TRASH,
        ).close()
    }

    override suspend fun getAlbums(connection: ImmichConnection): List<ImmichAlbum> = withContext(Dispatchers.IO) {
        fun load(shared: Boolean?): List<AlbumResponseDto> {
            val url = endpoint(connection, ImmichContract.GET_ALBUMS).newBuilder().apply {
                shared?.let { addQueryParameter("shared", it.toString()) }
            }.build()
            return executeJson(
                request = Request.Builder().url(url).get().build(),
                apiKey = connection.apiKey,
                permission = ImmichKeyPermissions.GET_ALL_ALBUMS,
            )
        }
        val albums = (load(null) + load(true)).distinctBy(AlbumResponseDto::id)
        // Who may change each album depends on who is asking. Without the answer the albums still
        // show, only without the actions that change them.
        val me = runCatching { currentUserId(connection) }.getOrNull()
        albums.map { it.toDomain(me) }
    }

    override suspend fun createAlbum(
        connection: ImmichConnection,
        name: String,
        assetIds: List<String>,
    ): ImmichAlbum = withContext(Dispatchers.IO) {
        val body = json.encodeToString(CreateAlbumDto(albumName = name, assetIds = assetIds)).toRequestBody(JSON_MEDIA_TYPE)
        val album = executeJson<AlbumResponseDto>(
            request = Request.Builder().url(endpoint(connection, ImmichContract.CREATE_ALBUM)).post(body).build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.CREATE_ALBUM,
        )
        // Whoever creates it owns it; asking the server who that is would be one more request for
        // an answer already known.
        album.toDomain(null).copy(isOwned = true, canEditContent = true)
    }

    override suspend fun addToAlbum(
        connection: ImmichConnection,
        albumId: String,
        assetIds: List<String>,
    ): AlbumAddition = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext AlbumAddition(0, 0, 0)
        val results = albumAssets(connection, albumId, assetIds, add = true)
        AlbumAddition(
            added = results.count { it.success },
            alreadyThere = results.count { !it.success && it.error == "duplicate" },
            failed = results.count { !it.success && it.error != "duplicate" },
        )
    }

    override suspend fun removeFromAlbum(
        connection: ImmichConnection,
        albumId: String,
        assetIds: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext 0
        albumAssets(connection, albumId, assetIds, add = false).count { it.success }
    }

    /** The same body and answer for adding and removing; only the method and the permission change. */
    private fun albumAssets(
        connection: ImmichConnection,
        albumId: String,
        assetIds: List<String>,
        add: Boolean,
    ): List<BulkIdResponseDto> {
        val path = (if (add) ImmichContract.ADD_ASSETS_TO_ALBUM else ImmichContract.REMOVE_ASSET_FROM_ALBUM).replace("{id}", albumId)
        val body = json.encodeToString(BulkIdsDto(assetIds)).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(endpoint(connection, path)).apply { if (add) put(body) else delete(body) }.build()
        return executeJson(
            request = request,
            apiKey = connection.apiKey,
            permission = if (add) ImmichKeyPermissions.ADD_ASSETS_TO_ALBUM else ImmichKeyPermissions.REMOVE_ASSET_FROM_ALBUM,
        )
    }

    override suspend fun renameAlbum(connection: ImmichConnection, albumId: String, name: String): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(UpdateAlbumDto(albumName = name)).toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.UPDATE_ALBUM_INFO.replace("{id}", albumId)))
                .patch(body)
                .build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.UPDATE_ALBUM_INFO,
        ).close()
    }

    override suspend fun deleteAlbum(connection: ImmichConnection, albumId: String): Unit = withContext(Dispatchers.IO) {
        execute(
            request = Request.Builder().url(endpoint(connection, ImmichContract.DELETE_ALBUM.replace("{id}", albumId))).delete().build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.DELETE_ALBUM,
        ).close()
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
                permission = ImmichKeyPermissions.GET_TIME_BUCKETS,
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
            permission = ImmichKeyPermissions.GET_TIME_BUCKET,
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
            permission = ImmichKeyPermissions.GET_ASSET_INFO,
        ).toDomain()
    }

    override suspend fun setFavorite(
        connection: ImmichConnection,
        assetId: String,
        isFavorite: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        updateAsset(connection, assetId, UpdateAssetDto(isFavorite))
    }

    override suspend fun setArchived(connection: ImmichConnection, assetIds: List<String>, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext
        val body = json.encodeToString(AssetVisibilityUpdateDto(assetIds, if (archived) "archive" else "timeline")).toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder().url(endpoint(connection, ImmichContract.UPDATE_ASSETS)).put(body).build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.UPDATE_ASSETS,
        ).close()
    }

    override suspend fun archivedAssets(connection: ImmichConnection, page: Int, pageSize: Int): AssetPage = search(
        connection, page, pageSize, LibraryFilter.ALL, null, null, null,
        requestedType = null, allowedTypes = setOf(AssetType.IMAGE, AssetType.VIDEO), visibility = "archive",
    )

    override suspend fun setFavorites(
        connection: ImmichConnection,
        assetIds: List<String>,
        isFavorite: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext
        val body = json.encodeToString(AssetBulkUpdateDto(ids = assetIds, isFavorite = isFavorite))
            .toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder().url(endpoint(connection, ImmichContract.UPDATE_ASSETS)).put(body).build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.UPDATE_ASSETS,
        ).close()
    }

    override suspend fun deleteAsset(
        connection: ImmichConnection,
        assetId: String,
        force: Boolean,
    ) = deleteAssets(connection, listOf(assetId), force)

    override suspend fun deleteAssets(
        connection: ImmichConnection,
        assetIds: List<String>,
        force: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext
        val body = json.encodeToString(AssetBulkDeleteDto(ids = assetIds, force = force))
            .toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder()
                .url(endpoint(connection, ImmichContract.DELETE_ASSETS))
                .delete(body)
                .build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.DELETE_ASSETS,
        ).close()
    }

    override suspend fun getAssetEdits(connection: ImmichConnection, assetId: String): List<ImmichEdit> =
        withContext(Dispatchers.IO) {
            executeJson<AssetEditsResponseDto>(
                request = Request.Builder().url(endpoint(connection, assetEditsPath(assetId))).get().build(),
                apiKey = connection.apiKey,
                permission = ImmichKeyPermissions.GET_ASSET_EDITS,
            ).edits.map { it.toDomain() }
        }

    override suspend fun replaceAssetEdits(
        connection: ImmichConnection,
        assetId: String,
        edits: List<ImmichEdit>,
    ): Unit = withContext(Dispatchers.IO) {
        // The server wants at least one edit: none is said with DELETE, not with an empty list.
        if (edits.isEmpty()) {
            execute(
                request = Request.Builder().url(endpoint(connection, assetEditsPath(assetId))).delete().build(),
                apiKey = connection.apiKey,
                permission = ImmichKeyPermissions.REMOVE_ASSET_EDITS,
            ).close()
            return@withContext
        }
        val body = json.encodeToString(AssetEditsCreateDto(edits.map(AssetEditActionItemDto::of)))
            .toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder().url(endpoint(connection, assetEditsPath(assetId))).put(body).build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.EDIT_ASSET,
        ).close()
    }

    private fun assetEditsPath(assetId: String) = ImmichContract.ASSET_EDITS.replace("{id}", assetId)

    private fun updateAsset(connection: ImmichConnection, assetId: String, payload: UpdateAssetDto) {
        val path = ImmichContract.UPDATE_ASSET.replace("{id}", assetId)
        val body = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request = Request.Builder().url(endpoint(connection, path)).put(body).build(),
            apiKey = connection.apiKey,
            permission = ImmichKeyPermissions.UPDATE_ASSET,
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
            permission = ImmichKeyPermissions.DOWNLOAD_ASSET,
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
            permission = ImmichKeyPermissions.UPLOAD_ASSET,
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
                    permission = ImmichKeyPermissions.CREATE_STACK,
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
            permission = ImmichKeyPermissions.UPLOAD_ASSET,
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

    private inline fun <reified T> executeJson(
        request: Request,
        apiKey: String?,
        permission: String?,
        client: OkHttpClient = this.client,
    ): T =
        execute(request, apiKey, permission, client).use { response ->
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

    /**
     * [permission] is what the key needs for this endpoint. Immich answers 401 to a key it does not
     * know and 403 to one without the permission, and only the first means the key is wrong: a key
     * created without a permission on purpose has to hear which one is missing.
     */
    private fun execute(
        request: Request,
        apiKey: String?,
        permission: String?,
        client: OkHttpClient = this.client,
    ): okhttp3.Response {
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
        if (response.code == 403 && permission != null) throw ImmichApiException.MissingPermission(listOf(permission), detail)
        if (response.code == 401 || response.code == 403) throw ImmichApiException.Authentication()
        throw ImmichApiException.Server(response.code, detail)
    }

    private companion object {
        const val KEY_PERMISSIONS_TIMEOUT_MS = 3_000L
        const val PEOPLE_PAGE = 500
        const val PEOPLE_LIMIT = 5_000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}

@Serializable
private data class AssetMediaResponseDto(val id: String, val status: String)

@Serializable
private data class StackCreateDto(val assetIds: List<String>)

@Serializable
private data class AssetEditsCreateDto(val edits: List<AssetEditActionItemDto>)

@Serializable
private data class AssetEditsResponseDto(val edits: List<AssetEditActionItemDto> = emptyList())

/**
 * One edit as it travels. The parameters are a different object for each action, which is why they
 * are read and written by hand from the action instead of through three DTOs and a polymorphic
 * serializer.
 */
@Serializable
private data class AssetEditActionItemDto(val action: String, val parameters: JsonObject) {
    fun toDomain(): ImmichEdit {
        fun number(name: String): Int = parameters[name]?.jsonPrimitive?.doubleOrNull?.roundToLong()?.toInt()
            ?: throw ImmichApiException.Server(200, "invalid response: edit $action without $name")
        return when (action) {
            "crop" -> ImmichEdit.Crop(number("x"), number("y"), number("width"), number("height"))
            "rotate" -> ImmichEdit.Rotate(number("angle"))
            "mirror" -> ImmichEdit.Mirror(
                when (parameters["axis"]?.jsonPrimitive?.contentOrNull) {
                    "horizontal" -> ImmichEdit.MirrorAxis.HORIZONTAL
                    "vertical" -> ImmichEdit.MirrorAxis.VERTICAL
                    else -> throw ImmichApiException.Server(200, "invalid response: mirror without a known axis")
                },
            )
            else -> throw ImmichApiException.Server(200, "invalid response: unknown edit $action")
        }
    }

    companion object {
        fun of(edit: ImmichEdit) = when (edit) {
            is ImmichEdit.Crop -> AssetEditActionItemDto(
                "crop",
                buildJsonObject {
                    put("x", edit.x)
                    put("y", edit.y)
                    put("width", edit.width)
                    put("height", edit.height)
                },
            )
            is ImmichEdit.Rotate -> AssetEditActionItemDto("rotate", buildJsonObject { put("angle", edit.angle) })
            is ImmichEdit.Mirror -> AssetEditActionItemDto(
                "mirror",
                buildJsonObject {
                    put("axis", if (edit.axis == ImmichEdit.MirrorAxis.HORIZONTAL) "horizontal" else "vertical")
                },
            )
        }
    }
}

@Serializable
private data class ApiKeyDto(val permissions: List<String>)

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
    /** With [trashedAfter], what the trash holds; trashed assets are left out otherwise. */
    val withDeleted: Boolean? = null,
    val trashedAfter: String? = null,
    val personIds: List<String>? = null,
    val visibility: String? = null,
)

@Serializable
private data class PeopleResponseDto(val people: List<PersonResponseDto> = emptyList(), val hasNextPage: Boolean = false)

@Serializable
private data class MemoryResponseDto(
    val assets: List<AssetResponseDto> = emptyList(),
    val data: OnThisDayDto? = null,
    val showAt: String? = null,
    val hideAt: String? = null,
)

@Serializable
private data class OnThisDayDto(val year: Int? = null)

@Serializable
private data class MapMarkerResponseDto(val id: String, val lat: Double, val lon: Double, val city: String? = null)

@Serializable
private data class PersonResponseDto(val id: String, val name: String = "", val isHidden: Boolean = false)

@Serializable
private data class ServerConfigDto(val trashDays: Int? = null)

@Serializable
private data class ServerFeaturesDto(val smartSearch: Boolean = false)

@Serializable
private data class SmartSearchDto(
    val query: String,
    val page: Int,
    val size: Int,
    val albumIds: List<String>? = null,
    val isFavorite: Boolean? = null,
    val takenAfter: String? = null,
    val takenBefore: String? = null,
    val language: String? = null,
    val withExif: Boolean? = null,
)

@Serializable
private data class UpdateAssetDto(val isFavorite: Boolean)

@Serializable
private data class AssetBulkUpdateDto(val ids: List<String>, val isFavorite: Boolean)

@Serializable
private data class AssetVisibilityUpdateDto(val ids: List<String>, val visibility: String)

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
    // A number in v2 and an integer in v3: read as a number, so both arrive.
    val exifImageWidth: Double? = null,
    val exifImageHeight: Double? = null,
    val orientation: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val fileSizeInByte: Long? = null,
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
        imageWidth = exifImageWidth?.toInt(),
        imageHeight = exifImageHeight?.toInt(),
        orientation = orientation?.takeIf(String::isNotBlank),
        fileSizeBytes = fileSizeInByte,
        latitude = latitude,
        longitude = longitude,
        city = city?.takeIf(String::isNotBlank),
        state = state?.takeIf(String::isNotBlank),
        country = country?.takeIf(String::isNotBlank),
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
    val visibility: String? = null,
    val isArchived: Boolean? = null,
    val people: List<PersonResponseDto> = emptyList(),
) {
    fun toDomain() = ImmichAssetDetail(
        people = people.filterNot { it.isHidden }.sortedBy { it.name.isBlank() }.map { ImmichPerson(it.id, it.name.trim()) },
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
            isArchived = visibility.equals("archive", ignoreCase = true) || isArchived == true,
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
    /** Up to v2. From v3 the owner is the first of [albumUsers], with the role "owner". */
    val ownerId: String? = null,
    val albumUsers: List<AlbumUserDto> = emptyList(),
) {
    /** [me] is this key's account; without it nothing can be said about who may change the album. */
    fun toDomain(me: String?): ImmichAlbum {
        val owner = ownerId ?: albumUsers.firstOrNull { it.role == "owner" }?.user?.id
        val isOwned = me != null && owner == me
        return ImmichAlbum(
            id = id,
            name = albumName,
            description = description,
            thumbnailAssetId = albumThumbnailAssetId,
            assetCount = assetCount,
            startDate = startDate,
            endDate = endDate,
            shared = shared,
            canEditContent = isOwned || albumUsers.any { it.user.id == me && it.role == "editor" },
            isOwned = isOwned,
        )
    }
}

@Serializable
private data class AlbumUserDto(val role: String, val user: AlbumUserIdDto)

@Serializable
private data class AlbumUserIdDto(val id: String)

@Serializable
private data class CreateAlbumDto(val albumName: String, val assetIds: List<String>)

@Serializable
private data class BulkIdsDto(val ids: List<String>)

@Serializable
private data class BulkIdResponseDto(val id: String, val success: Boolean, val error: String? = null)

@Serializable
private data class UpdateAlbumDto(val albumName: String)

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
    /**
     * `[stackId, assetCount]` for a stack's cover, null for a photo on its own. With `withStacked`
     * only the covers come; the count is how many the stack holds, the cover included.
     */
    val stack: List<List<JsonElement>?> = emptyList(),
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
            stackId = stack.getOrNull(index)?.getOrNull(0)?.let { (it as? JsonPrimitive)?.contentOrNull },
            // The contract declares both as text; read as a number either way.
            stackCount = stack.getOrNull(index)?.getOrNull(1)?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() },
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
private data class StackResponseDto(
    val id: String,
    val primaryAssetId: String,
    val assets: List<AssetResponseDto> = emptyList(),
) {
    fun toDomain() = eu.studio742.imago.core.model.ImmichStack(id, primaryAssetId, assets.map(AssetResponseDto::toDomain))
}

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
    /** "archive" from v2 on; `isArchived` is the older form of the same answer. */
    val visibility: String? = null,
    val isArchived: Boolean? = null,
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
        isArchived = visibility.equals("archive", ignoreCase = true) || isArchived == true,
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
