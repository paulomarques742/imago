package eu.studio742.imago.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import eu.studio742.imago.core.data.ContentHashRepository
import eu.studio742.imago.core.data.MediaHints
import eu.studio742.imago.core.data.MediaResolver
import eu.studio742.imago.core.data.ReferenceTranslator
import eu.studio742.imago.core.data.RemoteRecipeStore
import eu.studio742.imago.core.data.db.BrandKitEntity
import eu.studio742.imago.core.data.db.BuiltInRecipeMarkEntity
import eu.studio742.imago.core.data.db.CompositionProjectEntity
import eu.studio742.imago.core.data.db.CompositionTemplateEntity
import eu.studio742.imago.core.data.db.DerivedAssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.RecipeEntity
import eu.studio742.imago.core.data.db.SavedRecipeEntity
import eu.studio742.imago.core.data.db.SyncState
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
import eu.studio742.imago.core.model.SyncTexts
import java.time.Instant
import java.util.UUID

/** A local row ready to go up, already in the backend's shape. */
internal data class SyncRow(
    val key: String,
    val baseRevision: Long?,
    val payload: JsonObject,
    val editedAt: String,
    val deleted: Boolean,
    /** True while this version has not gone up. */
    val dirty: Boolean,
    val hints: JsonObject? = null,
    /**
     * Why it cannot go up now; null if it can. Empty is a block without a notice — a derived asset
     * from an unlinked library is not an error worth showing.
     */
    val blocked: String? = null,
    /** The photos that went up without a SHA-1; once they have it, the record goes up again. */
    val missingHashes: Set<String> = emptySet(),
)

/** What the pull needs to know about the local row, without translating it. */
internal data class LocalState(val dirty: Boolean, val baseRevision: Long?)

/** The devices' names, for conflicting copies. */
internal interface DeviceNames {
    val thisDevice: String
    suspend fun of(deviceId: String?): String
}

/**
 * What the engine needs to know about each table it syncs.
 *
 * The protocol is a single one — key, payload and state — and this is where each table says how it
 * is read, how it is saved and what happens to the version that loses a conflict.
 */
internal interface SyncedEntity {
    val entity: SyncEntity

    suspend fun pending(limit: Int): List<SyncRow>
    suspend fun pendingCount(): Int
    fun observePendingCount(): Flow<Int>

    /** The state of the local row for this key, deleted or not; null if it never existed here. */
    suspend fun local(key: String): LocalState?

    /**
     * The record went up. Clears the waiting-to-send mark **if** the row was not edited in the
     * meantime ([sentEditedAt] is the moment that was sent), and deletes for good what was a
     * deletion mark.
     */
    suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String)

    /**
     * Saves what came from the backend, already without a waiting-to-send mark. [resolvingConflict]
     * says this version won over a local one, and therefore overrides what was waiting to be sent.
     */
    suspend fun apply(change: IncomingChange, resolvingConflict: Boolean = false)

    /** Marks the record as waiting to be sent again without counting it as edited: it learnt what was missing. */
    suspend fun markDirty(key: String) = Unit

    /** Keeps the local version that lost here. `false` lets it go to the backend's history. */
    suspend fun keepLocalLoser(local: SyncRow, names: DeviceNames): Boolean = false

    /** Keeps the remote version that lost here. `false` lets it go to the backend's history. */
    suspend fun keepRemoteLoser(remote: IncomingChange, names: DeviceNames): Boolean = false
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun parseObject(raw: String): JsonObject =
    runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)?.contentOrNull == "true"
private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())

/**
 * Translates a local JSON to go up. Photos without a hash still go up and ask for it; once it is
 * ready, the record goes up again. An unlinked library blocks.
 */
private suspend fun ReferenceTranslator.outgoingOrBlocked(
    value: JsonElement,
    hashes: ContentHashRepository,
): Translated = try {
    val result = outgoing(value)
    result.missingHashes.forEach(hashes::request)
    Translated(result.payload, null, result.missingHashes)
} catch (error: ReferenceTranslator.UnlinkedLibraryException) {
    Translated(null, error.message ?: "There are photos from an unlinked library.", emptySet())
}

private data class Translated(val payload: JsonElement?, val blocked: String?, val missing: Set<String>)

/** The local state of a row just received: it is already the backend's version, and nothing is waiting to be sent. */
private fun IncomingChange.syncState() = SyncState(
    remoteRevision = revision,
    editedAt = editedAt,
    editedByDevice = editedByDevice,
    deletedAt = deletedAt,
    dirty = false,
)

