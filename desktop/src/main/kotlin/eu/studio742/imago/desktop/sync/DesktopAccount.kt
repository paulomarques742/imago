package eu.studio742.imago.desktop.sync

import eu.studio742.imago.desktop.platform.AppPaths
import eu.studio742.imago.desktop.platform.SecretStore
import eu.studio742.imago.core.data.AccountLocalData
import eu.studio742.imago.core.data.DesktopDataGraph
import eu.studio742.imago.core.data.WindowsSecurePreferences
import eu.studio742.imago.core.sync.AccountRepository
import eu.studio742.imago.core.sync.SyncBackend
import eu.studio742.imago.core.sync.supabase.SessionStore
import eu.studio742.imago.core.sync.supabase.SupabaseSettings
import eu.studio742.imago.core.sync.supabase.SupabaseSyncBackend
import java.util.Properties

/**
 * The IMAGO account on desktop: the same [AccountRepository] as on Android, with what belongs to
 * this computer — DPAPI for the session and the device registration, and the computer name as the
 * default name.
 */
object DesktopAccount {
    /** The account backend, with the session encrypted on this computer. */
    fun backend(secrets: SecretStore, settings: SupabaseSettings = bundledSettings()): SyncBackend =
        SupabaseSyncBackend(settings, DesktopSessionStore(secrets).takeIf { settings.isComplete })

    fun create(
        paths: AppPaths,
        secrets: SecretStore,
        graph: DesktopDataGraph,
        settings: SupabaseSettings = bundledSettings(),
        backend: SyncBackend = backend(secrets, settings),
    ): AccountRepository = AccountRepository(
        backend = backend,
        configuration = graph.configuration,
        database = graph.database,
        localData = AccountLocalData(graph.database),
        preferences = WindowsSecurePreferences(paths.root.resolve("account-device.bin")),
        defaultDeviceName = computerName(),
        platformName = "windows",
    )

    /** The Supabase project the build put into the resources, from local.properties. */
    fun bundledSettings(): SupabaseSettings {
        val properties = Properties()
        DesktopAccount::class.java.getResourceAsStream("/imago-supabase.properties")?.use(properties::load)
        return SupabaseSettings(properties.getProperty("url").orEmpty(), properties.getProperty("publishableKey").orEmpty())
    }

    private fun computerName(): String = System.getenv("COMPUTERNAME")?.takeIf(String::isNotBlank)
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "Windows"
}

/** The account session, encrypted with DPAPI like the Immich keys. */
internal class DesktopSessionStore(private val secrets: SecretStore) : SessionStore {
    override fun read(name: String): String? = secrets.read(id(name))
    override fun write(name: String, value: String) = secrets.write(id(name), value)
    override fun remove(name: String) = secrets.remove(id(name))

    /** The key name comes from the client and may contain dots; the vault only accepts plain names. */
    private fun id(name: String) = "account-" + name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
}
