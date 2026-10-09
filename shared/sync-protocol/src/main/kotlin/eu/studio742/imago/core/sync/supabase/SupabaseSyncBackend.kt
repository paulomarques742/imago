package eu.studio742.imago.core.sync.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import eu.studio742.imago.core.sync.AccountException
import eu.studio742.imago.core.sync.AccountException.Reason
import eu.studio742.imago.core.sync.AccountSession
import eu.studio742.imago.core.sync.AuthLink
import eu.studio742.imago.core.sync.OutgoingChange
import eu.studio742.imago.core.sync.PullPage
import eu.studio742.imago.core.sync.PushResult
import eu.studio742.imago.core.sync.RemoteDevice
import eu.studio742.imago.core.sync.SignUpResult
import eu.studio742.imago.core.sync.SyncBackend
import eu.studio742.imago.core.sync.SyncEntity
import java.io.IOException

/** This build's Supabase project. Incomplete means no account, and nothing leaves the device. */
data class SupabaseSettings(val url: String, val publishableKey: String) {
    val isComplete: Boolean get() = url.startsWith("http") && publishableKey.isNotBlank()
}

/**
 * [SyncBackend] on Supabase: Auth for the account, the `sync_*` functions through PostgREST and
 * Realtime only as a wake-up signal.
 *
 * The HTTP client is an instance of its own, sharing nothing with the Immich client. Without a
 * session no request is made: loading the saved session is local, and token refresh, requests or
 * the websocket only happen once someone has signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseSyncBackend(
    settings: SupabaseSettings,
    store: SessionStore?,
    httpClient: OkHttpClient = OkHttpClient(),
    /** Refresh the session when coming back to the foreground. Only Android has that lifecycle. */
    lifecycleCallbacks: Boolean = false,
) : SyncBackend {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionAdapter = store?.let(::SupabaseSessionAdapter)
    private val json = Json { ignoreUnknownKeys = true }

    override val isAvailable: Boolean = settings.isComplete && store != null

    private val client: SupabaseClient? = if (!isAvailable) null else createSupabaseClient(settings.url, settings.publishableKey) {
        // The client's logs would include request URLs and error responses.
        defaultLogLevel = LogLevel.NONE
        httpEngine = OkHttp.create { preconfigured = httpClient }
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = LINK_SCHEME
            host = LINK_HOST
            sessionManager = sessionAdapter
            codeVerifierCache = sessionAdapter
            enableLifecycleCallbacks = lifecycleCallbacks
        }
        install(Postgrest)
        install(Realtime)
        install(Functions)
    }

    override val session: StateFlow<AccountSession?> = client?.auth?.sessionStatus
        // Offline with an expired token the session still exists: only `NotAuthenticated` ends it.
        ?.filter { it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated }
        ?.map { status ->
            (status as? SessionStatus.Authenticated)?.session?.user?.let { AccountSession(it.id, it.email) }
        }
        ?.stateIn(scope, SharingStarted.Eagerly, null)
        ?: MutableStateFlow(null)

    override suspend fun signUp(email: String, password: String): SignUpResult = call {
        auth.signUpWith(Email, redirectUrl = CONFIRMATION_URL) {
            this.email = email
            this.password = password
        }
        if (auth.currentSessionOrNull() != null) SignUpResult.SIGNED_IN else SignUpResult.CONFIRMATION_SENT
    }

    override suspend fun signIn(email: String, password: String) = call {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun sendPasswordReset(email: String) = call {
        auth.resetPasswordForEmail(email, redirectUrl = RECOVERY_URL)
    }

    override suspend fun updatePassword(password: String) = call {
        auth.updateUser { this.password = password }
        Unit
    }

    override suspend fun signOut() {
        val client = client ?: return
        try {
            client.auth.signOut()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Offline the server never finds out, but on this device the session ends anyway.
            client.auth.clearSession()
        }
    }

    /**
     * The confirmation or recovery link, opened on the device where it was requested. With PKCE it
     * carries a `code`, which is exchanged for the session; the exchange runs in the background and
     * the session arrives through [session].
     */
    override fun handleAuthLink(uri: String): AuthLink? {
        val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
        if (parsed.scheme != LINK_SCHEME || parsed.host != LINK_HOST) return null
        val code = parsed.rawQuery.orEmpty().split('&')
            .map { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
            .firstOrNull { it.first == "code" }?.second
        val client = client
        if (code != null && client != null) scope.launch { runCatching { client.auth.exchangeCodeForSession(code) } }
        return if (parsed.path == RECOVERY_PATH) AuthLink.RECOVERY else AuthLink.CONFIRMATION
    }

    override suspend fun registerDevice(name: String, platform: String): String = rpc(
        "sync_register_device",
        buildJsonObject { put("p_name", name); put("p_platform", platform) },
    ).let(SyncWire::id)

    override suspend fun touchDevice(deviceId: String, name: String?): Boolean = try {
        rpc("sync_touch_device", buildJsonObject { put("p_device", deviceId); put("p_name", name) })
        true
    } catch (error: AccountException) {
        if ((error.cause as? PostgrestRestException)?.code == NO_DATA_FOUND) false else throw error
    }

    override suspend fun devices(): List<RemoteDevice> = call {
        SyncWire.devices(json.parseToJsonElement(from("devices").select().data))
    }

    override suspend fun removeDevice(deviceId: String) = call {
        from("devices").delete { filter { eq("id", deviceId) } }
        Unit
    }

    override suspend fun linkLibrary(provider: String, fingerprint: String, displayName: String, deviceId: String?): String = rpc(
        "sync_link_library",
        buildJsonObject {
            put("p_provider", provider)
            put("p_fingerprint", fingerprint)
            put("p_display_name", displayName)
            put("p_device", deviceId)
        },
    ).let(SyncWire::id)

    override suspend fun push(entity: SyncEntity, changes: List<OutgoingChange>): List<PushResult> =
        SyncWire.pushResults(rpc("sync_push", SyncWire.pushParameters(entity.wireName, changes)))

    override suspend fun pull(entity: SyncEntity, cursor: Long, limit: Int): PullPage =
        SyncWire.pullPage(rpc("sync_pull", SyncWire.pullParameters(entity.wireName, cursor, limit)))

    override suspend fun deleteAccount() = call {
        functions.invoke(DELETE_ACCOUNT_FUNCTION)
        Unit
    }

    override suspend fun libraries(): List<kotlinx.serialization.json.JsonObject> = call {
        (json.parseToJsonElement(from("libraries").select().data) as kotlinx.serialization.json.JsonArray)
            .map { it as kotlinx.serialization.json.JsonObject }
    }

    override suspend fun recordConflict(
        entity: SyncEntity,
        key: String,
        revision: Long,
        payload: kotlinx.serialization.json.JsonObject,
        editedAt: String,
        deviceId: String?,
    ) {
        rpc("sync_record_conflict", SyncWire.conflictParameters(entity.wireName, key, revision, payload, editedAt, deviceId))
    }

    override fun changes(): Flow<SyncEntity> = session.flatMapLatest { current ->
        val client = client
        if (current == null || client == null) return@flatMapLatest emptyFlow()
        callbackFlow {
            val channel = client.channel("sync-signals-${current.userId}")
            val signals = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "sync_signals" }
            val job = launch {
                signals.collect { action ->
                    val record = when (action) {
                        is PostgresAction.Insert -> action.record
                        is PostgresAction.Update -> action.record
                        else -> null
                    }
                    record?.get("entity")?.jsonPrimitive?.contentOrNull?.let(SyncEntity::fromWire)?.let { trySend(it) }
                }
            }
            runCatching { channel.subscribe() }
            awaitClose {
                job.cancel()
                scope.launch { runCatching { client.realtime.removeChannel(channel) } }
            }
        }
    }

    private suspend fun rpc(function: String, parameters: kotlinx.serialization.json.JsonObject): JsonElement = call {
        val body = postgrest.rpc(function, parameters).data
        if (body.isBlank()) kotlinx.serialization.json.JsonNull else json.parseToJsonElement(body)
    }

    private suspend fun <T> call(block: suspend SupabaseClient.() -> T): T {
        val client = client ?: throw AccountException(Reason.NOT_AVAILABLE, "Accounts are not available in this version of the app.")
        return try {
            client.withFreshSession(block)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AccountException) {
            throw error
        } catch (error: Exception) {
            throw accountError(error)
        }
    }

    /**
     * Runs [block] with an access token that has not expired.
     *
     * The token lasts an hour and the library refreshes it on its own, but not while the app is in the
     * background; coming back, the first sync ran in parallel with that refresh and went with the
     * expired token, and the server's 401 showed as a sync error until the next run. So it is
     * refreshed here first when it is about to expire, and once more if the server still says 401 —
     * it can expire between the check and the request.
     */
    private suspend fun <T> SupabaseClient.withFreshSession(block: suspend SupabaseClient.() -> T): T {
        auth.awaitInitialization()
        val session = auth.currentSessionOrNull()
        if (session != null && session.expiresAt.toEpochMilliseconds() - System.currentTimeMillis() < TOKEN_MARGIN_MS) {
            auth.refreshCurrentSession()
        }
        return try {
            block()
        } catch (error: RestException) {
            if (error.statusCode != 401 || auth.currentSessionOrNull() == null) throw error
            auth.refreshCurrentSession()
            block()
        }
    }

    companion object {
        /** Refreshed when it has less than this left: enough for a whole run of requests. */
        private const val TOKEN_MARGIN_MS = 60_000L

        private const val LINK_SCHEME = "imago"
        private const val LINK_HOST = "auth"
        private const val RECOVERY_PATH = "/recovery"
        const val CONFIRMATION_URL = "imago://auth/callback"
        const val RECOVERY_URL = "imago://auth/recovery"

        /** A Edge Function em backend/supabase/functions/delete-account. */
        private const val DELETE_ACCOUNT_FUNCTION = "delete-account"

        /** The functions' `raise ... using errcode = 'P0002'`: the device does not exist in this account. */
        private const val NO_DATA_FOUND = "P0002"
    }
}

