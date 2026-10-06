package eu.studio742.imago.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** SharedPreferences as seen by the shared configuration. */
class SharedPreferencesStore(private val preferences: SharedPreferences) : SecurePreferences {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun write(changes: Map<String, String?>): Boolean = preferences.edit().apply {
        for ((key, value) in changes) if (value == null) remove(key) else putString(key, value)
    }.commit()
}

internal fun encryptedPreferences(context: Context): SecurePreferences = SharedPreferencesStore(
    EncryptedSharedPreferences.create(
        context, "immich_connection",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ),
)
