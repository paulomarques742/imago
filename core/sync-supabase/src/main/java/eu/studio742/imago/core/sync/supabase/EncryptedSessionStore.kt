package eu.studio742.imago.core.sync.supabase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import eu.studio742.imago.core.sync.SyncBackend

/**
 * The IMAGO account session on Android, stored like the Immich keys: in
 * EncryptedSharedPreferences, and never in the Android backup.
 */
internal class EncryptedSessionStore(private val preferences: SharedPreferences) : SessionStore {
    override fun read(name: String): String? = preferences.getString(name, null)
    override fun write(name: String, value: String) { preferences.edit().putString(name, value).commit() }
    override fun remove(name: String) { preferences.edit().remove(name).commit() }
}

/** The Android [SyncBackend]: the shared client, with the session in encrypted preferences. */
object AndroidSupabase {
    fun create(context: Context, settings: SupabaseSettings): SyncBackend = SupabaseSyncBackend(
        settings = settings,
        store = if (settings.isComplete) EncryptedSessionStore(encrypted(context)) else null,
        lifecycleCallbacks = true,
    )

    private fun encrypted(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context, "imago_account",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
