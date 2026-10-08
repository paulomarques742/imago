package eu.studio742.imago.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.db.*
import eu.studio742.imago.core.immich.*
import eu.studio742.imago.core.model.*
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SyncStateTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: ImmichRoomDatabase
    private val files = mutableMapOf<String, ByteArray>()
    private var opened = 0
    private var immichChecksum = ""
    private lateinit var hashes: ContentHashRepository
    private lateinit var configuration: ConfigurationRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ImmichRoomDatabase::class.java).allowMainThreadQueries().build()
        hashes = ContentHashRepository(
            database,
            openDeviceFile = { id -> opened++; files[id]?.let(::ByteArrayInputStream) },
            assetDetail = { id -> ImmichAssetDetail(asset(AssetReference.parse(id).localId, immichChecksum).toDomain()) },
        )
        val api = object : ImmichApi by OkHttpImmichApi(OkHttpClient()) {}
        configuration = EncryptedConfigurationRepository(SharedPreferencesStore(context.getSharedPreferences(java.util.UUID.randomUUID().toString(), 0)), api)
    }

    @After fun tearDown() = database.close()

    @Test fun migrationFrom10KeepsEveryRowAndDatesItsEditsByWhenTheyHappened() = runBlocking {
        val name = "sync-migration-test.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val schema = Json.parseToJsonElement(
            File("schemas/eu.studio742.imago.core.data.db.ImmichRoomDatabase/10.json").readText(),
        ).jsonObject["database"]!!.jsonObject
        schema["entities"]!!.jsonArray.forEach { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            old.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            obj["indices"]?.jsonArray?.forEach { index ->
                old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        old.execSQL("INSERT INTO recipes VALUES(?,?,?,?)", arrayOf<Any?>("lib", "photo", "{\"a\":1}", "2026-03-01T10:00:00Z"))
        old.execSQL("INSERT INTO saved_recipes VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(GLOBAL_LIBRARY_ID, "p", "Preset", "Col", "{}", "2026-01-01", "2026-02-01", 1, null))
        old.execSQL("INSERT INTO derived_assets VALUES(?,?,?,?)", arrayOf<Any?>("lib", "export", "photo", "2026-04-01"))
        old.execSQL("INSERT INTO composition_projects VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(GLOBAL_LIBRARY_ID, "c", "Projecto", "{}", 3, "2026-01-01", "2026-05-01"))
        old.execSQL("INSERT INTO composition_templates VALUES(?,?,?,?,?,?)", arrayOf<Any?>(GLOBAL_LIBRARY_ID, "t", "Template", "{}", "2026-01-01", "2026-06-01"))
        old.execSQL("INSERT INTO brand_kits VALUES(?,?,?)", arrayOf<Any?>(GLOBAL_LIBRARY_ID, "{}", "2026-07-01"))
        old.execSQL(
            "INSERT INTO assets(libraryKey,id,checksum,originalFileName,fileCreatedAt,localDateTime,isFavorite,isEdited,hasLocalRecipe,type) VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("lib", "photo", "abc", "a.jpg", "2026-03-01", "", 0, 0, 1, "IMAGE"),
        )
        old.version = 10
        old.close()
        val upgraded = Room.databaseBuilder(context, ImmichRoomDatabase::class.java, name)
            .addMigrations(SyncMigration.MIGRATION_10_11, UnifiedMigration.MIGRATION_11_12).allowMainThreadQueries().build()
        try {
            val sql = upgraded.openHelper.writableDatabase // Room validates the whole schema here.
            val recipe = upgraded.recipeDao().get("lib", "photo")!!
            assertEquals("{\"a\":1}", recipe.recipeJson)
            assertEquals(SyncState(editedAt = "2026-03-01T10:00:00Z"), recipe.sync)
            assertNull(recipe.contentSha1)
            assertEquals("2026-02-01", upgraded.savedRecipeDao().list(GLOBAL_LIBRARY_ID).single().sync.editedAt)
            assertEquals("2026-04-01", upgraded.derivedAssetDao().getAny("lib", "export")!!.sync.editedAt)
            assertEquals("2026-05-01", upgraded.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "c")!!.sync.editedAt)
            assertEquals("2026-06-01", upgraded.compositionTemplateDao().get(GLOBAL_LIBRARY_ID, "t")!!.sync.editedAt)
            assertEquals("2026-07-01", upgraded.brandKitDao().getAny(GLOBAL_LIBRARY_ID)!!.sync.editedAt)
            for ((table, _) in SyncMigration.SYNCED_TABLES) {
                sql.query("SELECT COUNT(*) FROM $table WHERE dirty = 0").use { it.moveToFirst(); assertEquals(table, 1, it.getInt(0)) }
            }
            assertNull(upgraded.assetDao().asset("lib", "photo")!!.sizeBytes)
            // Version 12: the unified library's index, looked for by name.
            sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_assets_libraryKey_originalFileName'")
                .use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun savingMarksDirtyButSavingTheSameRecipeAgainIsNotAnEdit() = runBlocking {
        val recipes = RoomRecipeRepository(database, configuration, hashes)
        val id = AssetReference("lib", "photo").encode()
        database.assetDao().upsertAll(listOf(asset("photo", "qZk+NkcGgWq6PiVxeFDCbJzQ2J0=")))
        val recipe = EditRecipe(assetId = id, originalChecksum = "", createdAt = "2026-09-01", updatedAt = "2026-09-01")
        recipes.save(recipe)
        val first = database.recipeDao().get("lib", "photo")!!
        assertTrue(first.sync.dirty)
        assertTrue(first.sync.editedAt.isNotBlank())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", first.contentSha1)

        // As if it had already gone up: the engine sets the revision and clears dirty.
        database.recipeDao().upsert(first.copy(sync = first.sync.copy(remoteRevision = 4, dirty = false, editedAt = "2026-09-01T00:00:00Z")))
        recipes.save(recipe)
        assertEquals(SyncState(remoteRevision = 4, editedAt = "2026-09-01T00:00:00Z"), database.recipeDao().get("lib", "photo")!!.sync)

        recipes.save(recipe.copy(tone = Tone(exposure = 0.5f), updatedAt = "2026-09-02"))
        val edited = database.recipeDao().get("lib", "photo")!!.sync
        assertEquals(4L, edited.remoteRevision)
        assertTrue(edited.dirty)
        assertTrue(edited.editedAt > "2026-09-01T00:00:00Z")
    }

    @Test fun aFileOpenedFromAnotherAppKeepsItsEditsOutOfTheTableAndTheSync() = runBlocking {
        val recipes = RoomRecipeRepository(database, configuration, hashes)
        val id = AssetReference(OPENED_LIBRARY_ID, "content://com.whatsapp.provider/media/1").encode()
        val recipe = EditRecipe(assetId = id, originalChecksum = "", createdAt = "2026-10-07", updatedAt = "2026-10-07", tone = Tone(exposure = 0.5f))

        recipes.save(recipe)

        assertEquals(recipe, recipes.get(id))
        assertNull(database.recipeDao().getAny(OPENED_LIBRARY_ID, "content://com.whatsapp.provider/media/1"))
        assertEquals(emptyList<RecipeEntity>(), database.recipeDao().withoutSha1())
    }

    @Test fun deviceRecipeCarriesHintsAndGetsItsHashInTheBackground() = runBlocking {
        val recipes = RoomRecipeRepository(database, configuration, hashes)
        val localId = "content://media/external/images/media/7"
        files[localId] = "abc".toByteArray()
        database.assetDao().upsertAll(listOf(
            asset(localId, "local:3:1700000000", library = DEVICE_LIBRARY_ID).copy(sizeBytes = 3, width = 4080, height = 3072),
        ))
        val id = AssetReference(DEVICE_LIBRARY_ID, localId).encode()
        recipes.save(EditRecipe(assetId = id, originalChecksum = "", createdAt = "2026-09-01", updatedAt = "2026-09-01"))
        val saved = database.recipeDao().get(DEVICE_LIBRARY_ID, localId)!!
        val hints = Json.decodeFromString<MediaHints>(saved.hintsJson!!)
        assertEquals(MediaHints("PXL_1.jpg", 3, "2026-08-12T10:15:00.000Z", 4080, 3072), hints)
        repeat(50) { if (database.recipeDao().get(DEVICE_LIBRARY_ID, localId)!!.contentSha1 == null) Thread.sleep(20) }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", database.recipeDao().get(DEVICE_LIBRARY_ID, localId)!!.contentSha1)
    }

    @Test fun deviceHashIsReadOnceAndReadAgainOnlyWhenTheFileChanges() = runBlocking {
        val localId = "content://media/external/images/media/9"
        val id = AssetReference(DEVICE_LIBRARY_ID, localId).encode()
        files[localId] = "abc".toByteArray()
        database.assetDao().upsertAll(listOf(asset(localId, "local:3:100", library = DEVICE_LIBRARY_ID)))
        assertNull(hashes.cached(id))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hashes.sha1(id))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hashes.sha1(id))
        assertEquals(1, opened)

        files[localId] = "abcd".toByteArray()
        database.assetDao().upsertAll(listOf(asset(localId, "local:4:200", library = DEVICE_LIBRARY_ID)))
        assertNull(hashes.cached(id))
        assertEquals("81fe8bfe87576c3ecb22426f8e57847382917acf", hashes.sha1(id))
        assertEquals(2, opened)
        assertEquals(listOf(localId), database.contentHashDao().bySha1("81fe8bfe87576c3ecb22426f8e57847382917acf").map { it.assetId })
    }

    @Test fun immichChecksumIsNormalisedAndFetchedWhenTheCatalogHasNone() = runBlocking {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", normalizeImmichChecksum("qZk+NkcGgWq6PiVxeFDCbJzQ2J0="))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", normalizeImmichChecksum("A9993E364706816ABA3E25717850C26C9CD0D89D"))
        assertNull(normalizeImmichChecksum("local:3:100"))
        assertNull(normalizeImmichChecksum(""))

        val id = AssetReference("lib", "bucket-only").encode()
        database.assetDao().upsertAll(listOf(asset("bucket-only", "")))
        immichChecksum = "qZk+NkcGgWq6PiVxeFDCbJzQ2J0="
        assertNull(hashes.cached(id))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hashes.sha1(id))
        immichChecksum = ""
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hashes.cached(id))
    }

    @Test fun deletingSomethingThatNeverLeftTheDeviceRemovesItAndOtherwiseLeavesAMark() = runBlocking {
        val presets = RoomSavedRecipeRepository(database, configuration)
        val recipe = EditRecipe(assetId = "", originalChecksum = "", createdAt = "x", updatedAt = "x")
        presets.save(SavedRecipe("local-only", "A", "C", recipe, "x", "x"))
        presets.delete("local-only")
        assertNull(database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "local-only"))

        presets.save(SavedRecipe("uploaded", "B", "C", recipe, "x", "x"))
        val row = database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "uploaded")!!
        database.savedRecipeDao().upsert(row.copy(sync = row.sync.copy(remoteRevision = 2, dirty = false)))
        presets.delete("uploaded")
        val mark = database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "uploaded")!!
        assertNotNull(mark.sync.deletedAt)
        assertTrue(mark.sync.dirty)
        assertEquals(2L, mark.sync.remoteRevision)
        assertTrue(presets.list().isEmpty())

        presets.save(SavedRecipe("uploaded", "B", "C", recipe, "x", "x"))
        assertEquals(listOf("uploaded"), presets.list().map { it.id })
        assertNull(database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "uploaded")!!.sync.deletedAt)
    }

    @Test fun deletedProjectLeavesTheHubAndItsDeviceMediaIsHashed() = runBlocking {
        val projects = RoomCompositionRepository(database, configuration, hashes)
        val localId = "content://media/external/images/media/11"
        files[localId] = "abc".toByteArray()
        database.assetDao().upsertAll(listOf(asset(localId, "local:3:100", library = DEVICE_LIBRARY_ID)))
        val now = Instant.EPOCH.toString()
        val project = CompositionProject(
            id = "project", name = "Teste", format = PageFormatPreset.STORY_9_16.format,
            pages = listOf(CompositionPage("one", 0)),
            background = CompositionBackground.Photo(
                MediaReference(AssetReference(DEVICE_LIBRARY_ID, localId).encode(), "", "PXL_1.jpg"),
            ),
            createdAt = now, updatedAt = now,
        )
        projects.save(project)
        repeat(50) { if (database.contentHashDao().get(DEVICE_LIBRARY_ID, localId) == null) Thread.sleep(20) }
        assertNotNull(database.contentHashDao().get(DEVICE_LIBRARY_ID, localId))

        val row = database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "project")!!
        database.compositionProjectDao().upsert(row.copy(sync = row.sync.copy(remoteRevision = 1, dirty = false)))
        projects.save(project)
        assertFalse("Saving on exit without changes is not an edit", database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "project")!!.sync.dirty)
        projects.delete("project")
        assertTrue(projects.observeAll().first().isEmpty())
        assertNull(projects.get("project"))
        assertNotNull(database.compositionProjectDao().getAny(GLOBAL_LIBRARY_ID, "project")!!.sync.deletedAt)
    }

    @Test fun backfillingDerivedExportsDoesNotEraseTheirSyncState() = runBlocking {
        val lib = "derived-lib"
        val prefs = context.getSharedPreferences(java.util.UUID.randomUUID().toString(), 0)
        prefs.edit().putString("libraries_v2", """[{"id":"device","name":"Dispositivo"},{"id":"$lib","name":"S","serverUrls":["https://s.test"],"userId":"u","apiKey":"k"}]""")
            .putString("selected_library", lib).commit()
        val config = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), object : ImmichApi by OkHttpImmichApi(OkHttpClient()) {})
        val derived = RoomDerivedAssetRepository(database, config)
        derived.record(AssetReference(lib, "photo").encode(), AssetReference(lib, "export").encode())
        val row = database.derivedAssetDao().getAny(lib, "export")!!
        assertTrue(row.sync.dirty)
        database.derivedAssetDao().upsert(row.copy(sync = row.sync.copy(remoteRevision = 7, dirty = false)))
        fun legacy(export: String) = """{"assetId":"","originalChecksum":"","createdAt":"x","updatedAt":"x","derivedAssetId":"$export"}"""
        database.recipeDao().upsert(RecipeEntity(lib, "photo", legacy("export"), "2026-01-01"))
        database.recipeDao().upsert(RecipeEntity(lib, "older", legacy("older-export"), "2026-01-01"))
        assertEquals(setOf("export", "older-export"), derived.derivedIds())
        assertEquals(SyncState(remoteRevision = 7, editedAt = row.sync.editedAt), database.derivedAssetDao().getAny(lib, "export")!!.sync)
        assertTrue(database.derivedAssetDao().getAny(lib, "older-export")!!.sync.dirty)
    }

    private fun asset(id: String, checksum: String, library: String = "lib") = AssetEntity(
        libraryKey = library, id = id, checksum = checksum, originalFileName = "PXL_1.jpg",
        fileCreatedAt = "2026-08-12T10:15:00.000Z", localDateTime = "", width = null, height = null,
        isFavorite = false, isEdited = false, hasLocalRecipe = false, type = "IMAGE",
    )
}