/** Presets: `(user_id, id)` in the backend, global scope here. */
internal class SavedRecipeSync(
    private val database: ImmichRoomDatabase,
    private val translator: ReferenceTranslator,
    private val hashes: ContentHashRepository,
) : SyncedEntity {
    override val entity = SyncEntity.SAVED_RECIPE
    private val dao get() = database.savedRecipeDao()

    override suspend fun pending(limit: Int) = dao.pending(limit).map { rowOf(it) }
    override suspend fun pendingCount() = dao.pendingCount()
    override fun observePendingCount() = dao.observePendingCount()
    override suspend fun local(key: String) = dao.getAny(GLOBAL_LIBRARY_ID, key)?.let { LocalState(it.sync.dirty, it.sync.remoteRevision) }

    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) {
        val row = dao.getAny(GLOBAL_LIBRARY_ID, key) ?: return
        if (row.sync.editedAt != sentEditedAt) return
        if (row.sync.deletedAt != null) dao.delete(GLOBAL_LIBRARY_ID, key)
        else dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = revision, dirty = false)))
    }

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        if (change.deletedAt != null) return dao.delete(GLOBAL_LIBRARY_ID, change.key)
        val payload = change.payload
        dao.upsert(
            SavedRecipeEntity(
                libraryKey = GLOBAL_LIBRARY_ID,
                id = change.key,
                name = payload.string("name").orEmpty(),
                collection = payload.string("collection").orEmpty(),
                recipeJson = translator.incoming(payload.obj("recipe")).toString(),
                createdAt = payload.string("createdAt").orEmpty(),
                updatedAt = payload.string("updatedAt").orEmpty(),
                isFavorite = payload.boolean("isFavorite"),
                usedAt = payload.string("usedAt"),
                sync = change.syncState(),
            ),
        )
    }

    private suspend fun rowOf(entity: SavedRecipeEntity): SyncRow {
        // A preset does not depend on the photo it came from: if that photo is from an unlinked
        // library, it goes up without the reference instead of standing still.
        val recipe = parseObject(entity.recipeJson)
        val translated = translator.outgoingOrBlocked(recipe, hashes).payload
            ?: translator.outgoingOrBlocked(withoutReferences(recipe), hashes).payload
            ?: JsonObject(emptyMap())
        return SyncRow(
            key = entity.id,
            baseRevision = entity.sync.remoteRevision,
            payload = buildJsonObject {
                put("id", JsonPrimitive(entity.id))
                put("name", JsonPrimitive(entity.name))
                put("collection", JsonPrimitive(entity.collection))
                put("recipe", translated)
                put("createdAt", JsonPrimitive(entity.createdAt))
                put("updatedAt", JsonPrimitive(entity.updatedAt))
                put("isFavorite", JsonPrimitive(entity.isFavorite))
                entity.usedAt?.let { put("usedAt", JsonPrimitive(it)) }
            },
            editedAt = entity.sync.editedAt,
            deleted = entity.sync.deletedAt != null,
            dirty = entity.sync.dirty,
        )
    }

    private fun withoutReferences(value: JsonObject) = JsonObject(value.mapValues { (key, child) ->
        if (key.endsWith("ssetId") || key == "originalChecksum") JsonPrimitive("") else child
    })
}

/** The heart and last use on the app's presets: `(user_id, preset id)`. Nothing in them points at a photo. */
internal class BuiltInRecipeMarkSync(private val database: ImmichRoomDatabase) : SyncedEntity {
    override val entity = SyncEntity.BUILT_IN_RECIPE_MARK
    private val dao get() = database.builtInRecipeMarkDao()

