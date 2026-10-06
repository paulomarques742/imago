package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.requireUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.ImmichApiException
import eu.studio742.imago.core.immich.canonicalServerUrl
import eu.studio742.imago.core.model.*
import eu.studio742.imago.core.sync.EndpointSelector
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class EncryptedConfigurationRepository(
    private val preferences: SecurePreferences,
    private val api: ImmichApi,
    private val database: eu.studio742.imago.core.data.db.ImmichRoomDatabase? = null,
    endpointTriggers: Flow<EndpointTrigger> = emptyFlow(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ConfigurationRepository {
    private val json = Json { ignoreUnknownKeys = true }
    // The libraries the app names, and not the person — the device, earlier ones, unknown ones —
    // keep no name: the interface names them in the language it is in (`LibrarySource.displayName`).
    private val device = LibrarySource(DEVICE_LIBRARY_ID, "")

    // The flag is always saved, `true` or `false`: the first `persist()` writes the library list
    // right away, and without the flag set explicitly the next launch mistook a new install for an
    // old one. It is only missing altogether in an install from before the screen existed — and
    // there, with libraries or the old key saved, the app has already been used.
    private val welcome = MutableStateFlow(
        preferences.getString(WELCOME_KEY)?.toBooleanStrictOrNull()
            ?: (preferences.getString("libraries_v2") != null || preferences.getString("server_url") != null),
    )
    override val welcomeCompleted = welcome.asStateFlow()
    private val sources = MutableStateFlow(readSources())
    override val libraries = sources.asStateFlow()
    private val selected = MutableStateFlow(preferences.getString("selected_library")
        ?.takeIf { id -> sources.value.any { it.id == id && it.isConnected } }
        ?: sources.value.firstOrNull { !it.isDevice && it.isConnected }?.id ?: DEVICE_LIBRARY_ID)
    override val selectedLibraryId = selected.asStateFlow()
    private val state = MutableStateFlow(currentConnection())
    override val connection = state.asStateFlow()
    private val probes = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())
    override val endpointProbes = probes.asStateFlow()
    private val selector = EndpointSelector { url -> api.ping(url) }
    private val selecting = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var lastFailureRefresh = 0L

    init {
        persist()
        if (database != null) CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val ids = kotlinx.coroutines.withContext(Dispatchers.IO) { database.libraryIdentityDao().ids() }
            val missing = ids.filter { id -> id != GLOBAL_LIBRARY_ID && sources.value.none { it.id == id } }
            if (missing.isNotEmpty()) {
                sources.value += missing.map { LibrarySource(it, "") }
                persist()
            }
        }
        scope.launch {
            endpointTriggers
                // A failing request fires again on every attempt; without this brake, a library
                // without a network spent its time testing addresses.
                .filter { trigger ->
                    trigger != EndpointTrigger.CONNECTION_FAILED ||
                        (clock() - lastFailureRefresh >= FAILURE_REFRESH_INTERVAL_MS).also { if (it) lastFailureRefresh = clock() }
                }
                .debounce(500)
                .collect { runCatching { refreshEndpoints() } }
        }
        if (sources.value.any { it.serverUrls.size > 1 }) scope.launch { runCatching { refreshEndpoints() } }
        scope.launch { learnMissingIdentities() }
    }

    /**
     * A library from before the account identity was stored does not link to the IMAGO account, and
     * its edits do not go up. The key already there is enough to ask Immich whose it is; the local
     * key stays the same, and without a network it tries again on the next launch.
     */
    private suspend fun learnMissingIdentities() {
        for (source in sources.value.filter { !it.isDevice && it.isConnected && it.userId == null }) {
            val userId = runCatching { api.currentUserId(source.connection()) }.getOrNull() ?: continue
            // Another library already is this account: the same identity on both would link them to the same remote library.
            if (sources.value.any { it.id != source.id && it.userId == userId }) continue
            sources.update { list -> list.map { if (it.id == source.id && it.userId == null) it.copy(userId = userId) else it } }
            persist()
        }
    }
    override fun source(id: String) = sources.value.firstOrNull { it.id == id }
        ?: LibrarySource(id, "")
    override fun currentConnection() = source(selected.value).takeIf { !it.isDevice && it.isConnected }?.connection()
    override fun selectLibrary(id: String) {
        require(source(id).isConnected)
        selected.value = id
        state.value = currentConnection()
        persist()
    }
    override var lastExportLibraryId: String?
        get() = preferences.getString("export_library")
        set(value) { preferences.write(mapOf("export_library" to value)) }

    override fun completeWelcome() {
        check(preferences.write(mapOf(WELCOME_KEY to "true")))
        welcome.value = true
    }

    override suspend fun saveLibrary(id: String?, name: String, serverUrl: String, apiKey: String): ServerVersion {
        requireUser(name.isNotBlank(), UserMessage.LIBRARY_NAME_REQUIRED)
        val url = canonicalServerUrl(serverUrl)
        val candidate = ImmichConnection(url, apiKey.trim())
        val version = api.validateConnection(candidate)
        val userId = api.currentUserId(candidate)
        // A legacy profile has no stored user ID. Verify its existing credential before
        // matching it by URL, otherwise replacing it with another account would reuse edits.
        val candidates = sources.value.map { existing ->
            if (!existing.isDevice && existing.userId == null && existing.isConnected && url in existing.canonicalUrls()) {
                val identity = if (existing.apiKey == candidate.apiKey) userId else api.currentUserId(existing.connection())
                existing.copy(userId = identity)
            } else existing
        }
        val old = id?.let { editingId -> candidates.firstOrNull { it.id == editingId } }
        requireUser(old?.userId == null || old.userId == userId, UserMessage.KEY_OTHER_ACCOUNT)
        // The library is the account, not the address: the same account through another address is
        // the same library, and the new address joins the ones it already had.
        val match = candidates.firstOrNull { !it.isDevice && it.userId == userId }
        requireUser(match == null || id == null || match.id == id, UserMessage.LIBRARY_ALREADY_CONFIGURED)
        val base = old ?: match
        val key = base?.id ?: libraryKeyOf("immich|$userId")
        val urls = base?.serverUrls.orEmpty().let { known -> if (url in known.map(::canonicalServerUrl)) known else known + url }
        val source = LibrarySource(key, name.trim(), urls, userId, apiKey.trim(), activeUrl = url)
        database?.libraryIdentityDao()?.insert(eu.studio742.imago.core.data.db.LibraryIdentityEntity(key))
        sources.value = candidates.filterNot { it.id == key } + source
        probes.update { it + (key to (it[key].orEmpty() + (url to true))) }
        state.value = currentConnection()
        persist()
        return version
    }
    override suspend fun testLibrary(id: String) = api.validateConnection(source(id).connection())

    override suspend fun addServerUrl(id: String, serverUrl: String): ServerVersion {
        val source = source(id)
        requireUser(!source.isDevice && source.isConnected, UserMessage.LIBRARY_RECONNECT)
        val url = canonicalServerUrl(serverUrl)
        requireUser(url !in source.canonicalUrls(), UserMessage.ADDRESS_ALREADY_IN_LIBRARY)
        val connection = ImmichConnection(url, checkNotNull(source.apiKey), id)
        val version = try {
            api.validateConnection(connection)
        } catch (error: ImmichApiException.Authentication) {
            throw UserMessageException(UserMessage.ADDRESS_OTHER_ACCOUNT, cause = error)
        }
        val expected = source.userId ?: runCatching { api.currentUserId(source.connection()) }.getOrNull()
            ?: throw UserMessageException(UserMessage.ACCOUNT_UNCONFIRMED)
        requireUser(api.currentUserId(connection) == expected, UserMessage.ADDRESS_WRONG_SERVER)
        sources.update { list -> list.map { if (it.id == id) it.copy(serverUrls = it.serverUrls + url, userId = expected) else it } }
        probes.update { it + (id to (it[id].orEmpty() + (url to true))) }
        persist()
        refreshEndpoints()
        return version
    }

    override fun removeServerUrl(id: String, serverUrl: String) {
        val source = source(id)
        require(serverUrl in source.serverUrls)
        requireUser(source.serverUrls.size > 1, UserMessage.LIBRARY_NEEDS_ADDRESS)
        sources.update { list -> list.map { if (it.id == id) it.copy(serverUrls = it.serverUrls - serverUrl) else it } }
        probes.update { it + (id to (it[id].orEmpty() - serverUrl)) }
        state.value = currentConnection()
        persist()
        scope.launch { runCatching { refreshEndpoints() } }
    }

    override fun moveServerUrl(id: String, serverUrl: String, offset: Int) {
        val urls = source(id).serverUrls.toMutableList()
        val from = urls.indexOf(serverUrl)
        require(from >= 0)
        urls.add((from + offset).coerceIn(0, urls.lastIndex), urls.removeAt(from))
        sources.update { list -> list.map { if (it.id == id) it.copy(serverUrls = urls) else it } }
        persist()
        scope.launch { runCatching { refreshEndpoints() } }
    }

    override suspend fun refreshEndpoints() = selecting.withLock {
        for (source in sources.value.filter { !it.isDevice && it.isConnected && it.serverUrls.size > 1 }) {
            val selection = selector.select(source.serverUrls)
            probes.update { it + (source.id to selection.probes) }
            // With none answering, the last one that worked stays: it is the most likely to come back.
            val active = selection.active ?: continue
            sources.update { list -> list.map { if (it.id == source.id) it.copy(activeUrl = active) else it } }
        }
        state.value = currentConnection()
        persist()
    }

    override fun removeLibrary(id: String) {
        require(id != DEVICE_LIBRARY_ID)
        sources.value = sources.value.map { if (it.id == id) it.copy(apiKey = null) else it }
        if (selected.value == id) selected.value = DEVICE_LIBRARY_ID
        state.value = currentConnection()
        persist()
    }
    override suspend fun validateAndSave(serverUrl: String, apiKey: String): ServerVersion {
        val version = saveLibrary(null, "Immich", serverUrl, apiKey)
        selectLibrary(sources.value.last().id)
        return version
    }
    override suspend fun clear() { if (selected.value != DEVICE_LIBRARY_ID) removeLibrary(selected.value) }
    private fun readSources(): List<LibrarySource> {
        val saved = preferences.getString("libraries_v2")
        if (saved != null) {
            val active = preferences.getString("active_urls")
                ?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty()
            return json.decodeFromString<List<LibrarySource>>(upgradeStoredSources(saved))
                .map { it.withoutLegacyName().copy(activeUrl = active[it.id]) }
        }
        val url = preferences.getString("server_url") ?: return listOf(device)
        val key = preferences.getString("api_key") ?: return listOf(device)
        return listOf(device, LibrarySource(libraryKeyOf(url), "Immich", listOf(url), apiKey = key))
    }
    @Synchronized private fun persist() {
        val active = sources.value.mapNotNull { source -> source.activeUrl?.let { source.id to it } }.toMap()
        check(preferences.write(mapOf(
            "libraries_v2" to json.encodeToString(sources.value),
            "active_urls" to json.encodeToString(active),
            "selected_library" to selected.value,
            "server_url" to null,
            "api_key" to null,
            WELCOME_KEY to welcome.value.toString(),
        )))
    }

    private companion object {
        const val FAILURE_REFRESH_INTERVAL_MS = 30_000L
        const val WELCOME_KEY = "welcome_completed"
    }
}

