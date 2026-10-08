package eu.studio742.imago.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LibraryMigrationTest {
    @Test fun upgradesRealVersion8WithoutLosingRecipesOrMixedOriginReferences() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("migration-test.db")
        file.parentFile!!.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val schema = Json.parseToJsonElement(javaClass.getResource("/schema8.json")!!.readText()).jsonObject["database"]!!.jsonObject
        schema["entities"]!!.jsonArray.forEach { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            old.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            obj["indices"]!!.jsonArray.forEach { index ->
                old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            }
        }
        val project = """{"schemaVersion":1,"id":"same-project","name":"Project","elements":[{"media":{"assetId":"same-photo"}}],"background":{"media":{"assetId":"background"}}}"""
        for (library in listOf("server-a", "server-b")) {
            old.execSQL("INSERT INTO composition_projects VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(library,"same-project","Project",project,1,"2026-01-01","2026-01-01"))
            old.execSQL("INSERT INTO recipes VALUES(?,?,?,?)", arrayOf<Any?>(library,"same-photo","{}","2026-01-01"))
        }
        old.version = 8
        old.close()
        val upgraded = Room.databaseBuilder(context, ImmichRoomDatabase::class.java, "migration-test.db")
            .addMigrations(LibraryMigration.MIGRATION_8_9, LibraryMigration.MIGRATION_9_10, SyncMigration.MIGRATION_10_11, UnifiedMigration.MIGRATION_11_12, UnifiedMigration.MIGRATION_12_13, UnifiedMigration.MIGRATION_13_14).allowMainThreadQueries().build()
        try {
            val sql = upgraded.openHelper.writableDatabase // Room validates the complete new schema here.
            assertEquals(setOf("server-a", "server-b"), upgraded.libraryIdentityDao().ids().toSet())
            sql.query("SELECT COUNT(*) FROM recipes").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            val references = mutableSetOf<String>()
            sql.query("SELECT libraryKey, id, projectJson FROM composition_projects").use { cursor ->
                while (cursor.moveToNext()) {
                    assertEquals(GLOBAL_LIBRARY_ID, cursor.getString(0))
                    val json = Json.parseToJsonElement(cursor.getString(2)).jsonObject
                    assertEquals(cursor.getString(1), json["id"]!!.jsonPrimitive.content)
                    val ref = json["elements"]!!.jsonArray[0].jsonObject["media"]!!.jsonObject["assetId"]!!.jsonPrimitive.content
                    references += AssetReference.parse(ref).libraryId
                    assertEquals(2, json["schemaVersion"]!!.jsonPrimitive.int)
                }
            }
            assertEquals(setOf("server-a", "server-b"), references)
        } finally { upgraded.close(); context.deleteDatabase("migration-test.db") }
    }
    @Test fun nestedBackgroundsLogosAndRecipesRetainSourceAndUnknownFields() {
        val raw = Json.parseToJsonElement("""{"logos":[{"assetId":"logo"}],"backgroundOverride":{"media":{"assetId":"photo"}},"recipe":{"assetId":"photo","exposure":0.7},"futureField":true}""")
        val migrated = qualifyLegacyMedia(raw, "source")
        assertEquals(migrated, qualifyLegacyMedia(migrated, "different"))
        assertTrue(migrated.jsonObject["futureField"]!!.jsonPrimitive.boolean)
        assertEquals("source", AssetReference.parse(migrated.jsonObject["logos"]!!.jsonArray[0].jsonObject["assetId"]!!.jsonPrimitive.content).libraryId)
    }
}
