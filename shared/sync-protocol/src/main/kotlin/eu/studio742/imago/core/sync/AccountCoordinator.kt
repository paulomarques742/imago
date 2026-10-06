package eu.studio742.imago.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A local library, as the account sees it. [localKey] never leaves the device; it is the key under
 * which the link to the remote library is stored.
 */
data class AccountLibrary(
    val localKey: String,
    val name: String,
    val kind: Kind,
    /** The account in the photo service; null for old Immich libraries, which stay unlinked. */
    val accountUserId: String? = null,
) {
    enum class Kind { DEVICE, IMMICH }
}

/** What the account needs from each platform: where it stores things and what signing out does to the data. */
interface AccountPlatform {
    /** This device's registration: the id and the account it was made in. */
    fun storedDevice(): StoredDevice?
    fun storeDevice(device: StoredDevice)
    fun storedDeviceName(): String?
    fun storeDeviceName(name: String)

    suspend fun libraryLink(localKey: String): String?
    suspend fun saveLibraryLink(localKey: String, remoteLibraryId: String)
    suspend fun forgetLibraryLink(localKey: String)

    /** "Keep a copy": the data stays as local data, without the account's revisions or links. */
    suspend fun detachFromAccount()

    /** "Remove the account data from this device". */
    suspend fun removeAccountData()

    fun log(message: String)
}

data class StoredDevice(val deviceId: String, val userId: String)

sealed interface AccountLinkState {
    data object Idle : AccountLinkState
    data object Linking : AccountLinkState

    /** Registered; [withoutIdentity] are the old Immich libraries that were left unlinked. */
    data class Linked(val deviceId: String, val withoutIdentity: List<String> = emptyList()) : AccountLinkState
    /**
     * Registering the device, or linking some libraries, failed. [libraries] are the ones left
     * unlinked (empty if it was the device); [reason] is the account's reason, when there was one.
     */
    data class Failed(
        val libraries: List<String> = emptyList(),
        val reason: AccountException.Reason? = null,
    ) : AccountLinkState
}

/**
 * The IMAGO account, the same on Android and desktop: signing in, signing out, this device and the
 * others.
 *
 * While there is a session, this device is registered and every library with an identity is linked
 * to its remote library — including those linked after signing in. The device library is
 * identified by `device|<id>`, the Immich one by an HMAC of the Immich account with the IMAGO
 * account; neither carries an address.
 */
