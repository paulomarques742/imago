package eu.studio742.imago.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.SavedRecipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SavedRecipeOriginTest {
    private val configuration = Proxy.newProxyInstance(
        ConfigurationRepository::class.java.classLoader,
        arrayOf(ConfigurationRepository::class.java),
    ) { _, method, _ -> error("ConfigurationRepository.${method.name} is not used here") } as ConfigurationRepository

    private val recipe = EditRecipe(assetId = "", originalChecksum = "", createdAt = "x", updatedAt = "x")

    @Test fun anImportedRecipeKeepsItsOriginAndOneMadeHereHasNone() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val presets = RoomSavedRecipeRepository(database, configuration)
            presets.save(SavedRecipe("imported", "A", "C", recipe, "x", "x", origin = RecipeOrigin.XMP))
            presets.save(SavedRecipe("own", "B", "C", recipe, "x", "x"))

            assertEquals(RecipeOrigin.XMP, presets.list().single { it.id == "imported" }.origin)
            assertNull(presets.list().single { it.id == "own" }.origin)
        } finally {
            database.close()
        }
    }

    @Test fun version22UpgradesAndItsRecipesCountAsMadeHere() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "recipe-origin-migration.db"
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile!!.mkdirs() }, null)
        val schema = Json.parseToJsonElement(File(SCHEMA_22).readText()).jsonObject["database"]!!.jsonObject
        schema["entities"]!!.jsonArray.forEach { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            old.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            obj["indices"]?.jsonArray?.forEach { index ->
                old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            }
        }
        old.execSQL(
            "INSERT INTO saved_recipes (libraryKey, id, name, collection, recipeJson, createdAt, updatedAt, isFavorite) VALUES (?,?,?,?,?,?,?,0)",
            arrayOf<Any?>(GLOBAL_LIBRARY_ID, "kept", "Kept", "C", "{}", "x", "x"),
        )
        old.version = 22
        old.close()

        val upgraded = Room.databaseBuilder(context, ImmichRoomDatabase::class.java, name)
            .addMigrations(UnifiedMigration.MIGRATION_22_23).allowMainThreadQueries().build()
        try {
            upgraded.openHelper.writableDatabase // Room validates the whole schema here.
            val row = upgraded.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "kept")!!
            assertEquals("Kept", row.name)
            assertNull(row.origin)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        const val SCHEMA_22 = "schemas/eu.studio742.imago.core.data.db.ImmichRoomDatabase/22.json"
    }
}
