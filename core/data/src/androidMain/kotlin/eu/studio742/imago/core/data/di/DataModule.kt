package eu.studio742.imago.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.EncryptedConfigurationRepository
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.RoomDerivedAssetRepository
import eu.studio742.imago.core.data.RoomLibraryRepository
import eu.studio742.imago.core.data.RoomRecipeRepository
import eu.studio742.imago.core.data.RoomSavedRecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.data.BrandKitRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import eu.studio742.imago.core.data.RoomBrandKitRepository
import eu.studio742.imago.core.data.RoomCompositionRepository
import eu.studio742.imago.core.data.RoomCompositionTemplateRepository
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.ImmichCompositionMediaRepository
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.OkHttpImmichApi
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds abstract fun configuration(implementation: EncryptedConfigurationRepository): ConfigurationRepository
    @Binds abstract fun library(implementation: eu.studio742.imago.core.data.SourceLibraryRepository): LibraryRepository
    @Binds abstract fun recipes(implementation: RoomRecipeRepository): RecipeRepository
    @Binds abstract fun savedRecipes(implementation: RoomSavedRecipeRepository): SavedRecipeRepository
    @Binds abstract fun derivedAssets(implementation: RoomDerivedAssetRepository): DerivedAssetRepository
    @Binds abstract fun compositions(implementation: RoomCompositionRepository): CompositionRepository
    @Binds abstract fun compositionTemplates(implementation: RoomCompositionTemplateRepository): CompositionTemplateRepository
    @Binds abstract fun brandKit(implementation: RoomBrandKitRepository): BrandKitRepository
    @Binds abstract fun compositionMedia(implementation: ImmichCompositionMediaRepository): CompositionMediaRepository
    @Binds abstract fun deviceLibrary(implementation: eu.studio742.imago.core.data.DeviceLibraryRepository): eu.studio742.imago.core.data.DeviceLibrary
}

@Module
@InstallIn(SingletonComponent::class)
object DataProviders {
    @Provides
    @Singleton
    fun configuration(
        @ApplicationContext context: Context,
        api: ImmichApi,
        database: ImmichRoomDatabase,
        triggers: eu.studio742.imago.core.data.EndpointTriggers,
    ): EncryptedConfigurationRepository = EncryptedConfigurationRepository(
        eu.studio742.imago.core.data.encryptedPreferences(context), api, database, triggers.events,
    )

    @Provides
    @Singleton
    fun contentHashes(
        @ApplicationContext context: Context,
        database: ImmichRoomDatabase,
        library: LibraryRepository,
    ): eu.studio742.imago.core.data.ContentHashRepository = eu.studio742.imago.core.data.ContentHashRepository(
        database,
        { id -> context.contentResolver.openInputStream(android.net.Uri.parse(id)) },
        { id -> library.assetDetail(id) },
    )

    /** Outside the backup and the cache: the cache can be cleared by the system halfway through an upload. */
    @Provides
    @Singleton
    fun sharedUploads(@ApplicationContext context: Context): eu.studio742.imago.core.data.SharedUploadStaging =
        eu.studio742.imago.core.data.SharedUploadStaging(java.io.File(context.noBackupFilesDir, "shared-uploads"))

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ImmichRoomDatabase =
        Room.databaseBuilder(context, ImmichRoomDatabase::class.java, "immich-room.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, eu.studio742.imago.core.data.LibraryMigration.MIGRATION_8_9, eu.studio742.imago.core.data.LibraryMigration.MIGRATION_9_10, eu.studio742.imago.core.data.SyncMigration.MIGRATION_10_11)
            .build()

    /** The grid's order gets an index: without it, every page sorted the whole table. */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_assets_libraryKey_fileCreatedAt_id " +
                    "ON assets (libraryKey, fileCreatedAt, id)",
            )
        }
    }

    /** The record of which timeline months are already whole in the local catalogue. */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS catalog_months (
                    libraryKey TEXT NOT NULL,
                    month TEXT NOT NULL,
                    assetCount INTEGER NOT NULL,
                    syncedAt TEXT NOT NULL,
                    PRIMARY KEY(libraryKey, month)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE assets ADD COLUMN checksum TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE assets ADD COLUMN hasLocalRecipe INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recipes (
                    libraryKey TEXT NOT NULL,
                    assetId TEXT NOT NULL,
                    recipeJson TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    PRIMARY KEY(libraryKey, assetId)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS saved_recipes (
                    libraryKey TEXT NOT NULL,
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    collection TEXT NOT NULL,
                    recipeJson TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    PRIMARY KEY(libraryKey, id)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS derived_assets (
                    libraryKey TEXT NOT NULL,
                    derivedAssetId TEXT NOT NULL,
                    originalAssetId TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    PRIMARY KEY(libraryKey, derivedAssetId)
                )
                """.trimIndent(),
            )
        }
    }

    /** Saved recipes gain a favourite and a last-used date, for the tabs. */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE saved_recipes ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE saved_recipes ADD COLUMN usedAt TEXT")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE assets ADD COLUMN mimeType TEXT")
            db.execSQL("ALTER TABLE assets ADD COLUMN durationMs INTEGER")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS composition_projects (
                    libraryKey TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
                    projectJson TEXT NOT NULL, revision INTEGER NOT NULL,
                    createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL,
                    PRIMARY KEY(libraryKey, id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS composition_templates (
                    libraryKey TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
                    templateJson TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL,
                    PRIMARY KEY(libraryKey, id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS brand_kits (
                    libraryKey TEXT NOT NULL, kitJson TEXT NOT NULL, updatedAt TEXT NOT NULL,
                    PRIMARY KEY(libraryKey)
                )
                """.trimIndent(),
            )
        }
    }

    @Provides
    @Singleton
    fun api(triggers: eu.studio742.imago.core.data.EndpointTriggers): ImmichApi = OkHttpImmichApi(
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            // A request that does not reach the server may mean one left the home network: the
            // configuration chooses each library's address again.
            .addInterceptor { chain ->
                try { chain.proceed(chain.request()) } catch (error: java.io.IOException) {
                    triggers.connectionFailed()
                    throw error
                }
            }
            .build(),
    )
}
