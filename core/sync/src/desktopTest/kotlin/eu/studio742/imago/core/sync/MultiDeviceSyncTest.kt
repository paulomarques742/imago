package eu.studio742.imago.core.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import eu.studio742.imago.core.data.ContentHashRepository
import eu.studio742.imago.core.data.MediaResolver
import eu.studio742.imago.core.data.ReferenceTranslator
import eu.studio742.imago.core.data.RemoteRecipeStore
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.BrandKitEntity
import eu.studio742.imago.core.data.db.CompositionProjectEntity
import eu.studio742.imago.core.data.db.CompositionTemplateEntity
import eu.studio742.imago.core.data.db.DerivedAssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.LibraryLinkEntity
import eu.studio742.imago.core.data.db.RecipeEntity
import eu.studio742.imago.core.data.db.SavedRecipeEntity
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
import eu.studio742.imago.core.model.Tone
import java.security.MessageDigest
import java.util.Base64

/**
 * Two devices, each with its own database, and one account: references, recipes by content and
 * conflicts, and the privacy invariants.
 */
class MultiDeviceSyncTest {
    private val server = FakeSyncServer().apply { signIn() }
    private val phone = Device("device-a", "Phone", server)
    private val computer = Device("device-b", "Computer", server)

    @After
    fun tearDown() {
        phone.close()
        computer.close()
    }