    override suspend fun pending(limit: Int) = dao.pending(limit).map { entity ->
        SyncRow(
            key = entity.id,
            baseRevision = entity.sync.remoteRevision,
            payload = buildJsonObject {
                put("id", JsonPrimitive(entity.id))
                put("isFavorite", JsonPrimitive(entity.isFavorite))
                entity.usedAt?.let { put("usedAt", JsonPrimitive(it)) }
                put("updatedAt", JsonPrimitive(entity.updatedAt))
            },
            editedAt = entity.sync.editedAt,
            deleted = entity.sync.deletedAt != null,
            dirty = entity.sync.dirty,
        )
    }
    override suspend fun pendingCount() = dao.pendingCount()
    override fun observePendingCount() = dao.observePendingCount()
    override suspend fun local(key: String) = dao.getAny(key)?.let { LocalState(it.sync.dirty, it.sync.remoteRevision) }

    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) {
        val row = dao.getAny(key) ?: return
        if (row.sync.editedAt != sentEditedAt) return
        if (row.sync.deletedAt != null) dao.delete(key)
        else dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = revision, dirty = false)))
    }

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        if (change.deletedAt != null) return dao.delete(change.key)
        val payload = change.payload
        dao.upsert(
            BuiltInRecipeMarkEntity(
                id = change.key,
                isFavorite = payload.boolean("isFavorite"),
                usedAt = payload.string("usedAt"),
                updatedAt = payload.string("updatedAt").orEmpty(),
                sync = change.syncState(),
            ),
        )
    }
}

/** Composition templates: `(user_id, id)`. */
internal class TemplateSync(
    private val database: ImmichRoomDatabase,
    private val translator: ReferenceTranslator,
    private val hashes: ContentHashRepository,
) : SyncedEntity {
    override val entity = SyncEntity.TEMPLATE
    private val dao get() = database.compositionTemplateDao()

    override suspend fun pending(limit: Int) = dao.pending(limit).map { rowOf(it) }
    override suspend fun pendingCount() = dao.pendingCount()
    override fun observePendingCount() = dao.observePendingCount()
    override suspend fun local(key: String) = dao.getAny(GLOBAL_LIBRARY_ID, key)?.let { LocalState(it.sync.dirty, it.sync.remoteRevision) }

    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) {
        val row = dao.getAny(GLOBAL_LIBRARY_ID, key) ?: return
        if (row.sync.editedAt != sentEditedAt) return
        if (row.sync.deletedAt != null) dao.delete(GLOBAL_LIBRARY_ID, key)
        else dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = revision, dirty = false)))
    }

    override suspend fun markDirty(key: String) {
        val row = dao.getAny(GLOBAL_LIBRARY_ID, key) ?: return
        if (row.sync.deletedAt == null) dao.upsert(row.copy(sync = row.sync.copy(dirty = true)))
    }

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        if (change.deletedAt != null) return dao.delete(GLOBAL_LIBRARY_ID, change.key)
        val payload = change.payload
        dao.upsert(
            CompositionTemplateEntity(
                libraryKey = GLOBAL_LIBRARY_ID,
                id = change.key,
                name = payload.string("name").orEmpty(),
                templateJson = translator.incoming(payload.obj("template")).toString(),
                createdAt = payload.string("createdAt").orEmpty(),
                updatedAt = payload.string("updatedAt").orEmpty(),
                sync = change.syncState(),
            ),
        )
    }

    private suspend fun rowOf(entity: CompositionTemplateEntity): SyncRow {
        val (template, blocked, missing) = translator.outgoingOrBlocked(parseObject(entity.templateJson), hashes)
        return SyncRow(
            key = entity.id,
            baseRevision = entity.sync.remoteRevision,
            payload = buildJsonObject {
                put("id", JsonPrimitive(entity.id))
                put("name", JsonPrimitive(entity.name))
                put("template", template ?: JsonObject(emptyMap()))
                put("createdAt", JsonPrimitive(entity.createdAt))
                put("updatedAt", JsonPrimitive(entity.updatedAt))
            },
            editedAt = entity.sync.editedAt,
            deleted = entity.sync.deletedAt != null,
            dirty = entity.sync.dirty,
            blocked = blocked,
            missingHashes = missing,
        )
    }
}

