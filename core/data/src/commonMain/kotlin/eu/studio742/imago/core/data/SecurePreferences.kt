package eu.studio742.imago.core.data

/**
 * The encrypted storage of the configuration: addresses, API keys, chosen library.
 *
 * On Android it is EncryptedSharedPreferences; on desktop, a file protected by the Windows user's
 * DPAPI. The configuration only needs to read a key and to save a set of changes at once.
 */
interface SecurePreferences {
    fun getString(key: String): String?

    /** Saves all or nothing; `null` removes the key. Returns false if the storage refused. */
    fun write(changes: Map<String, String?>): Boolean
}