/** Supabase errors, with a message that can be shown. */
internal fun accountError(error: Throwable): AccountException = when (error) {
    is AuthWeakPasswordException -> AccountException(
        Reason.WEAK_PASSWORD, "The password is too weak. Use a longer one, with letters and numbers.", error,
    )
    is AuthRestException -> when (error.errorCode) {
        AuthErrorCode.InvalidCredentials -> AccountException(Reason.INVALID_CREDENTIALS, "The email or the password is not right.", error)
        AuthErrorCode.EmailNotConfirmed -> AccountException(
            Reason.EMAIL_NOT_CONFIRMED, "Confirm your email before signing in: look for the message we sent you.", error,
        )
        AuthErrorCode.EmailExists, AuthErrorCode.UserAlreadyExists ->
            AccountException(Reason.EMAIL_IN_USE, "An account with this email already exists.", error)
        AuthErrorCode.WeakPassword -> AccountException(
            Reason.WEAK_PASSWORD, "The password is too weak. Use a longer one, with letters and numbers.", error,
        )
        AuthErrorCode.SamePassword -> AccountException(Reason.SAME_PASSWORD, "The new password must be different from the current one.", error)
        AuthErrorCode.OverEmailSendRateLimit, AuthErrorCode.OverRequestRateLimit ->
            AccountException(Reason.RATE_LIMITED, "Too many attempts in a row. Try again in a few minutes.", error)
        else -> AccountException(Reason.SERVER, "The account server refused the request.", error)
    }
    is IOException -> AccountException(Reason.OFFLINE, "No connection to the account server. Try again when there is a network.", error)
    is RestException -> AccountException(Reason.SERVER, "The account server refused the request.", error)
    else -> AccountException(Reason.SERVER, "Could not reach the account server.", error)
}