/** Brand kit: a single one per account, with the fixed key the backend requires. */
internal class BrandKitSync(
    private val database: ImmichRoomDatabase,
    private val translator: ReferenceTranslator,
    private val hashes: ContentHashRepository,
) : SyncedEntity {
    override val entity = SyncEntity.BRAND_KIT
    private val dao get() = database.brandKitDao()

    override suspend fun pending(limit: Int) = dao.pending(limit).map { rowOf(it) }
    override suspend fun pendingCount() = dao.pendingCount()
    override fun observePendingCount() = dao.observePendingCount()
    override suspend fun local(key: String) = dao.getAny(GLOBAL_LIBRARY_ID)?.let { LocalState(it.sync.dirty, it.sync.remoteRevision) }

    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) {
        val row = dao.getAny(GLOBAL_LIBRARY_ID) ?: return
        if (row.sync.editedAt != sentEditedAt) return
        dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = revision, dirty = false)))
    }

    override suspend fun markDirty(key: String) {
        val row = dao.getAny(GLOBAL_LIBRARY_ID) ?: return
        if (row.sync.deletedAt == null) dao.upsert(row.copy(sync = row.sync.copy(dirty = true)))
    }

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        dao.upsert(
            BrandKitEntity(
                libraryKey = GLOBAL_LIBRARY_ID,
                kitJson = translator.incoming(change.payload.obj("kit")).toString(),
                updatedAt = change.payload.string("updatedAt").orEmpty(),
                sync = change.syncState(),
            ),
        )
    }

    private suspend fun rowOf(entity: BrandKitEntity): SyncRow {
        val (kit, blocked, missing) = translator.outgoingOrBlocked(parseObject(entity.kitJson), hashes)
        return SyncRow(
            key = BRAND_KIT_KEY,
            baseRevision = entity.sync.remoteRevision,
            payload = buildJsonObject {
                put("kit", kit ?: JsonObject(emptyMap()))
                put("updatedAt", JsonPrimitive(entity.updatedAt))
            },
            editedAt = entity.sync.editedAt,
            deleted = entity.sync.deletedAt != null,
            dirty = entity.sync.dirty,
            blocked = blocked,
            missingHashes = missing,
        )
    }

    companion object {
        /** The backend only accepts this key in the brand kit table. */
        const val BRAND_KIT_KEY = "brand-kit"
    }
}

/**
 * Composition projects. The version that loses a conflict does not only go to the history: it
 * becomes a new project, named by [SyncTexts.conflictCopyName] in the app language, which shows up in
 * the hub and goes up like any other.
 */
internal class ProjectSync(
    private val database: ImmichRoomDatabase,
    private val translator: ReferenceTranslator,
    private val hashes: ContentHashRepository,
    private val texts: SyncTexts,
    private val now: () -> String,
) : SyncedEntity {
    override val entity = SyncEntity.PROJECT
    private val dao get() = database.compositionProjectDao()

    override suspend fun pending(limit: Int) = dao.pending(limit).map { rowOf(it) }
    override suspend fun pendingCount() = dao.pendingCount()
    override fun observePendingCount() = dao.observePendingCount()
    override suspend fun local(key: String) = dao.getAny(GLOBAL_LIBRARY_ID, key)?.let { LocalState(it.sync.dirty, it.sync.remoteRevision) }

    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) {
        val row = dao.getAny(GLOBAL_LIBRARY_ID, key) ?: return
        if (row.sync.editedAt != sentEditedAt) return
        if (row.sync.deletedAt != null) dao.delete(GLOBAL_LIBRARY_ID, key)
        else dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = revision, dirty = false)))
    }

    override suspend fun markDirty(key: String) {
        val row = dao.getAny(GLOBAL_LIBRARY_ID, key) ?: return
        if (row.sync.deletedAt == null) dao.upsert(row.copy(sync = row.sync.copy(dirty = true)))
    }

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        if (change.deletedAt != null) return dao.delete(GLOBAL_LIBRARY_ID, change.key)
        dao.upsert(entityOf(change.key, change.payload, change.syncState()))
    }

    override suspend fun keepLocalLoser(local: SyncRow, names: DeviceNames): Boolean {
        val row = dao.getAny(GLOBAL_LIBRARY_ID, local.key) ?: return true
        // A deletion that loses has nothing to keep: the project goes on, in the winning version.
        if (row.sync.deletedAt != null) return true
        dao.upsert(copyOf(row.name, parseObject(row.projectJson), row.revision, row.createdAt, names.thisDevice))
        return true
    }

    override suspend fun keepRemoteLoser(remote: IncomingChange, names: DeviceNames): Boolean {
        if (remote.deletedAt != null) return true
        val received = entityOf(remote.key, remote.payload, SyncState())
        dao.upsert(copyOf(received.name, parseObject(received.projectJson), received.revision, received.createdAt, names.of(remote.editedByDevice)))
        return true
    }

    private suspend fun copyOf(name: String, project: JsonObject, revision: Long, createdAt: String, deviceName: String): CompositionProjectEntity {
        val id = UUID.randomUUID().toString()
        val copyName = texts.conflictCopyName(name, deviceName)
        val stamp = now()
        val json = JsonObject(project + mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(copyName), "updatedAt" to JsonPrimitive(stamp)))
        return CompositionProjectEntity(GLOBAL_LIBRARY_ID, id, copyName, json.toString(), revision, createdAt, stamp, SyncState().edited(stamp))
    }

    private suspend fun entityOf(key: String, payload: JsonObject, sync: SyncState) = CompositionProjectEntity(
        libraryKey = GLOBAL_LIBRARY_ID,
        id = key,
        name = payload.string("name").orEmpty(),
        projectJson = translator.incoming(payload.obj("project")).toString(),
        revision = (payload["revision"] as? JsonPrimitive)?.longOrNull ?: 1,
        createdAt = payload.string("createdAt").orEmpty(),
        updatedAt = payload.string("updatedAt").orEmpty(),
        sync = sync,
    )

    private suspend fun rowOf(entity: CompositionProjectEntity): SyncRow {
        val (project, blocked, missing) = translator.outgoingOrBlocked(parseObject(entity.projectJson), hashes)
        return SyncRow(
            key = entity.id,
            baseRevision = entity.sync.remoteRevision,
            payload = buildJsonObject {
                put("id", JsonPrimitive(entity.id))
                put("name", JsonPrimitive(entity.name))
                put("project", project ?: JsonObject(emptyMap()))
                put("revision", JsonPrimitive(entity.revision))
                put("createdAt", JsonPrimitive(entity.createdAt))
                put("updatedAt", JsonPrimitive(entity.updatedAt))
            },
            editedAt = entity.sync.editedAt,
            deleted = entity.sync.deletedAt != null,
            dirty = entity.sync.dirty,
            blocked = blocked,
            missingHashes = missing,
        )
    }
}

