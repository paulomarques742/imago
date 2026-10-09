package eu.studio742.imago.core.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import eu.studio742.imago.core.data.ContentHashRepository
import eu.studio742.imago.core.data.db.BrandKitEntity
import eu.studio742.imago.core.data.db.BuiltInRecipeMarkEntity
import eu.studio742.imago.core.data.db.CompositionTemplateEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.SavedRecipeEntity
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID

/**
 * The engine against a real database and an in-memory server with the `sync_push` rules: one
 * revision per write, and a conflict when the base sent is not the current one.
 */
class SyncEngineTest {
    private val database = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val server = FakeSyncServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deviceId: String? = DEVICE_A
    private val hashes = ContentHashRepository(database, { null }, { error("no Immich") })
    private var clock = 1_000_000_000L
    private val engine = SyncEngine(
        database, server, hashes, { deviceId }, { "Computer" }, TestSyncTexts, scope,
        now = { "2026-09-16T12:00:00Z" }, debounceMillis = 20, clock = { clock },
    )

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `what is waiting to be sent goes up and is left clean`() = runBlocking {
        server.signIn()
        database.savedRecipeDao().upsert(preset("p1", "Tarde quente", dirty("2026-09-16T10:00:00Z")))
        database.compositionTemplateDao().upsert(template("t1", "Grelha", dirty("2026-09-16T10:01:00Z")))
        database.brandKitDao().upsert(BrandKitEntity(GLOBAL_LIBRARY_ID, """{"colors":["#112233"]}""", "2026-09-16", dirty("2026-09-16T10:02:00Z")))

        assertTrue(engine.syncNow())

        assertEquals("Tarde quente", server.row(SyncEntity.SAVED_RECIPE, "p1")!!.payload.string("name"))
        assertEquals(DEVICE_A, server.row(SyncEntity.SAVED_RECIPE, "p1")!!.device)
        assertEquals("Grelha", server.row(SyncEntity.TEMPLATE, "t1")!!.payload.string("name"))
        assertEquals("#112233", server.row(SyncEntity.BRAND_KIT, "brand-kit")!!.payload.toString().substringAfter("[\"").substringBefore("\""))
        val preset = database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p1")!!
        assertFalse(preset.sync.dirty)
        assertEquals(1L, preset.sync.remoteRevision)
        assertEquals(0, engine.status.value.pending)
        assertNull(engine.status.value.error)
    }

    @Test
    fun `the heart on an app preset goes up, and the one from another device arrives`() = runBlocking {
        server.signIn()
        database.builtInRecipeMarkDao().upsert(
            BuiltInRecipeMarkEntity("built-in-pastel", isFavorite = true, usedAt = null, updatedAt = "2026-09-16T10:00:00Z", sync = dirty("2026-09-16T10:00:00Z")),
        )
        server.store(
            SyncEntity.BUILT_IN_RECIPE_MARK, "built-in-dramatic",
            buildJsonObject {
                put("isFavorite", JsonPrimitive(false))
                put("usedAt", JsonPrimitive("2026-09-16T09:00:00Z"))
            },
            "2026-09-16T09:00:00Z", DEVICE_B,
        )

        assertTrue(engine.syncNow())

        val sent = server.row(SyncEntity.BUILT_IN_RECIPE_MARK, "built-in-pastel")!!
        assertEquals("true", sent.payload.string("isFavorite"))
        assertFalse(database.builtInRecipeMarkDao().getAny("built-in-pastel")!!.sync.dirty)
        val received = database.builtInRecipeMarkDao().getAny("built-in-dramatic")!!
        assertFalse(received.isFavorite)
        assertEquals("2026-09-16T09:00:00Z", received.usedAt)
        assertFalse(received.sync.dirty)
    }

    @Test
    fun `coming back to the app runs nothing after a recent good run, unless something waits`() = runBlocking {
        server.signIn()
        assertTrue("never ran", engine.isStale())
        assertTrue(engine.syncNow())
        assertFalse(engine.isStale())

        clock += 14 * 60_000L
        assertFalse("fourteen minutes later", engine.isStale())
        database.savedRecipeDao().upsert(preset("p5", "Edited here", dirty("2026-09-16T11:00:00Z")))
        assertTrue("what waits to go up always goes", engine.isStale())

        assertTrue(engine.syncNow())
        clock += 16 * 60_000L
        assertTrue("old enough to look again", engine.isStale())
    }

