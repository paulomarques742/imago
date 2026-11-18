package eu.studio742.imago

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.studio742.imago.core.ml.HttpModelSource
import eu.studio742.imago.feature.editor.GeneratedMaskTools
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** Where the mask models come from: the public bucket of the build's Supabase project. */
@Module
@InstallIn(SingletonComponent::class)
object MaskModelsModule {
    /** Subject, Sky and Background are in debug builds only, while they are being tested. */
    @Provides
    @Singleton
    fun generatedMaskTools(@ApplicationContext context: Context): GeneratedMaskTools = GeneratedMaskTools(
        if (!BuildConfig.DEBUG) null else HttpModelSource(
            File(context.filesDir, "ml-models"),
            BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/ml-models/",
            OkHttpClient(),
        ),
    )
}
