package eu.studio742.imago.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import eu.studio742.imago.core.data.AccountLocalData
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.LibraryLinkEntity
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import javax.inject.Singleton

/**
 * The IMAGO account, in both apps. The logic is the shared [AccountCoordinator]'s; this is where
 * things are stored — Room for the links and the data, [SecurePreferences] for this device's
 * registration — and which the libraries are.
 *
 * Features do not call [SyncBackend]; this is how the account reaches them.
 *
 * @param defaultDeviceName the device's name in the system — the phone's, the computer's.
 * @param platformName how the backend identifies the platform: "android" or "windows".
 */
@Singleton
class AccountRepository(
    backend: SyncBackend,
    configuration: ConfigurationRepository,
    private val database: ImmichRoomDatabase,
    private val localData: AccountLocalData,
    private val preferences: SecurePreferences,
    defaultDeviceName: String,
    platformName: String,
) {

    private val platform = object : AccountPlatform {
        override fun storedDevice(): StoredDevice? {
            val id = preferences.getString(KEY_DEVICE) ?: return null
            val user = preferences.getString(KEY_USER) ?: return null
            return StoredDevice(id, user)
        }
        override fun storeDevice(device: StoredDevice) {
            preferences.write(mapOf(KEY_DEVICE to device.deviceId, KEY_USER to device.userId))
        }
        override fun storedDeviceName(): String? = preferences.getString(KEY_NAME)
        override fun storeDeviceName(name: String) { preferences.write(mapOf(KEY_NAME to name)) }
        override suspend fun libraryLink(localKey: String) = database.libraryLinkDao().get(localKey)?.remoteLibraryId
        override suspend fun saveLibraryLink(localKey: String, remoteLibraryId: String) =
            database.libraryLinkDao().upsert(LibraryLinkEntity(localKey, remoteLibraryId))
        override suspend fun forgetLibraryLink(localKey: String) = database.libraryLinkDao().delete(localKey)
        override suspend fun detachFromAccount() = localData.detachFromAccount()
        override suspend fun removeAccountData() = localData.removeAccountData()
        override fun log(message: String) { System.err.println("[$TAG] $message") }
    }

    private val coordinator = AccountCoordinator(
        backend = backend,
        platform = platform,
        libraries = configuration.libraries.map { sources ->
            sources.filter { it.isConnected }.map { source ->
                AccountLibrary(
                    localKey = source.id,
                    name = source.name,
                    kind = if (source.isDevice) AccountLibrary.Kind.DEVICE else AccountLibrary.Kind.IMMICH,
                    accountUserId = source.userId,
                )
            }
        },
        defaultDeviceName = defaultDeviceName,
        platformName = platformName,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    init {
        check(DEVICE_LIBRARY_ID == AccountCoordinator.DEVICE_LIBRARY_KEY)
    }

    val isAvailable get() = coordinator.isAvailable
    val session get() = coordinator.session
    val deviceName get() = coordinator.deviceName
    val passwordRecoveryPending get() = coordinator.passwordRecoveryPending
    val linkState get() = coordinator.linkState
    val currentDeviceId: String? get() = coordinator.currentDeviceId

    suspend fun signUp(email: String, password: String) = coordinator.signUp(email, password)
    suspend fun signIn(email: String, password: String) = coordinator.signIn(email, password)
    suspend fun sendPasswordReset(email: String) = coordinator.sendPasswordReset(email)
    suspend fun updatePassword(password: String) = coordinator.updatePassword(password)
    fun dismissPasswordRecovery() = coordinator.dismissPasswordRecovery()
    fun handleAuthLink(uri: String) = coordinator.handleAuthLink(uri)
    fun retryLink() = coordinator.retryLink()
    suspend fun devices() = coordinator.devices()
    suspend fun renameDevice(newName: String) = coordinator.renameDevice(newName)
    suspend fun removeDevice(deviceId: String) = coordinator.removeDevice(deviceId)
    suspend fun signOut(keepCopy: Boolean) = coordinator.signOut(keepCopy)
    suspend fun exportData(): ByteArray = coordinator.exportData()
    suspend fun deleteAccount(password: String) = coordinator.deleteAccount(password)

    private companion object {
        const val TAG = "ImagoSync"
        const val KEY_DEVICE = "device_id"
        const val KEY_USER = "device_user"
        const val KEY_NAME = "device_name"
    }
}