class AccountCoordinator(
    private val backend: SyncBackend,
    private val platform: AccountPlatform,
    libraries: Flow<List<AccountLibrary>>,
    defaultDeviceName: String,
    private val platformName: String,
    private val scope: CoroutineScope,
) {
    val isAvailable: Boolean get() = backend.isAvailable
    val session: StateFlow<AccountSession?> = backend.session

    private val name = MutableStateFlow(platform.storedDeviceName() ?: defaultDeviceName)

    /** This device's name, as it appears on conflicting copies and in the device list. */
    val deviceName: StateFlow<String> = name.asStateFlow()

    private val recovery = MutableStateFlow(false)

    /** A recovery link was opened: the interface asks for the new password. */
    val passwordRecoveryPending: StateFlow<Boolean> = recovery.asStateFlow()

    private val link = MutableStateFlow<AccountLinkState>(AccountLinkState.Idle)

    /**
     * Where this device's registration and the library links stand. The device list only makes
     * sense after [AccountLinkState.Linked]: before that this device is not in it yet.
     */
    val linkState: StateFlow<AccountLinkState> = link.asStateFlow()

    /** The account the current [linkState] refers to. */
    @Volatile private var linkedUser: String? = null
    private val latestLibraries = MutableStateFlow<List<AccountLibrary>>(emptyList())
    private val attaching = Mutex()

    init {
        scope.launch {
            combine(backend.session, libraries) { session, current -> session to current }
                .collectLatest { (session, current) ->
                    latestLibraries.value = current
                    // Without network it stays in error, and tries again when the session or the
                    // libraries change, or when someone asks.
                    if (session != null) attach(session, current) else resetLink()
                }
        }
    }

    /** This device's id in the signed-in account, if it is already registered. */
    val currentDeviceId: String?
        get() = platform.storedDevice()?.takeIf { it.userId == session.value?.userId }?.deviceId

    suspend fun signUp(email: String, password: String): SignUpResult {
        val result = backend.signUp(email.trim(), password)
        awaitLink()
        return result
    }

    suspend fun signIn(email: String, password: String) {
        backend.signIn(email.trim(), password)
        awaitLink()
    }

    /**
     * Waits for the registration and the linking that the new session triggers. There is a single
     * path for them — the one that follows the session and the libraries — so that linking never
     * happens with a list of libraries that has not arrived yet.
     */
    private suspend fun awaitLink() {
        val user = backend.session.value?.userId ?: return
        withTimeoutOrNull(LINK_TIMEOUT_MS) {
            link.first { (it is AccountLinkState.Linked || it is AccountLinkState.Failed) && linkedUser == user }
        }
    }

    suspend fun sendPasswordReset(email: String) = backend.sendPasswordReset(email.trim())

    suspend fun updatePassword(password: String) {
        backend.updatePassword(password)
        recovery.value = false
    }

    fun dismissPasswordRecovery() { recovery.value = false }

    /** Handles a link opened in the app. `true` if it was an authentication link. */
    fun handleAuthLink(uri: String): Boolean {
        val link = backend.handleAuthLink(uri) ?: return false
        if (link == AuthLink.RECOVERY) recovery.value = true
        return true
    }

    /** Volta a tentar registar o aparelho e ligar as bibliotecas. */
    fun retryLink() {
        val current = session.value ?: return
        scope.launch { attach(current, latestLibraries.value) }
    }

    suspend fun devices(): List<RemoteDevice> = try {
        backend.devices().sortedByDescending { it.lastSeenAt }
    } catch (error: Exception) {
        if (error !is CancellationException) platform.log("Reading the devices failed: ${describe(error)}")
        throw error
    }

    suspend fun renameDevice(newName: String) {
        val trimmed = newName.trim()
        require(trimmed.length in 1..80) { "The device name has to be between 1 and 80 characters." }
        currentDeviceId?.let { backend.touchDevice(it, trimmed) }
        platform.storeDeviceName(trimmed)
        name.value = trimmed
    }

    suspend fun removeDevice(deviceId: String) {
        require(deviceId != currentDeviceId) { "To remove this device, sign out of the account." }
        backend.removeDevice(deviceId)
    }

    /**
     * The account data in a ZIP, with one JSON per entity. It comes from the server: it is what the
     * account stores, not just what this device has.
     */
    suspend fun exportData(now: String = Instant.now().toString()): ByteArray {
        val current = session.value ?: throw AccountException(AccountException.Reason.NOT_SIGNED_IN, "Sign in to export the data.")
        val pretty = Json { prettyPrint = true }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, content: JsonElement) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(pretty.encodeToString(JsonElement.serializer(), content).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("conta.json", buildJsonObject {
                put("email", current.email?.let(::JsonPrimitive) ?: JsonNull)
                put("exportadoEm", JsonPrimitive(now))
                put("formato", JsonPrimitive(1))
            })
            entry("aparelhos.json", JsonArray(backend.devices().map { device ->
                buildJsonObject {
                    put("id", JsonPrimitive(device.id))
                    put("nome", JsonPrimitive(device.name))
                    put("plataforma", JsonPrimitive(device.platform))
                    put("criadoEm", JsonPrimitive(device.createdAt))
                    put("vistoEm", JsonPrimitive(device.lastSeenAt))
                }
            }))
            entry("bibliotecas.json", JsonArray(backend.libraries()))
            for (entity in SyncEntity.entries) {
                val rows = mutableListOf<JsonElement>()
                var cursor = 0L
                while (true) {
                    val page = backend.pull(entity, cursor, EXPORT_PAGE)
                    page.rows.mapTo(rows) { row ->
                        buildJsonObject {
                            put("key", JsonPrimitive(row.key))
                            put("revision", JsonPrimitive(row.revision))
                            put("payload", row.payload)
                            put("editedAt", JsonPrimitive(row.editedAt))
                            put("editedByDevice", row.editedByDevice?.let(::JsonPrimitive) ?: JsonNull)
                            put("deletedAt", row.deletedAt?.let(::JsonPrimitive) ?: JsonNull)
                            row.hints?.let { put("hints", it) }
                        }
                    }
                    if (page.rows.size < EXPORT_PAGE || page.cursor == cursor) break
                    cursor = page.cursor
                }
                entry("${entity.wireName.lowercase()}.json", JsonArray(rows))
            }
        }
        return bytes.toByteArray()
    }

    /**
     * Deletes the account: asks for the password again — it cannot be undone, and the server only
     * accepts a recent sign-in —, deletes on the server and then does "Sign out · Keep a copy".
     */
    suspend fun deleteAccount(password: String) {
        val email = session.value?.email
            ?: throw AccountException(AccountException.Reason.NOT_SIGNED_IN, "Sign in to delete the account.")
        backend.signIn(email, password)
        backend.deleteAccount()
        // The session no longer has an account behind it; signing out only clears what was left on this device.
        runCatching { backend.signOut() }
        resetLink()
        platform.detachFromAccount()
    }

    /**
     * Signs out. [keepCopy] leaves the data on this device as local data; otherwise the account
     * data leaves this device. Libraries and keys always stay.
     */
    suspend fun signOut(keepCopy: Boolean) {
        backend.signOut()
        resetLink()
        if (keepCopy) platform.detachFromAccount() else platform.removeAccountData()
    }

    /**
     * Registers the device and links the libraries. Never throws: the result goes to [linkState],
     * and a library that fails does not stop the next ones.
     */
    private fun resetLink() {
        linkedUser = null
        link.value = AccountLinkState.Idle
    }

    private suspend fun attach(session: AccountSession, libraries: List<AccountLibrary>) = attaching.withLock {
        linkedUser = session.userId
        link.value = AccountLinkState.Linking
        val deviceId = try {
            ensureDevice(session)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            platform.log("Registering the device failed: ${describe(error)}")
            link.value = AccountLinkState.Failed(reason = (error as? AccountException)?.reason)
            return@withLock
        }
        val withoutIdentity = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (library in libraries) {
            if (platform.libraryLink(library.localKey) != null) continue
            val (provider, fingerprint) = when {
                library.kind == AccountLibrary.Kind.DEVICE -> LibraryFingerprint.DEVICE to LibraryFingerprint.device(deviceId)
                // A library from before the account identity was stored has nothing to identify
                // itself with; it is linked once it is saved again and gains the identity.
                library.accountUserId != null ->
                    LibraryFingerprint.IMMICH to LibraryFingerprint.immich(session.userId, library.accountUserId)
                else -> {
                    platform.log("Library ${library.localKey.take(8)} has no account identity; left unlinked")
                    withoutIdentity += library.name
                    continue
                }
            }
            val displayName = if (library.kind == AccountLibrary.Kind.DEVICE) name.value else library.name
            try {
                val deviceForLibrary = deviceId.takeIf { library.kind == AccountLibrary.Kind.DEVICE }
                platform.saveLibraryLink(library.localKey, backend.linkLibrary(provider, fingerprint, displayName, deviceForLibrary))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                platform.log("Linking library ${library.localKey.take(8)} ($provider) failed: ${describe(error)}")
                failed += library.name
            }
        }
        link.value = if (failed.isEmpty()) AccountLinkState.Linked(deviceId, withoutIdentity)
        else AccountLinkState.Failed(libraries = failed.toList())
    }

    /** Reuses this device's registration in the same account; in another account, or if it was removed, registers it again. */
    private suspend fun ensureDevice(session: AccountSession): String {
        val stored = platform.storedDevice()
        if (stored != null && stored.userId == session.userId && backend.touchDevice(stored.deviceId)) return stored.deviceId

        if (stored != null && stored.userId != session.userId) {
            // Another account without going through "Sign out" (the previous session expired): what
            // was there belonged to the other account and stays as local data, which joins this one.
            platform.detachFromAccount()
        } else {
            // The same user with the device removed: the device library belonged to the old
            // registration, and is linked again with the new id.
            platform.forgetLibraryLink(DEVICE_LIBRARY_KEY)
        }
        val id = backend.registerDevice(name.value, platformName)
        platform.storeDevice(StoredDevice(id, session.userId))
        return id
    }

    companion object {
        /** A chave local da biblioteca deste aparelho, igual em todas as plataformas. */
        const val DEVICE_LIBRARY_KEY = "device"
        private const val LINK_TIMEOUT_MS = 30_000L
        private const val EXPORT_PAGE = 500
    }
}

/**
 * The error for the log: the type and, for backend errors, only the first line of the message.
 * Never the full description, which carries the request's URL and headers.
 */
fun describe(error: Throwable): String {
    val root = generateSequence(error) { it.cause }.last()
    return "${error.javaClass.simpleName}(${error.message}) <- ${root.javaClass.simpleName}: ${root.message?.lineSequence()?.firstOrNull()}"
}
