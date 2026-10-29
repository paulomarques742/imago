package eu.studio742.imago.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.paging.PagingSource
import androidx.room.Room
import eu.studio742.imago.core.data.db.AlbumMemberEntity
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.inLibrary
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
 * The movement of Live Photos and Motion Photos, as the catalogue keeps it.
 *
 * Immich keeps the movement as a hidden video the photo points at. Earlier versions let those videos
 * into the catalogue as photos of their own; this is what takes them out, and what keeps the relation
 * across the reads that do not mention it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LivePhotoCatalogueTest {
    private val library = "server"

    private fun asset(
        id: String,
        type: AssetType = AssetType.IMAGE,
        movement: String? = null,
        known: Boolean = movement != null,
    ) = ImmichAsset(
        id = id, checksum = "", originalFileName = "$id.jpg",
        fileCreatedAt = "2026-08-02T10:00:00.000Z", localDateTime = "2026-08-02T11:00",
        width = null, height = null, isFavorite = false, isEdited = false, type = type,
        livePhotoVideoId = movement, livePhotoKnown = known,
    )

    private fun <T> withDatabase(block: suspend (ImmichRoomDatabase) -> T): T = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(database) } finally { database.close() }
    }

    @Test fun aPhotoThatNamesItsMovementTakesTheOldLooseVideoOut() = withDatabase { database ->
        // What an earlier version wrote: the movement as a video of its own, in an album too.
        database.assetDao().upsertAll(
            listOf(asset("m1", AssetType.VIDEO), asset("v1", AssetType.VIDEO)).map { AssetEntity.fromDomain(library, it) },
        )
        database.albumMemberDao().upsertAll(listOf("m1", "v1").mapIndexed { index, id -> AlbumMemberEntity(library, "al", id, index, 1L) })

        val returned = database.upsertFromSearch(library, listOf(asset("p1", movement = "m1")))

        assertEquals(setOf("p1", "v1"), database.assetDao().byIds(library, listOf("p1", "m1", "v1")).map { it.id }.toSet())
        assertEquals("m1", database.assetDao().byIds(library, listOf("p1")).single().livePhotoVideoId)
        assertEquals("m1", returned.single().livePhotoVideoId)
        // The album opened from the catalogue no longer lists it either.
        val album = database.albumMemberDao().pagingSource(library, "al", false, false, null)
            .load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("v1"), album.data.map { it.id })
    }

    @Test fun anIdThatNamesAPhotoIsNeverTakenOut() = withDatabase { database ->
        database.assetDao().upsertAll(listOf(AssetEntity.fromDomain(library, asset("other"))))

        database.upsertFromSearch(library, listOf(asset("p1", movement = "other")))

        assertEquals(setOf("p1", "other"), database.assetDao().byIds(library, listOf("p1", "other")).map { it.id }.toSet())
    }

    @Test fun aReadThatDoesNotSayKeepsTheMovementAndOneThatSaysNoneErasesIt() = withDatabase { database ->
        database.upsertFromSearch(library, listOf(asset("p1", movement = "m1")))

        val unsaid = database.upsertFromSearch(library, listOf(asset("p1", known = false))).single()
        assertEquals("m1" to true, unsaid.livePhotoVideoId to unsaid.livePhotoKnown)
        assertEquals("m1", database.assetDao().byIds(library, listOf("p1")).single().livePhotoVideoId)

        // The relation was undone on the server: a read that carried the field says so.
        database.upsertFromSearch(library, listOf(asset("p1", known = true)))
        val row = database.assetDao().byIds(library, listOf("p1")).single()
        assertNull(row.livePhotoVideoId)
        assertTrue(row.livePhotoKnown)
    }

    @Test fun theTimelineKeepsWhatAnEarlierReadKnewWhenItsColumnIsMissing() {
        val before = AssetEntity.fromDomain(library, asset("p1", movement = "m1"))

        val unsaid = AssetEntity.fromDomain(library, asset("p1", known = false)).keeping(before)
        val none = AssetEntity.fromDomain(library, asset("p1", known = true)).keeping(before)
        val moved = AssetEntity.fromDomain(library, asset("p1", movement = "m2")).keeping(before)

        assertEquals("m1", unsaid.livePhotoVideoId)
        assertNull(none.livePhotoVideoId)
        assertEquals("m2", moved.livePhotoVideoId)
    }

    @Test fun theDetailWritesTheMovementItFound() = withDatabase { database ->
        database.assetDao().upsertAll(listOf(asset("p1"), asset("m1", AssetType.VIDEO)).map { AssetEntity.fromDomain(library, it) })

        database.assetDao().setMovement(library, "p1", "m1")
        database.forgetMovementsOf(library, listOf("m1"))

        val rows = database.assetDao().byIds(library, listOf("p1", "m1"))
        assertEquals(listOf("p1"), rows.map { it.id })
        assertEquals("m1" to true, rows.single().livePhotoVideoId to rows.single().livePhotoKnown)
    }

    @Test fun twoLibrariesWithTheSameIdsKeepTheirOwnMovements() {
        val a = asset("p1", movement = "m1").inLibrary("server-a")
        val b = asset("p1", movement = "m1").inLibrary("server-b")

        assertEquals(AssetReference("server-a", "m1"), AssetReference.parse(a.livePhotoVideoId!!))
        assertEquals(AssetReference("server-b", "m1"), AssetReference.parse(b.livePhotoVideoId!!))
        // Already qualified, a movement keeps the library it came from.
        assertEquals(b.livePhotoVideoId, asset("p1", movement = b.livePhotoVideoId).inLibrary("server-a").livePhotoVideoId)
    }

    @Test fun thePhonesCopyPlaysTheMovementOfItsServerCopy() = withDatabase { database ->
        fun row(libraryKey: String, id: String, createdAt: String, movement: String? = null) = AssetEntity(
            libraryKey, id, "", "20261003_180641.jpg", createdAt, createdAt.dropLast(1), null, null,
            isFavorite = false, isEdited = false, hasLocalRecipe = false, type = "IMAGE", livePhotoVideoId = movement,
            livePhotoKnown = movement != null,
        )
        database.assetDao().upsertAll(
            listOf(
                row(DEVICE_LIBRARY_ID, "phone", "2026-10-03T17:06:41.000Z"),
                row(library, "cloud", "2026-10-03T17:06:41.000Z", movement = "m1"),
            ),
        )

        val page = database.assetDao().unifiedPagingSource(DEVICE_LIBRARY_ID, library, false, false, null, null, null)
            .load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        val shown = page.data.single()
        val asset = shown.asset.withServerMovement(library, shown.serverLivePhotoVideoId).toDomain().inLibrary(shown.asset.libraryKey)

        assertEquals(AssetReference(DEVICE_LIBRARY_ID, "phone"), AssetReference.parse(asset.id))
        // Asked for on the server, with the server's key: not on the phone, where it does not exist.
        assertEquals(AssetReference(library, "m1"), AssetReference.parse(asset.livePhotoVideoId!!))
        // A phone photo with no copy on the server has no movement to borrow.
        val alone = row(DEVICE_LIBRARY_ID, "alone", "2026-10-04T10:00:00.000Z").withServerMovement(library, null)
        assertNull(alone.livePhotoVideoId)
    }

    @Test fun version20UpgradesAndReadsEveryMonthAgainWithoutLosingWork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("live-photo-migration.db")
        file.parentFile!!.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val schema = Json.parseToJsonElement(File(SCHEMA_20).readText()).jsonObject["database"]!!.jsonObject
        schema["entities"]!!.jsonArray.forEach { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            old.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            obj["indices"]?.jsonArray?.forEach { index ->
                old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
            }
        }
        // The photo and its movement as a loose video, both written by an earlier version.
        for ((id, type) in listOf("p1" to "IMAGE", "m1" to "VIDEO")) {
            old.execSQL(
                "INSERT INTO assets (libraryKey, id, checksum, originalFileName, fileCreatedAt, localDateTime, isFavorite, isEdited, hasLocalRecipe, type) " +
                    "VALUES (?, ?, '', ?, '2026-08-02T10:00:00.000Z', '2026-08-02T11:00', 0, 0, 1, ?)",
                arrayOf<Any?>(library, id, "$id.jpg", type),
            )
        }
        old.execSQL("INSERT INTO recipes (libraryKey, assetId, recipeJson, updatedAt) VALUES(?,?,?,?)", arrayOf<Any?>(library, "p1", "{}", "2026-08-02"))
        old.execSQL(
            "INSERT INTO composition_projects (libraryKey, id, name, projectJson, revision, createdAt, updatedAt) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(library, "project", "Project", "{}", 1, "2026-08-02", "2026-08-02"),
        )
        old.execSQL("INSERT INTO catalog_months VALUES(?,?,?,?)", arrayOf<Any?>(library, "2026-08-01", 2, "2026-08-03"))
        old.version = 20
        old.close()

        val upgraded = Room.databaseBuilder(context, ImmichRoomDatabase::class.java, "live-photo-migration.db")
            .addMigrations(UnifiedMigration.MIGRATION_20_21, UnifiedMigration.MIGRATION_21_22).allowMainThreadQueries().build()
        try {
            // Room validates the whole version 21 schema on opening.
            val sql = upgraded.openHelper.writableDatabase
            val p1 = upgraded.assetDao().byIds(library, listOf("p1")).single()
            assertNull(p1.livePhotoVideoId)
            assertFalse(p1.livePhotoKnown)
            sql.query("SELECT COUNT(*) FROM recipes").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM composition_projects").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            // Every month is read again once, so the loose movement goes with the month it is in.
            assertTrue(upgraded.catalogMonthDao().all(library).isEmpty())
        } finally {
            upgraded.close()
            context.deleteDatabase("live-photo-migration.db")
        }
    }

    private companion object {
        /** The schema Room exported for version 20; the tests run from the module's folder. */
        const val SCHEMA_20 = "schemas/eu.studio742.imago.core.data.db.ImmichRoomDatabase/20.json"
    }
}
