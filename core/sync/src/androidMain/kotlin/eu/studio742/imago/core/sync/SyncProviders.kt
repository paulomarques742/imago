package eu.studio742.imago.core.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import eu.studio742.imago.core.data.AccountLocalData
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.ContentHashRepository
import eu.studio742.imago.core.data.SharedPreferencesStore
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.SyncTexts
import javax.inject.Singleton

/** The account on Android: the device registration in SharedPreferences, and the name the system gives it. */
@Module
@InstallIn(SingletonComponent::class)
object SyncProviders {
    @Provides
    @Singleton
    fun account(
        @ApplicationContext context: Context,
        backend: SyncBackend,
        configuration: ConfigurationRepository,
        database: ImmichRoomDatabase,
        localData: AccountLocalData,
    ): AccountRepository = AccountRepository(
        backend, configuration, database, localData,
        SharedPreferencesStore(context.getSharedPreferences("sync_device", Context.MODE_PRIVATE)),
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf(String::isNotBlank)
            ?: Build.MODEL,
        platformName = "android",
    )

    @Provides
    @Singleton
    fun syncEngine(
        database: ImmichRoomDatabase,
        backend: SyncBackend,
        hashes: ContentHashRepository,
        account: AccountRepository,
        texts: SyncTexts,
    ): SyncEngine = SyncEngine(
        database, backend, hashes,
        deviceId = { account.currentDeviceId },
        deviceName = { account.deviceName.value },
        texts = texts,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}