/**
 * Older versions saved the names the app gave in Portuguese — "Dispositivo" for the device and
 * "Biblioteca anterior · <id>" for libraries found only in the database. Those names now come from the
 * interface, in the app language, so the saved ones go. A name the person typed is never touched.
 */
internal fun LibrarySource.withoutLegacyName(): LibrarySource = when {
    isDevice -> copy(name = "")
    !isConnected && name.startsWith(LEGACY_PREVIOUS_LIBRARY_NAME) -> copy(name = "")
    else -> this
}

private const val LEGACY_PREVIOUS_LIBRARY_NAME = "Biblioteca anterior · "

private fun LibrarySource.canonicalUrls() = serverUrls.map { runCatching { canonicalServerUrl(it) }.getOrDefault(it) }

/**
 * The list saved before a library could have several addresses kept a single one, in `serverUrl`.
 * It becomes a one-element list; the rest of the record stays as it was.
 */
internal fun upgradeStoredSources(raw: String): String {
    val stored = Json.parseToJsonElement(raw) as? JsonArray ?: return raw
    return JsonArray(stored.map { entry ->
        val source = entry as? JsonObject ?: return@map entry
        val legacy = source["serverUrl"] as? JsonPrimitive
        when {
            "serverUrls" in source -> JsonObject(source - "serverUrl")
            legacy != null && legacy.isString -> JsonObject(source - "serverUrl" + ("serverUrls" to JsonArray(listOf(legacy))))
            else -> JsonObject(source - "serverUrl")
        }
    }).toString()
}

/** A provider captures one source; changing the selector cannot redirect an in-flight request. */
internal class SourceConfiguration(
    private val parent: ConfigurationRepository,
    private val source: LibrarySource,
) : ConfigurationRepository by parent {
    override fun currentConnection() = source.connection()
    override val connection = MutableStateFlow<ImmichConnection?>(source.connection()).asStateFlow()
}
