package eu.studio742.imago.core.sync

import eu.studio742.imago.core.model.SyncTexts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.content

/**
 * The in-memory backend, shared by a test's devices: only what the engine uses, with the revision
 * rules of `sync_push` and the conflicting versions of `sync_record_conflict`.
 */
internal class FakeSyncServer : SyncBackend {
    data class Row(
        val key: String,
        val revision: Long,
        val seq: Long,
        val payload: JsonObject,
        val editedAt: String,
        val device: String?,
        val deletedAt: String?,
        val hints: JsonObject? = null,
    )

    data class Conflict(val entity: SyncEntity, val key: String, val payload: JsonObject, val deviceId: String?, val editedAt: String, val seq: Long)

    private val lock = Any()
    private val rows = mutableMapOf<Pair<SyncEntity, String>, Row>()
    private var seq = 0L
    val conflicts = mutableListOf<Conflict>()
    val pullCursors = mutableMapOf<SyncEntity, Long>()

    /** Everything that ever went up, for the privacy tests to look for what should not be there. */
    val sent = mutableListOf<OutgoingChange>()
    @Volatile var rejectNext = false
    @Volatile var beforePush: ((call: Int) -> Unit)? = null

    /** Accepted send calls (refused ones do not count). */
    @Volatile var pushCalls = 0
    private var pushAttempts = 0

    override val isAvailable = true
    override val session = MutableStateFlow<AccountSession?>(null)

    fun signIn() {
        session.value = AccountSession("user", null)
    }

    fun row(entity: SyncEntity, key: String) = synchronized(lock) { rows[entity to key] }

    fun rows(entity: SyncEntity) = synchronized(lock) { rows.filterKeys { it.first == entity }.values.toList() }

    /** A write made by another device, directly on the server. */
    fun store(
        entity: SyncEntity,
        key: String,
        payload: JsonObject,
        editedAt: String,
        device: String,
        deleted: Boolean = false,
        hints: JsonObject? = null,
    ): Row = synchronized(lock) {
        val previous = rows[entity to key]
        Row(key, (previous?.revision ?: 0) + 1, ++seq, payload, editedAt, device, editedAt.takeIf { deleted }, hints)
            .also { rows[entity to key] = it }
    }

    override suspend fun push(entity: SyncEntity, changes: List<OutgoingChange>): List<PushResult> {
        beforePush?.invoke(++pushAttempts)
        if (rejectNext) {
            rejectNext = false
            return changes.map { PushResult.Rejected(it.key, "invalid payload") }
        }
        pushCalls++
        return synchronized(lock) {
            sent += changes
            changes.map { change ->
                val current = rows[entity to change.key]
                if (current != null && current.revision != change.baseRevision) {
                    PushResult.Conflict(change.key, current.incoming())
                } else {
                    val row = store(entity, change.key, change.payload, change.editedAt, change.deviceId, change.deleted, change.hints)
                    PushResult.Applied(change.key, row.revision, row.seq)
                }
            }
        }
    }

    override suspend fun pull(entity: SyncEntity, cursor: Long, limit: Int): PullPage = synchronized(lock) {
        pullCursors[entity] = cursor
        val page = if (entity == SyncEntity.RECIPE_CONFLICT) {
            conflicts.filter { it.entity == SyncEntity.RECIPE && it.seq > cursor }.sortedBy { it.seq }.take(limit)
                .map { IncomingChange(it.key, 0, it.seq, it.payload, it.editedAt, it.deviceId, null) }
        } else {
            rows.filterKeys { it.first == entity }.values.filter { it.seq > cursor }.sortedBy { it.seq }.take(limit).map { it.incoming() }
        }
        PullPage(page, page.lastOrNull()?.seq ?: cursor)
    }

    override suspend fun recordConflict(entity: SyncEntity, key: String, revision: Long, payload: JsonObject, editedAt: String, deviceId: String?) {
        synchronized(lock) { conflicts += Conflict(entity, key, payload, deviceId, editedAt, ++seq) }
    }

    override fun changes(): Flow<SyncEntity> = emptyFlow()

    private fun Row.incoming() = IncomingChange(key, revision, seq, payload, editedAt, device, deletedAt, hints)

    override suspend fun signUp(email: String, password: String) = error("not used")
    override suspend fun signIn(email: String, password: String) = error("not used")
    override suspend fun sendPasswordReset(email: String) = error("not used")
    override suspend fun updatePassword(password: String) = error("not used")
    override suspend fun signOut() = error("not used")
    override fun handleAuthLink(uri: String) = error("not used")
    override suspend fun registerDevice(name: String, platform: String) = error("not used")
    override suspend fun touchDevice(deviceId: String, name: String?) = error("not used")
    override suspend fun devices() = listOf(
        RemoteDevice("device-a", "Phone", "android", "", ""),
        RemoteDevice("device-b", "Computer", "windows", "", ""),
    )
    override suspend fun removeDevice(deviceId: String) = error("not used")
    override suspend fun linkLibrary(provider: String, fingerprint: String, displayName: String, deviceId: String?) = error("not used")
}

/** The texts sync writes, as the interface would give them in English. */
internal object TestSyncTexts : SyncTexts {
    override suspend fun conflictCopyName(name: String, deviceName: String) = "$name (conflict · $deviceName)"
    override suspend fun unknownDevice() = "another device"
}
