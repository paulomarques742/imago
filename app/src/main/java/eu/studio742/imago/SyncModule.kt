package eu.studio742.imago

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.studio742.imago.core.designsystem.i18n.AppSyncTexts
import eu.studio742.imago.core.model.SyncTexts
import eu.studio742.imago.core.sync.SyncBackend
import eu.studio742.imago.core.sync.supabase.SupabaseSettings
import eu.studio742.imago.core.sync.supabase.AndroidSupabase
import javax.inject.Singleton

/** The only place that wires sync to Supabase. */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun syncBackend(@ApplicationContext context: Context): SyncBackend = AndroidSupabase.create(
        context,
        SupabaseSettings(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
    )

    /** What sync writes into the data comes out in the app language; the strings live in the design system. */
    @Provides
    fun syncTexts(): SyncTexts = AppSyncTexts
}