/**
 * Recipes, by content identity: the key is the SHA-1 of the original, and several local photos can
 * share one — the same photo in Immich and on the phone.
 */
internal class RecipeSync(
    private val database: ImmichRoomDatabase,
    private val translator: ReferenceTranslator,
    private val store: RemoteRecipeStore,
    private val media: MediaResolver,
) : SyncedEntity {
    override val entity = SyncEntity.RECIPE
    private val dao get() = database.recipeDao()

    /**
     * One key per content, the one of the most recent edit. The others with the same SHA-1 stay
     * waiting to be sent and lose on the next run, going to the history as a conflict.
     */
    override suspend fun pending(limit: Int): List<SyncRow> = dao.pending(limit)
        .groupBy { it.contentSha1!! }
        .map { (_, rows) -> rows.maxBy { moment(it.sync.editedAt) } }
        .map { rowOf(it) }

    override suspend fun pendingCount() = dao.pendingCount()
    override fun observePendingCount() = dao.observePendingCount()

    override suspend fun local(key: String): LocalState? {
        val rows = dao.bySha1(key).ifEmpty { return null }
        val chosen = rows.firstOrNull { it.sync.dirty } ?: rows.first()
        return LocalState(chosen.sync.dirty, chosen.sync.remoteRevision)
    }

    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) {
        for (row in dao.bySha1(key)) {
            if (row.sync.editedAt != sentEditedAt) continue
            if (row.sync.deletedAt != null) dao.delete(row.libraryKey, row.assetId)
            else dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = revision, dirty = false)))
        }
    }

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        if (resolvingConflict) {
            // It beat the most recent local edit of this content, and therefore the older ones too.
            for (row in dao.bySha1(change.key)) {
                if (row.sync.dirty) dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = change.revision, dirty = false)))
            }
        }
        store.apply(change.key, change.payload, change.syncState(), change.hints)
    }

    private suspend fun rowOf(entity: RecipeEntity): SyncRow {
        val sha1 = entity.contentSha1!!
        val row = SyncRow(
            key = sha1,
            baseRevision = entity.sync.remoteRevision,
            payload = JsonObject(emptyMap()),
            editedAt = entity.sync.editedAt,
            deleted = entity.sync.deletedAt != null,
            dirty = entity.sync.dirty,
            // Saved with the recipe; a recipe from before that reads them from the catalogue now.
            hints = entity.hintsJson?.let(::parseObject)?.takeIf { it.isNotEmpty() }
                ?: media.hints(AssetReference(entity.libraryKey, entity.assetId).encode())
                    ?.let { json.encodeToJsonElement(MediaHints.serializer(), it) as JsonObject },
        )
        return try {
            val payload = translator.outgoing(parseObject(entity.recipeJson)).payload as JsonObject
            // The recipe's checksum is the SHA-1 that is the key, even if the catalogue does not have it yet.
            row.copy(payload = JsonObject(payload + ("originalChecksum" to JsonPrimitive(sha1))))
        } catch (error: ReferenceTranslator.UnlinkedLibraryException) {
            row.copy(blocked = error.message.orEmpty())
        }
    }
}

