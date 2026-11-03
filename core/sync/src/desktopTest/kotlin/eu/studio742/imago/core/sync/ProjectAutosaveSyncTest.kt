package eu.studio742.imago.core.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.data.ContentHashRepository
import eu.studio742.imago.core.data.MediaResolver
import eu.studio742.imago.core.data.ReferenceTranslator
import eu.studio742.imago.core.data.db.CompositionProjectEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Saves that arrive while an earlier revision is being acknowledged are still the same project. */
class ProjectAutosaveSyncTest {
    private val database = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private val server = FakeSyncServer().apply { signIn() }
    private val hashes = ContentHashRepository(database, { null }, { error("unused") })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { encodeDefaults = true; classDiscriminator = "elementType" }
    private val dao get() = database.compositionProjectDao()
    private val firstStamp = "2026-11-03T10:00:00Z"
    private val laterStamp = "2026-11-03T10:00:01Z"
    private fun row(name: String, stamp: String) = CompositionProjectEntity(
        GLOBAL_LIBRARY_ID, "project", name, json.encodeToString(CompositionProject(id = "project", name = name,
            format = PageFormatPreset.SQUARE_1_1.format, pages = listOf(CompositionPage("page", 0)),
            createdAt = firstStamp, updatedAt = stamp)), 1, firstStamp, stamp, SyncState().edited(stamp))
    private fun engine(backend: SyncBackend = server) = SyncEngine(database, backend, hashes,
        { "device-a" }, { "Phone" }, TestSyncTexts, scope, debounceMillis = 20)
    private val names = object : DeviceNames {
        override val thisDevice = "Phone"
        override suspend fun of(deviceId: String?) = "Computer"
    }
    private fun projects() = ProjectSync(database, ReferenceTranslator(database, MediaResolver(database, hashes)),
        hashes, TestSyncTexts) { laterStamp }

    @After fun tearDown() { scope.cancel(); database.close() }

    @Test fun `an edit during upload retains the new content and advances its base without creating copies`() = runBlocking {
        dao.upsert(row("First", firstStamp))
        var edited = false
        server.beforePush = {
            if (!edited) {
                edited = true
                runBlocking { dao.upsert(row("Latest", laterStamp)) }
            }
        }
        val engine = engine()
        assertTrue(engine.syncNow())
        val waiting = dao.getAny(GLOBAL_LIBRARY_ID, "project")!!
        assertEquals("Latest", waiting.name)
        assertTrue(waiting.sync.dirty)
        assertEquals(1L, waiting.sync.remoteRevision)
        repeat(4) { assertTrue(engine.syncNow()) }
        assertEquals(1, dao.observeAll(GLOBAL_LIBRARY_ID).first().size)
        assertEquals(1, server.rows(SyncEntity.PROJECT).size)
        assertEquals("Latest", server.row(SyncEntity.PROJECT, "project")!!.payload.string("name"))
        assertFalse(dao.getAny(GLOBAL_LIBRARY_ID, "project")!!.sync.dirty)
    }

    @Test fun `retrying an applied upload after losing its response does not create a conflict copy`() = runBlocking {
        dao.upsert(row("Draft", firstStamp))
        var loseResponse = true
        val backend = object : SyncBackend by server {
            override suspend fun push(entity: SyncEntity, changes: List<OutgoingChange>): List<PushResult> {
                val applied = server.push(entity, changes)
                if (entity == SyncEntity.PROJECT && loseResponse) {
                    loseResponse = false
                    throw IOException("response lost")
                }
                return applied
            }
        }
        val engine = engine(backend)
        assertFalse(engine.syncNow())
        repeat(3) { assertTrue(engine.syncNow()) }
        assertEquals(1, dao.observeAll(GLOBAL_LIBRARY_ID).first().size)
        assertEquals(1, server.rows(SyncEntity.PROJECT).size)
        assertFalse(dao.getAny(GLOBAL_LIBRARY_ID, "project")!!.sync.dirty)
    }

    @Test fun `replaying the same remote conflict preserves one copy of that version`() = runBlocking {
        dao.upsert(row("Remote version", firstStamp))
        val projects = projects()
        val snapshot = projects.pending(10).single()
        val remote = IncomingChange(snapshot.key, 4, 4, snapshot.payload, firstStamp, "device-b", null)
        dao.upsert(row("Local version", laterStamp))
        repeat(3) { assertTrue(projects.keepRemoteLoser(remote, names)) }
        val rows = dao.observeAll(GLOBAL_LIBRARY_ID).first()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.name == "Local version" })
        assertTrue(rows.any { it.name == "Remote version (conflict · Computer)" })
    }

    @Test fun `replaying the same local conflict preserves one copy of that version`() = runBlocking {
        dao.upsert(row("Local version", firstStamp))
        val projects = projects()
        val snapshot = projects.pending(10).single()
        repeat(3) { assertTrue(projects.keepLocalLoser(snapshot, names)) }
        assertEquals(2, dao.observeAll(GLOBAL_LIBRARY_ID).first().size)
    }
}
