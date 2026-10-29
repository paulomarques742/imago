package eu.studio742.imago.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.LocalMotionEntity
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EmbeddedMotion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The movements found inside the phone's photos, as the catalogue keeps them across MediaStore syncs,
 * which rewrite every row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocalMotionCatalogueTest {
    private fun row(id: String, identity: String = "local:100:1", mime: String = "image/jpeg", type: String = "IMAGE", at: String = "2026-10-29T09:53:48.000Z") =
        AssetEntity(DEVICE_LIBRARY_ID, id, identity, "$id.jpg", at, at.dropLast(1), null, null,
            isFavorite = false, isEdited = false, hasLocalRecipe = false, type = type, mimeType = mime)

    private val movement = EmbeddedMotion("content://media/external/images/media/1", 4_533_174, 4_179_782).encode()

    private fun <T> withDatabase(block: suspend (ImmichRoomDatabase) -> T): T = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(database) } finally { database.close() }
    }

    @Test fun onlyJpegPhotosNotLookedAtArePendingTheNewestFirst() = withDatabase { database ->
        database.assetDao().upsertAll(
            listOf(
                row("old", at = "2025-01-01T10:00:00.000Z"),
                row("new"),
                row("looked"),
                row("png", mime = "image/png"),
                row("video", mime = "video/mp4", type = "VIDEO"),
            ),
        )
        database.localMotionDao().upsertAll(listOf(LocalMotionEntity(DEVICE_LIBRARY_ID, "looked", "local:100:1", null)))

        assertEquals(listOf("new", "old"), database.localMotionDao().pending(DEVICE_LIBRARY_ID, 10).map { it.id })
    }

    @Test fun aSyncThatRewritesTheRowsKeepsWhatWasFound() = withDatabase { database ->
        database.localMotionDao().upsertAll(
            listOf(
                LocalMotionEntity(DEVICE_LIBRARY_ID, "1", "local:100:1", movement),
                LocalMotionEntity(DEVICE_LIBRARY_ID, "2", "local:100:1", null),
            ),
        )
        // What a MediaStore sync writes: rows that know nothing of movements.
        database.assetDao().upsertAll(listOf(row("1"), row("2"), row("3")))

        database.localMotionDao().restore(DEVICE_LIBRARY_ID)

        val rows = database.assetDao().byIds(DEVICE_LIBRARY_ID, listOf("1", "2", "3")).associateBy { it.id }
        assertEquals(movement to true, rows.getValue("1").livePhotoVideoId to rows.getValue("1").livePhotoKnown)
        assertEquals(null to true, rows.getValue("2").livePhotoVideoId to rows.getValue("2").livePhotoKnown)
        // Not looked at yet: unknown, not "without".
        assertFalse(rows.getValue("3").livePhotoKnown)
    }

    @Test fun aFileChangedSinceTheLookIsLookedAtAgain() = withDatabase { database ->
        database.localMotionDao().upsertAll(listOf(LocalMotionEntity(DEVICE_LIBRARY_ID, "1", "local:100:1", movement)))
        // Edited in another app: another size, another moment.
        database.assetDao().upsertAll(listOf(row("1", identity = "local:90:2")))

        database.localMotionDao().restore(DEVICE_LIBRARY_ID)

        assertNull(database.assetDao().byIds(DEVICE_LIBRARY_ID, listOf("1")).single().livePhotoVideoId)
        assertEquals(listOf("1"), database.localMotionDao().pending(DEVICE_LIBRARY_ID, 10).map { it.id })
    }

    @Test fun aDeletedPhotosLookIsForgotten() = withDatabase { database ->
        database.localMotionDao().upsertAll(listOf(LocalMotionEntity(DEVICE_LIBRARY_ID, "gone", "local:100:1", movement)))

        database.localMotionDao().forgetGone(DEVICE_LIBRARY_ID)

        database.assetDao().upsertAll(listOf(row("gone")))
        assertEquals(listOf("gone"), database.localMotionDao().pending(DEVICE_LIBRARY_ID, 10).map { it.id })
    }

    @Test fun version21UpgradesWithTheLooksTable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "local-motion-migration.db"
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile!!.mkdirs() }, null)
        val schema = Json.parseToJsonElement(File(SCHEMA_21).readText()).jsonObject["database"]!!.jsonObject
        schema["entities"]!!.jsonArray.forEach { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            old.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            obj["indices"]?.jsonArray?.forEach { index ->
                old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            }
        }
        old.version = 21
        old.close()

        val upgraded = Room.databaseBuilder(context, ImmichRoomDatabase::class.java, name)
            .addMigrations(UnifiedMigration.MIGRATION_21_22).allowMainThreadQueries().build()
        try {
            upgraded.openHelper.writableDatabase
            upgraded.assetDao().upsertAll(listOf(row("1")))
            assertTrue(upgraded.localMotionDao().pending(DEVICE_LIBRARY_ID, 10).isNotEmpty())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        const val SCHEMA_21 = "schemas/eu.studio742.imago.core.data.db.ImmichRoomDatabase/21.json"
    }
}