/** The recipe versions that lost conflicts: only received, and they go into the history. */
internal class RecipeConflictSync(
    private val store: RemoteRecipeStore,
    private val names: DeviceNames,
) : SyncedEntity {
    override val entity = SyncEntity.RECIPE_CONFLICT

    override suspend fun pending(limit: Int) = emptyList<SyncRow>()
    override suspend fun pendingCount() = 0
    override fun observePendingCount(): Flow<Int> = flowOf(0)
    override suspend fun local(key: String): LocalState? = null
    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) = Unit

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        store.addConflict(change.key, change.payload, change.editedAt, names.of(change.editedByDevice))
    }
}

/** The derived assets sent to Immich: `<remote library>/<derived asset id>`. */
internal class DerivedAssetSync(private val database: ImmichRoomDatabase) : SyncedEntity {
    override val entity = SyncEntity.DERIVED_ASSET
    private val dao get() = database.derivedAssetDao()

    override suspend fun pending(limit: Int) = dao.pending(limit).map { rowOf(it) }
    override suspend fun pendingCount() = dao.pendingCount()
    override fun observePendingCount() = dao.observePendingCount()

    override suspend fun local(key: String): LocalState? {
        val (libraryKey, derivedId) = localKey(key) ?: return null
        return dao.getAny(libraryKey, derivedId)?.let { LocalState(it.sync.dirty, it.sync.remoteRevision) }
    }

    override suspend fun markPushed(key: String, revision: Long?, sentEditedAt: String) {
        val (libraryKey, derivedId) = localKey(key) ?: return
        val row = dao.getAny(libraryKey, derivedId) ?: return
        if (row.sync.editedAt != sentEditedAt) return
        if (row.sync.deletedAt != null) dao.delete(libraryKey, derivedId)
        else dao.upsert(row.copy(sync = row.sync.copy(remoteRevision = revision, dirty = false)))
    }

    override suspend fun apply(change: IncomingChange, resolvingConflict: Boolean) {
        // A library that is not linked here has nowhere to put them; the cursor goes back when it
        // is linked.
        val (libraryKey, derivedId) = localKey(change.key) ?: return
        if (change.deletedAt != null) return dao.delete(libraryKey, derivedId)
        dao.upsert(
            DerivedAssetEntity(
                libraryKey = libraryKey,
                derivedAssetId = derivedId,
                originalAssetId = change.payload.string("originalAssetId").orEmpty(),
                createdAt = change.payload.string("createdAt").orEmpty(),
                sync = change.syncState(),
            ),
        )
    }

    private suspend fun localKey(key: String): Pair<String, String>? {
        val remoteLibrary = key.substringBefore('/', "").ifEmpty { return null }
        val link = database.libraryLinkDao().byRemote(remoteLibrary) ?: return null
        return link.localKey to key.substringAfter('/')
    }

    private suspend fun rowOf(entity: DerivedAssetEntity): SyncRow {
        val remoteLibrary = database.libraryLinkDao().get(entity.libraryKey)?.remoteLibraryId
        return SyncRow(
            key = "${remoteLibrary.orEmpty()}/${entity.derivedAssetId}",
            baseRevision = entity.sync.remoteRevision,
            payload = buildJsonObject {
                put("libraryId", JsonPrimitive(remoteLibrary.orEmpty()))
                put("derivedAssetId", JsonPrimitive(entity.derivedAssetId))
                put("originalAssetId", JsonPrimitive(entity.originalAssetId))
                put("createdAt", JsonPrimitive(entity.createdAt))
            },
            editedAt = entity.sync.editedAt,
            deleted = entity.sync.deletedAt != null,
            dirty = entity.sync.dirty,
            blocked = if (remoteLibrary == null) "" else null,
        )
    }
}

/** The moment of an edit, to compare the local clock with the backend's. */
internal fun moment(value: String): Instant = MediaResolver.parseMoment(value) ?: Instant.EPOCH
