package eu.studio742.imago.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The sync transport.
 *
 * The app never imports types from the concrete backend outside the module that implements it:
 * switching backends means writing another implementation of this interface. No payload passing
 * through here may contain credentials or addresses of the photo services.
 */
interface SyncBackend {
    /** Whether this build has a project configured. Without one there is no account and nothing leaves the device. */
    val isAvailable: Boolean

    /** The account session, or null without a session. */
    val session: StateFlow<AccountSession?>

    suspend fun signUp(email: String, password: String): SignUpResult
    suspend fun signIn(email: String, password: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun updatePassword(password: String)

    /** Ends the session on this device. Offline, it still ends it locally. */
    suspend fun signOut()

    /** Handles an authentication link opened in the app. Null if the link is not its own. */
    fun handleAuthLink(uri: String): AuthLink?

    suspend fun registerDevice(name: String, platform: String): String

    /** Updates "last seen" and, if given, the name. `false` if the device no longer exists. */
    suspend fun touchDevice(deviceId: String, name: String? = null): Boolean
    suspend fun devices(): List<RemoteDevice>
    suspend fun removeDevice(deviceId: String)

    suspend fun linkLibrary(provider: String, fingerprint: String, displayName: String, deviceId: String?): String

    suspend fun push(entity: SyncEntity, changes: List<OutgoingChange>): List<PushResult>
    suspend fun pull(entity: SyncEntity, cursor: Long, limit: Int = 500): PullPage

    /**
     * Stores the version that lost a conflict, so the history can show it. Whoever resolved the
     * conflict is the one who sends it: it is the only device that has both versions.
     */
    suspend fun recordConflict(
        entity: SyncEntity,
        key: String,
        revision: Long,
        payload: JsonObject,
        editedAt: String,
        deviceId: String?,
    )

    /**
     * Deletes the account on the server and, in cascade, everything it stores. The server only
     * accepts a request made right after signing in with the password.
     */
    suspend fun deleteAccount(): Unit =
        throw AccountException(AccountException.Reason.NOT_AVAILABLE, "Deleting the account is not available in this version of the app.")

    /** The libraries linked to the account, as the server stores them: only for exporting. */
    suspend fun libraries(): List<JsonObject> = emptyList()

    /** Only a wake-up signal: the entity that changed on another device. May never emit. */
    fun changes(): Flow<SyncEntity>
}

data class AccountSession(val userId: String, val email: String?)

enum class SignUpResult {
    /** The account was created and is waiting for the email confirmation. */
    CONFIRMATION_SENT,

    /** The project does not ask for confirmation and the session has already started. */
    SIGNED_IN,
}

enum class AuthLink { CONFIRMATION, RECOVERY }

data class RemoteDevice(
    val id: String,
    val name: String,
    val platform: String,
    val createdAt: String,
    val lastSeenAt: String,
)

/** The protocol entities, with the name they have in the backend. */
enum class SyncEntity(val wireName: String) {
    RECIPE("RECIPE"),
    DERIVED_ASSET("DERIVED_ASSET"),
    SAVED_RECIPE("SAVED_RECIPE"),
    TEMPLATE("TEMPLATE"),
    PROJECT("PROJECT"),
    BRAND_KIT("BRAND_KIT"),

    /** The recipe versions that lost a conflict; only received. */
    RECIPE_CONFLICT("RECIPE_CONFLICT");

    companion object {
        fun fromWire(name: String): SyncEntity? = entries.firstOrNull { it.wireName == name }
    }
}

data class OutgoingChange(
    val key: String,
    val baseRevision: Long?,
    val payload: JsonObject,
    val editedAt: String,
    val deviceId: String,
    val deleted: Boolean = false,
    val hints: JsonObject? = null,
)

data class IncomingChange(
    val key: String,
    val revision: Long,
    val seq: Long,
    val payload: JsonObject,
    val editedAt: String,
    val editedByDevice: String?,
    val deletedAt: String?,
    val hints: JsonObject? = null,
)

sealed interface PushResult {
    val key: String

    data class Applied(override val key: String, val revision: Long, val seq: Long) : PushResult
    data class Conflict(override val key: String, val remote: IncomingChange) : PushResult

    /** Invalid change; not retried. */
    data class Rejected(override val key: String, val reason: String) : PushResult
}

data class PullPage(val rows: List<IncomingChange>, val cursor: Long)

/** An account error, with the message ready to show. */
class AccountException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        NOT_AVAILABLE, OFFLINE, INVALID_CREDENTIALS, EMAIL_NOT_CONFIRMED, EMAIL_IN_USE,
        WEAK_PASSWORD, SAME_PASSWORD, RATE_LIMITED, NOT_SIGNED_IN, SERVER,
    }
}
