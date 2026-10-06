package eu.studio742.imago.core.sync.supabase

import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Where each platform stores the account session: EncryptedSharedPreferences on Android, DPAPI on
 * Windows. It is the only credential the backend knows, and it never goes into backups.
 *
 * It stores opaque text; the format belongs to this module, and the platforms do not know the
 * supabase-kt types.
 */
interface SessionStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun remove(name: String)
}

/** An in-memory [SessionStore], for tests and for builds without secure storage. */
class InMemorySessionStore : SessionStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun read(name: String): String? = values[name]
    override fun write(name: String, value: String) { values[name] = value }
    override fun remove(name: String) { values.remove(name) }
}

internal class SupabaseSessionAdapter(private val store: SessionStore) : SessionManager, CodeVerifierCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun saveSession(session: UserSession) =
        store.write(KEY_SESSION, json.encodeToString(UserSession.serializer(), session))

    override suspend fun loadSession(): UserSession {
        val stored = store.read(KEY_SESSION) ?: throw NoSessionFoundException()
        return json.decodeFromString(UserSession.serializer(), stored)
    }

    override suspend fun deleteSession() = store.remove(KEY_SESSION)

    override suspend fun saveCodeVerifier(codeVerifier: String) = store.write(KEY_VERIFIER, codeVerifier)

    override suspend fun loadCodeVerifier(): String? = store.read(KEY_VERIFIER)

    override suspend fun deleteCodeVerifier() = store.remove(KEY_VERIFIER)

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_VERIFIER = "pkce_verifier"
    }
}