    @Test
    fun `a deletion goes up as a mark and stops existing here`() = runBlocking {
        server.signIn()
        server.store(SyncEntity.SAVED_RECIPE, "p1", presetPayload("Antigo"), "2026-09-15T10:00:00Z", DEVICE_A)
        database.savedRecipeDao().upsert(preset("p1", "Antigo", SyncState(remoteRevision = 1, editedAt = "2026-09-15T10:00:00Z").deleted("2026-09-16T10:00:00Z")))

        assertTrue(engine.syncNow())

        val remote = server.row(SyncEntity.SAVED_RECIPE, "p1")!!
        assertNotNull(remote.deletedAt)
        assertEquals(2L, remote.revision)
        assertNull(database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p1"))
    }

    @Test
    fun `what another device changed arrives, and the cursor does not ask for it again`() = runBlocking {
        server.signIn()
        server.store(SyncEntity.SAVED_RECIPE, "p2", presetPayload("From the phone"), "2026-09-16T09:00:00Z", DEVICE_B)

        assertTrue(engine.syncNow())

        val preset = database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p2")!!
        assertEquals("From the phone", preset.name)
        assertFalse(preset.sync.dirty)
        assertEquals(DEVICE_B, preset.sync.editedByDevice)
        val cursor = database.syncCursorDao().get(SyncEntity.SAVED_RECIPE.wireName)
        assertEquals(server.row(SyncEntity.SAVED_RECIPE, "p2")!!.seq, cursor)

        server.pullCursors.clear()
        assertTrue(engine.syncNow())
        assertEquals(cursor, server.pullCursors[SyncEntity.SAVED_RECIPE])
    }

    @Test
    fun `the most recent local edit wins, and the remote one is kept`() = runBlocking {
        server.signIn()
        server.store(SyncEntity.SAVED_RECIPE, "p1", presetPayload("Original"), "2026-09-16T08:00:00Z", DEVICE_A)
        server.store(SyncEntity.SAVED_RECIPE, "p1", presetPayload("From the phone"), "2026-09-16T09:00:00Z", DEVICE_B)
        // This device was still on revision 1, and edited after the phone.
        database.savedRecipeDao().upsert(preset("p1", "From the computer", SyncState(remoteRevision = 1).edited("2026-09-16T10:00:00Z")))

        assertTrue(engine.syncNow())

        val remote = server.row(SyncEntity.SAVED_RECIPE, "p1")!!
        assertEquals("From the computer", remote.payload.string("name"))
        assertEquals(3L, remote.revision)
        assertEquals("From the computer", database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p1")!!.name)
        val conflict = server.conflicts.single()
        assertEquals("From the phone", conflict.payload.string("name"))
        assertEquals(DEVICE_B, conflict.deviceId)
    }

    @Test
    fun `the most recent remote edit wins, and the local one is kept`() = runBlocking {
        server.signIn()
        server.store(SyncEntity.SAVED_RECIPE, "p1", presetPayload("Original"), "2026-09-16T08:00:00Z", DEVICE_A)
        database.savedRecipeDao().upsert(preset("p1", "From the computer", SyncState(remoteRevision = 1).edited("2026-09-16T09:00:00Z")))
        server.store(SyncEntity.SAVED_RECIPE, "p1", presetPayload("From the phone"), "2026-09-16T10:00:00Z", DEVICE_B)

        assertTrue(engine.syncNow())

        val local = database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p1")!!
        assertEquals("From the phone", local.name)
        assertFalse(local.sync.dirty)
        assertEquals(2L, local.sync.remoteRevision)
        assertEquals(2L, server.row(SyncEntity.SAVED_RECIPE, "p1")!!.revision)
        val conflict = server.conflicts.single()
        assertEquals("From the computer", conflict.payload.string("name"))
        assertEquals(DEVICE_A, conflict.deviceId)
    }

    @Test
    fun `an edit waiting to be sent is not overwritten by what arrives, and goes up on the next run`() = runBlocking {
        server.signIn()
        server.store(SyncEntity.TEMPLATE, "t1", templatePayload("Original"), "2026-09-16T08:00:00Z", DEVICE_A)
        server.store(SyncEntity.TEMPLATE, "t1", templatePayload("From the phone"), "2026-09-16T09:00:00Z", DEVICE_B)
        database.compositionTemplateDao().upsert(template("t1", "From the computer", SyncState(remoteRevision = 1).edited("2026-09-16T10:00:00Z")))
        // While this device resolves the conflict, the phone writes again: the resolution never
        // goes up, and the row is still waiting to be sent when the page with revision 3 arrives.
        server.beforePush = { call ->
            if (call == 2) server.store(SyncEntity.TEMPLATE, "t1", templatePayload("Once more"), "2026-09-16T09:30:00Z", DEVICE_B)
        }

        assertTrue(engine.syncNow())
        val kept = database.compositionTemplateDao().getAny(GLOBAL_LIBRARY_ID, "t1")!!
        assertEquals("From the computer", kept.name)
        assertTrue(kept.sync.dirty)

        server.beforePush = null
        assertTrue(engine.syncNow())
        assertEquals("From the computer", server.row(SyncEntity.TEMPLATE, "t1")!!.payload.string("name"))
        assertFalse(database.compositionTemplateDao().getAny(GLOBAL_LIBRARY_ID, "t1")!!.sync.dirty)
    }

    @Test
    fun `a refused change is not retried, and the notice stays`() = runBlocking {
        server.signIn()
        database.savedRecipeDao().upsert(preset("p1", "Invalid", dirty("2026-09-16T10:00:00Z")))
        server.rejectNext = true

        engine.syncNow()

        assertNotNull(engine.status.value.error)
        val local = database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p1")!!
        assertFalse(local.sync.dirty)
        assertNull(local.sync.remoteRevision)
        assertEquals(0, server.pushCalls)
    }

    @Test
    fun `without an account or a device nothing leaves`() = runBlocking {
        database.savedRecipeDao().upsert(preset("p1", "Local", dirty("2026-09-16T10:00:00Z")))
        assertFalse(engine.syncNow())

        server.signIn()
        deviceId = null
        assertFalse(engine.syncNow())
        assertNull(server.row(SyncEntity.SAVED_RECIPE, "p1"))
    }

    @Test
    fun `once wired, a save goes up on its own`() = runBlocking {
        engine.start()
        server.signIn()
        delay(100)
        database.savedRecipeDao().upsert(preset("p9", "Saved now", dirty("2026-09-16T11:00:00Z")))

        withTimeout(10_000) {
            while (database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p9")?.sync?.dirty != false) delay(20)
        }
        assertEquals("Saved now", server.row(SyncEntity.SAVED_RECIPE, "p9")!!.payload.string("name"))
    }

    // --- dados

    private fun dirty(editedAt: String) = SyncState(editedAt = editedAt, dirty = true)

    private fun preset(id: String, name: String, sync: SyncState) = SavedRecipeEntity(
        libraryKey = GLOBAL_LIBRARY_ID, id = id, name = name, collection = "Summer", recipeJson = "{}",
        createdAt = "2026-09-01", updatedAt = "2026-09-16", sync = sync,
    )

    private fun template(id: String, name: String, sync: SyncState) = CompositionTemplateEntity(
        libraryKey = GLOBAL_LIBRARY_ID, id = id, name = name, templateJson = "{}",
        createdAt = "2026-09-01", updatedAt = "2026-09-16", sync = sync,
    )

    private fun presetPayload(name: String) = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("collection", JsonPrimitive("Summer"))
        put("recipe", JsonObject(emptyMap()))
    }

    private fun templatePayload(name: String) = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("template", JsonObject(emptyMap()))
    }

    private companion object {
        const val DEVICE_A = "device-a"
        const val DEVICE_B = "device-b"
    }
}
