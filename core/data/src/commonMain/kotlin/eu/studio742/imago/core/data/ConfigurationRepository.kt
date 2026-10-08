package eu.studio742.imago.core.data

import kotlinx.coroutines.flow.Flow
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.ServerVersion

interface ConfigurationRepository {
    val libraries: kotlinx.coroutines.flow.StateFlow<List<eu.studio742.imago.core.model.LibrarySource>>
    val selectedLibraryId: kotlinx.coroutines.flow.StateFlow<String>
    fun selectLibrary(id: String)

    /**
     * The server library the unified one joins the phone with: the one chosen in Settings, or the
     * first connected. Null when there is none, and then there is no unified library either.
     */
    val unifiedPartnerId: String?
        get() = libraries.value.firstOrNull { !it.isDevice && !it.isUnified && it.isConnected }?.id

    fun setUnifiedPartner(id: String) = Unit
    fun source(id: String): eu.studio742.imago.core.model.LibrarySource
    suspend fun saveLibrary(id: String?, name: String, serverUrl: String, apiKey: String): ServerVersion
    suspend fun testLibrary(id: String): ServerVersion

    /**
     * The optional permissions this key was created without, in Immich's names. Empty when it has them
     * all or when they could not be read: it is a notice, never a reason to refuse the key.
     */
    suspend fun missingOptionalPermissions(connection: ImmichConnection): List<String> = emptyList()

    /**
     * Adds an address to an Immich library that is already linked.
     *
     * It is only accepted if it answers with a supported version and returns the same account with
     * the library's key: an address of another server or another account is refused.
     */
    suspend fun addServerUrl(id: String, serverUrl: String): ServerVersion
    fun removeServerUrl(id: String, serverUrl: String)

    /** Moves an address [offset] positions in the order of preference. */
    fun moveServerUrl(id: String, serverUrl: String, offset: Int)

    /** The result of the last test of each address, per library: `libraryId → url → answered`. */
    val endpointProbes: kotlinx.coroutines.flow.StateFlow<Map<String, Map<String, Boolean>>>

    /** Chooses again the active address of the libraries with more than one. */
    suspend fun refreshEndpoints()
    fun removeLibrary(id: String)
    var lastExportLibraryId: String?

    /**
     * Whether the first launch is done: the choice between the device only and an Immich server.
     *
     * An install that already had configuration saved from before this screen existed counts as
     * done — whoever already uses the app does not have to choose again.
     */
    val welcomeCompleted: kotlinx.coroutines.flow.StateFlow<Boolean>
    fun completeWelcome()

    /**
     * The app version whose news the person was last shown, or that was installed fresh. Null in
     * an install from before it was recorded.
     */
    var lastSeenAppVersion: String?
    val connection: Flow<ImmichConnection?>
    fun currentConnection(): ImmichConnection?
    suspend fun validateAndSave(serverUrl: String, apiKey: String): ServerVersion
    suspend fun clear()
}

fun ConfigurationRepository.activeSource(): kotlinx.coroutines.flow.Flow<eu.studio742.imago.core.model.LibrarySource> =
    kotlinx.coroutines.flow.combine(selectedLibraryId, libraries) { id, sources ->
        sources.firstOrNull { it.id == id && it.isConnected }
            ?: sources.first { it.isDevice }
    }
