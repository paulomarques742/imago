package eu.studio742.imago.core.data

import androidx.sqlite.execSQL

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.OkHttpImmichApi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop data layer, assembled by hand — the role the Hilt module plays on Android.
 *
 * The pieces are the same classes as on the phone; only the three that touch the system change:
 * the database with embedded SQLite, the configuration encrypted with DPAPI and the folder library
 * instead of the gallery.
 *
 * @param root the app's data folder (`%LOCALAPPDATA%\IMAGO`).
 * @param trash how the folder library sends files to the Recycle Bin.
 */
class DesktopDataGraph(
    val root: Path,
    trash: (java.io.File) -> Boolean = { java.awt.Desktop.getDesktop().moveToTrash(it) },
) : AutoCloseable {
    init { Files.createDirectories(root) }

    val database: ImmichRoomDatabase = Room.databaseBuilder<ImmichRoomDatabase>(name = root.resolve(DATABASE_FILE).toString())
        .setDriver(BundledSQLiteDriver())
        // The desktop database started at version 11; what comes after it is migrated here.
        .addMigrations(object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(UNIFIED_INDEX_SQL)
            }
        }, object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(ASSET_LOCATIONS_SQL)
            }
        }, object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                ARCHIVE_SQL.forEach { connection.execSQL(it) }
            }
        }, object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                STACK_SQL.forEach { connection.execSQL(it) }
            }
        }, object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                UNIFIED_STACKS_SQL.forEach { connection.execSQL(it) }
            }
        })
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    private val endpointTriggers = MutableSharedFlow<EndpointTrigger>(extraBufferCapacity = 8)

    val api: ImmichApi = OkHttpImmichApi(
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            // As on Android: a request that does not reach the server may mean one left the home
            // network, and the configuration chooses each library's address again.
            .addInterceptor { chain ->
                try { chain.proceed(chain.request()) } catch (error: IOException) {
                    endpointTriggers.tryEmit(EndpointTrigger.CONNECTION_FAILED)
                    throw error
                }
            }
            .build(),
    )

    val preferences: SecurePreferences = WindowsSecurePreferences(root.resolve(PREFERENCES_FILE))
    val configuration = EncryptedConfigurationRepository(preferences, api, database, endpointTriggers)
    val folders = FolderLibraryRepository(database, preferences, trash)
    val library: LibraryRepository = SourceLibraryRepository(configuration, database, api, folders)
    val contentHashes = ContentHashRepository(
        database,
        { id -> runCatching { Files.newInputStream(FolderLibraryRepository.fileOf(id)) }.getOrNull() },
        { id -> library.assetDetail(id) },
    )
    val derivedAssets: DerivedAssetRepository = RoomDerivedAssetRepository(database, configuration)
    val recipes: RecipeRepository = RoomRecipeRepository(database, configuration, contentHashes)
    val savedRecipes: SavedRecipeRepository = RoomSavedRecipeRepository(database, configuration)
    val compositions: CompositionRepository = RoomCompositionRepository(database, configuration, contentHashes)
    val compositionTemplates: CompositionTemplateRepository = RoomCompositionTemplateRepository(database, configuration, contentHashes)
    val brandKit: BrandKitRepository = RoomBrandKitRepository(database, configuration, contentHashes)
    val compositionMedia: CompositionMediaRepository = ImmichCompositionMediaRepository(configuration, api, library)
    val accountLocalData = AccountLocalData(database)

    /** A signal from outside — the network changed, the window got focus back — to review the addresses. */
    fun endpointTrigger(trigger: EndpointTrigger) { endpointTriggers.tryEmit(trigger) }

    override fun close() = database.close()

    companion object {
        /**
         * A new file, and not the previous version's `imago.db`: the data layer is a different one and
         * desktop starts over (the libraries link again; what was in the account comes down again).
         */
        const val DATABASE_FILE = "imago-data.db"
        const val PREFERENCES_FILE = "configuration.bin"
    }
}