    @Test
    fun `a project with an Immich photo reaches the other device with that device's local key`() = runBlocking {
        phone.link("immich-phone", "remote-immich")
        computer.link("immich-computer", "remote-immich")
        val sha1 = sha1("praia".toByteArray())
        val onPhone = phone.immichPhoto("immich-phone", "asset-1", sha1)
        val onComputer = computer.immichPhoto("immich-computer", "asset-1", sha1)
        phone.project("p1", "Viagem", onPhone, "2026-09-16T10:00:00Z")
        phone.database.derivedAssetDao().upsert(
            DerivedAssetEntity("immich-phone", "derived-1", "asset-1", "2026-09-16", SyncState().edited("2026-09-16T10:00:00Z")),
        )

        assertTrue(phone.engine.syncNow())
        assertTrue(computer.engine.syncNow())

        val received = computer.database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "p1")!!
        assertTrue(received.projectJson.contains(onComputer))
        assertFalse(received.projectJson.contains(onPhone))
        // The checksum goes back to this device's catalogue form, which is the one the editor compares.
        assertTrue(received.projectJson.contains(MediaResolver.immichChecksumOf(sha1)!!))
        assertEquals(listOf("derived-1"), computer.database.derivedAssetDao().ids("immich-computer"))
        val wire = server.rows(SyncEntity.PROJECT).single().payload.toString()
        assertTrue(wire.contains("imago-r:remote-immich:"))
        assertTrue(wire.contains(sha1))
    }

    @Test
    fun `a phone photo is found on the computer by its content`() = runBlocking {
        val bytes = "IMG_1 original".toByteArray()
        phone.link(DEVICE_LIBRARY_ID, "remote-phone")
        computer.link(DEVICE_LIBRARY_ID, "remote-computer")
        val onPhone = phone.devicePhoto("content://media/1", bytes, "IMG_1.jpg")
        val onComputer = computer.devicePhoto("file:///C:/Photos/IMG_1.jpg", bytes, "IMG_1.jpg")
        phone.hashes.sha1(onPhone)
        computer.hashes.sha1(onComputer)
        phone.project("p1", "Tarde", onPhone, "2026-09-16T10:00:00Z")

        phone.engine.syncNow()
        computer.engine.syncNow()

        assertTrue(computer.database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "p1")!!.projectJson.contains(onComputer))
        val media = server.rows(SyncEntity.PROJECT).single().payload.toString()
        assertTrue("as pistas sobem: $media", media.contains("\"sizeBytes\":${bytes.size}") && media.contains("\"takenAt\""))
    }

    @Test
    fun `without the photo it stays unresolved, and resolves when it shows up`() = runBlocking {
        val bytes = "IMG_2 original".toByteArray()
        phone.link(DEVICE_LIBRARY_ID, "remote-phone")
        computer.link(DEVICE_LIBRARY_ID, "remote-computer")
        val onPhone = phone.devicePhoto("content://media/2", bytes, "IMG_2.jpg")
        phone.hashes.sha1(onPhone)
        phone.project("p1", "Tarde", onPhone, "2026-09-16T10:00:00Z")
        phone.engine.syncNow()
        computer.engine.syncNow()

        val waiting = computer.database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "p1")!!
        assertTrue("guardada como veio: ${waiting.projectJson}", waiting.projectJson.contains(ReferenceTranslator.REMOTE_MARKER))

        // The photo reaches the computer; nobody has read it yet, and confirming reads the file.
        val onComputer = computer.devicePhoto("file:///C:/Photos/IMG_2.jpg", bytes, "IMG_2.jpg")
        computer.engine.resolveWaiting(readFiles = false)
        assertFalse(computer.database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "p1")!!.projectJson.contains(onComputer))
        computer.engine.resolveWaiting(readFiles = true)

        val resolved = computer.database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "p1")!!
        assertTrue(resolved.projectJson.contains(onComputer))
        assertFalse("resolving is not editing", resolved.sync.dirty)
    }

    @Test
    fun `a project goes up without the hash and goes up again once it is ready`() = runBlocking {
        val bytes = "IMG_3 original".toByteArray()
        phone.link(DEVICE_LIBRARY_ID, "remote-phone")
        val onPhone = phone.devicePhoto("content://media/3", bytes, "IMG_3.jpg")
        phone.project("p1", "Tarde", onPhone, "2026-09-16T10:00:00Z")
        phone.engine.start()

        // Without a hash, the project goes up anyway, and the hash is requested in the background.
        withTimeout(10_000) {
            while (server.row(SyncEntity.PROJECT, "p1")?.payload?.toString()?.contains(sha1(bytes)) != true) delay(20)
        }
        assertEquals(2L, server.row(SyncEntity.PROJECT, "p1")!!.revision)
        assertTrue(server.sent.first().payload.toString().contains("\"checksum\":\"\""))
    }

    @Test
    fun `a project edited on both devices keeps the most recent and a copy of the other`() = runBlocking {
        phone.link("immich-phone", "remote-immich")
        computer.link("immich-computer", "remote-immich")
        val sha1 = sha1("retrato".toByteArray())
        phone.immichPhoto("immich-phone", "asset-1", sha1)
        val onComputer = computer.immichPhoto("immich-computer", "asset-1", sha1)
        phone.project("p1", "Portrait", AssetReference("immich-phone", "asset-1").encode(), "2026-09-16T10:00:00Z")
        phone.engine.syncNow()
        computer.engine.syncNow()

        // Both edit without seeing each other; the computer edits later.
        phone.editProject("p1", "Phone portrait", "2026-09-16T11:00:00Z")
        computer.editProject("p1", "Computer portrait", "2026-09-16T12:00:00Z")
        phone.engine.syncNow()
        computer.engine.syncNow()
        phone.engine.syncNow()

        for (device in listOf(phone, computer)) {
            val names = device.database.compositionProjectDao().observeAll(GLOBAL_LIBRARY_ID).first().map { it.name }.sorted()
            assertEquals(listOf("Computer portrait", "Phone portrait (conflict · Phone)"), names)
        }
        val copy = computer.database.compositionProjectDao().observeAll(GLOBAL_LIBRARY_ID).first().first { it.id != "p1" }
        assertTrue("the copy points to this device's photo", copy.projectJson.contains(onComputer))
    }

    @Test
    fun `the recipe of a phone photo applies to the same photo in the computer's Immich`() = runBlocking {
        val bytes = "IMG_4 original".toByteArray()
        val sha1 = sha1(bytes)
        phone.link(DEVICE_LIBRARY_ID, "remote-phone")
        computer.link("immich-computer", "remote-immich")
        val onPhone = phone.devicePhoto("content://media/4", bytes, "IMG_4.jpg")
        phone.hashes.sha1(onPhone)
        val onComputer = computer.immichPhoto("immich-computer", "asset-4", sha1)
        phone.recipe(onPhone, sha1, exposure = 1.5f, editedAt = "2026-09-16T10:00:00Z")

        phone.engine.syncNow()
        computer.engine.syncNow()

        val row = computer.database.recipeDao().get("immich-computer", "asset-4")!!
        val recipe = json.decodeFromString<EditRecipe>(row.recipeJson)
        assertEquals(1.5f, recipe.tone.exposure)
        assertEquals(onComputer, recipe.assetId)
        assertEquals(MediaResolver.immichChecksumOf(sha1), recipe.originalChecksum)
        assertFalse(row.sync.dirty)
        assertTrue(computer.database.assetDao().asset("immich-computer", "asset-4")!!.hasLocalRecipe)
        assertNotNull("the hints go up with the recipe", server.row(SyncEntity.RECIPE, sha1)!!.hints)
    }

    @Test
    fun `recipes from before the account go up on sign-in and reach the other device`() = runBlocking {
        val sha1 = sha1("IMG_9 original".toByteArray())
        phone.link("immich-phone", "remote-immich")
        computer.link("immich-computer", "remote-immich")
        val onPhone = phone.immichPhoto("immich-phone", "asset-9", sha1)
        computer.immichPhoto("immich-computer", "asset-9b", sha1)
        // As the migration left them: without a remote revision, without a waiting-to-send mark and without the SHA-1.
        phone.database.recipeDao().upsert(
            RecipeEntity("immich-phone", "asset-9", recipeJson(onPhone, 0.8f), "2026-08-27", null, null,
                SyncState(editedAt = "2026-08-27T18:10:13Z")),
        )
        phone.engine.start()

        withTimeout(10_000) { while (server.row(SyncEntity.RECIPE, sha1) == null) delay(20) }
        computer.engine.syncNow()

        val row = computer.database.recipeDao().get("immich-computer", "asset-9b")!!
        assertEquals(0.8f, json.decodeFromString<EditRecipe>(row.recipeJson).tone.exposure)
        assertEquals("2026-08-27T18:10:13Z", server.row(SyncEntity.RECIPE, sha1)!!.editedAt)
    }

    @Test
    fun `a waiting recipe only applies to the photo the hash confirms`() = runBlocking {
        val bytes = "IMG_5 original".toByteArray()
        val sha1 = sha1(bytes)
        phone.link(DEVICE_LIBRARY_ID, "remote-phone")
        val onPhone = phone.devicePhoto("content://media/5", bytes, "IMG_5.jpg")
        phone.hashes.sha1(onPhone)
        phone.recipe(onPhone, sha1, exposure = -1f, editedAt = "2026-09-16T10:00:00Z")
        phone.engine.syncNow()
        computer.engine.syncNow()
        assertNotNull("without the photo, it waits", computer.database.remoteRecipeDao().get(sha1))

        // A file with the same name and size, but other content, and the real one.
        val decoy = computer.devicePhoto("file:///C:/Others/IMG_5.jpg", "IMG_5 alterada".toByteArray(), "IMG_5.jpg")
        val real = computer.devicePhoto("file:///C:/Photos/IMG_5.jpg", bytes, "IMG_5.jpg")
        computer.engine.resolveWaiting(readFiles = false)
        assertTrue("the candidates light up the indicator", computer.database.assetDao().asset(DEVICE_LIBRARY_ID, "file:///C:/Others/IMG_5.jpg")!!.hasLocalRecipe)

        val store = computer.store()
        assertFalse(store.materializeFor(decoy))
        assertNull(computer.database.recipeDao().get(DEVICE_LIBRARY_ID, "file:///C:/Others/IMG_5.jpg"))
        assertFalse(computer.database.assetDao().asset(DEVICE_LIBRARY_ID, "file:///C:/Others/IMG_5.jpg")!!.hasLocalRecipe)
        assertTrue(computer.database.candidateRejectionDao().isRejected(sha1, DEVICE_LIBRARY_ID, "file:///C:/Others/IMG_5.jpg"))

        assertTrue(store.materializeFor(real))
        assertEquals(-1f, json.decodeFromString<EditRecipe>(computer.database.recipeDao().get(DEVICE_LIBRARY_ID, "file:///C:/Photos/IMG_5.jpg")!!.recipeJson).tone.exposure)
        assertNull(computer.database.remoteRecipeDao().get(sha1))
    }

    @Test
    fun `a recipe edited on both sides keeps the most recent and the other in both histories`() = runBlocking {
        phone.link("immich-phone", "remote-immich")
        computer.link("immich-computer", "remote-immich")
        val sha1 = sha1("montanha".toByteArray())
        val onPhone = phone.immichPhoto("immich-phone", "asset-6", sha1)
        val onComputer = computer.immichPhoto("immich-computer", "asset-6", sha1)
        phone.recipe(onPhone, sha1, exposure = 1f, editedAt = "2026-09-16T09:00:00Z")
        phone.engine.syncNow()
        computer.engine.syncNow()

        phone.recipe(onPhone, sha1, exposure = 2f, editedAt = "2026-09-16T10:00:00Z")
        computer.recipe(onComputer, sha1, exposure = 3f, editedAt = "2026-09-16T11:00:00Z")
        phone.engine.syncNow()
        computer.engine.syncNow()
        phone.engine.syncNow()

        for ((device, key) in listOf(phone to "immich-phone", computer to "immich-computer")) {
            val current = json.decodeFromString<EditRecipe>(device.database.recipeDao().get(key, "asset-6")!!.recipeJson)
            assertEquals(3f, current.tone.exposure)
            val history = device.database.recipeConflictDao().observe(key, "asset-6").first()
            assertEquals(1, history.size)
            assertEquals("Phone", history.single().deviceName)
            assertEquals(2f, json.decodeFromString<EditRecipe>(history.single().recipeJson).tone.exposure)
        }
    }

    @Test
    fun `nothing that goes up carries local keys or configuration`() = runBlocking {
        val sentinel = "SENTINELA-CHAVE-LOCAL"
        phone.link(sentinel, "remote-immich")
        val sha1 = sha1("sentinela".toByteArray())
        val photo = phone.immichPhoto(sentinel, "asset-7", sha1)
        phone.project("p1", "Projecto", photo, "2026-09-16T10:00:00Z")
        phone.database.compositionTemplateDao().upsert(
            CompositionTemplateEntity(GLOBAL_LIBRARY_ID, "t1", "Modelo", projectJson("t1", "Modelo", photo, sha1), "", "", SyncState().edited("2026-09-16T10:00:00Z")),
        )
        phone.database.brandKitDao().upsert(
            BrandKitEntity(GLOBAL_LIBRARY_ID, buildJsonObject { putJsonArray("logos") { addJsonObject { put("assetId", photo); put("checksum", "x"); put("fileName", "logo.png") } } }.toString(), "", SyncState().edited("2026-09-16T10:00:00Z")),
        )
        phone.database.savedRecipeDao().upsert(
            SavedRecipeEntity(GLOBAL_LIBRARY_ID, "s1", "Preset", "", recipeJson(photo, 1f), "", "", sync = SyncState().edited("2026-09-16T10:00:00Z")),
        )
        phone.recipe(photo, sha1, exposure = 1f, editedAt = "2026-09-16T10:00:00Z")
        phone.database.derivedAssetDao().upsert(DerivedAssetEntity(sentinel, "derived-7", "asset-7", "", SyncState().edited("2026-09-16T10:00:00Z")))

        assertTrue(phone.engine.syncNow())

        val encodedSentinel = Base64.getUrlEncoder().withoutPadding().encodeToString(sentinel.toByteArray())
        val everything = server.sent.joinToString("\n") { "${it.key} ${it.payload} ${it.hints}" }
        assertEquals(6, server.sent.map { it.key }.toSet().size)
        assertFalse(everything.contains(sentinel))
        assertFalse(everything.contains(encodedSentinel))
        assertFalse(Regex("imago:").containsMatchIn(everything))

        // A configuration field in a record does not go up: the run fails and nothing leaves.
        phone.database.compositionTemplateDao().upsert(
            CompositionTemplateEntity(GLOBAL_LIBRARY_ID, "t2", "Modelo", """{"apiKey":"CHAVE-SENTINELA"}""", "", "", SyncState().edited("2026-09-16T11:00:00Z")),
        )
        assertFalse(phone.engine.syncNow())
        assertFalse(server.sent.joinToString { it.payload.toString() }.contains("CHAVE-SENTINELA"))
    }

    @Test
    fun `an unlinked library leaves the project unsent, with a notice`() = runBlocking {
        val photo = phone.immichPhoto("unlinked-library", "asset-8", sha1("x".toByteArray()))
        phone.project("p1", "Projecto", photo, "2026-09-16T10:00:00Z")

        assertTrue(phone.engine.syncNow())

        assertTrue(server.rows(SyncEntity.PROJECT).isEmpty())
        assertTrue(phone.database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, "p1")!!.sync.dirty)
        assertNotNull(phone.engine.status.value.error)
    }

    // --- aparelhos e dados

    private class Device(val id: String, val name: String, server: FakeSyncServer) : AutoCloseable {
        val files = mutableMapOf<String, ByteArray>()
        val database: ImmichRoomDatabase = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val hashes = ContentHashRepository(database, { localId -> files[localId]?.inputStream() }, { error("no Immich") })
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SyncEngine(database, server, hashes, { id }, { name }, TestSyncTexts, scope, debounceMillis = 20)

        fun store(): RemoteRecipeStore {
            val media = MediaResolver(database, hashes)
            return RemoteRecipeStore(database, media, ReferenceTranslator(database, media))
        }

        suspend fun link(localKey: String, remoteLibraryId: String) =
            database.libraryLinkDao().upsert(LibraryLinkEntity(localKey, remoteLibraryId))

        suspend fun devicePhoto(localId: String, bytes: ByteArray, fileName: String): String {
            files[localId] = bytes
            database.assetDao().upsertAll(listOf(asset(DEVICE_LIBRARY_ID, localId, "local:${bytes.size}:1700000000", fileName, bytes.size.toLong())))
            return AssetReference(DEVICE_LIBRARY_ID, localId).encode()
        }

        suspend fun immichPhoto(libraryKey: String, assetId: String, sha1: String): String {
            database.assetDao().upsertAll(listOf(asset(libraryKey, assetId, MediaResolver.immichChecksumOf(sha1)!!, "$assetId.jpg", null)))
            return AssetReference(libraryKey, assetId).encode()
        }

        suspend fun project(id: String, name: String, photo: String, editedAt: String) {
            val checksum = database.assetDao().asset(AssetReference.parse(photo).libraryId, AssetReference.parse(photo).localId)?.checksum.orEmpty()
            database.compositionProjectDao().upsert(
                CompositionProjectEntity(GLOBAL_LIBRARY_ID, id, name, projectJson(id, name, photo, checksum), 1, "2026-09-01", editedAt, SyncState().edited(editedAt)),
            )
        }

        suspend fun editProject(id: String, name: String, editedAt: String) {
            val row = database.compositionProjectDao().get(GLOBAL_LIBRARY_ID, id)!!
            val json = row.projectJson.replace("\"name\":\"${row.name}\"", "\"name\":\"$name\"")
            database.compositionProjectDao().upsert(row.copy(name = name, projectJson = json, updatedAt = editedAt, sync = row.sync.edited(editedAt)))
        }

        suspend fun recipe(photo: String, sha1: String, exposure: Float, editedAt: String) {
            val ref = AssetReference.parse(photo)
            val previous = database.recipeDao().getAny(ref.libraryId, ref.localId)
            database.recipeDao().upsert(
                RecipeEntity(ref.libraryId, ref.localId, recipeJson(photo, exposure), editedAt, sha1, null,
                    (previous?.sync ?: SyncState()).edited(editedAt)),
            )
        }

        override fun close() {
            scope.cancel()
            database.close()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        fun asset(libraryKey: String, id: String, checksum: String, fileName: String, size: Long?) = AssetEntity(
            libraryKey = libraryKey, id = id, checksum = checksum, originalFileName = fileName,
            fileCreatedAt = "2026-08-12T10:15:00Z", localDateTime = "2026-08-12T11:15:00",
            width = 4000, height = 3000, isFavorite = false, isEdited = false, hasLocalRecipe = false,
            type = "IMAGE", sizeBytes = size,
        )

        fun recipeJson(photo: String, exposure: Float) = json.encodeToString(
            EditRecipe(assetId = photo, originalChecksum = "", createdAt = "2026-09-01", updatedAt = "2026-09-16", tone = Tone(exposure = exposure)),
        )

        fun projectJson(id: String, name: String, photo: String, checksum: String) = buildJsonObject {
            put("id", id)
            put("name", name)
            putJsonArray("elements") {
                addJsonObject {
                    put("elementType", "photo")
                    put("id", "foto")
                    putJsonObject("media") {
                        put("assetId", photo)
                        put("checksum", checksum)
                        put("fileName", "foto.jpg")
                    }
                    putJsonObject("recipe") {
                        putJsonObject("recipe") {
                            put("assetId", photo)
                            put("originalChecksum", checksum)
                        }
                    }
                }
            }
        }.toString()
    }
}
