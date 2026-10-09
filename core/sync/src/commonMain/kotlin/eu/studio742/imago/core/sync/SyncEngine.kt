package eu.studio742.imago.core.sync

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import eu.studio742.imago.core.data.AccountLocalData
import eu.studio742.imago.core.data.ContentHashRepository
import eu.studio742.imago.core.data.MediaResolver
import eu.studio742.imago.core.data.ReferenceTranslator
import eu.studio742.imago.core.data.RemoteRecipeStore
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.SyncCursorEntity
import eu.studio742.imago.core.data.withTransaction
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
import eu.studio742.imago.core.model.SyncTexts
import java.time.Instant

/** What Settings shows about sync. */
data class SyncStatus(
    val running: Boolean = false,
    /** Records waiting to be sent; zero with everything delivered. */
    val pending: Int = 0,
    val lastSyncedAt: String? = null,
    /** The last failure, by the sentence the interface gives it; null once the next run went well. */
    val error: UserMessage? = null,
    /** Whether there is a signed-in account: without one the rest is not shown. */
    val signedIn: Boolean = false,
    /** The last failure was a lack of connection: what is waiting to be sent waits for the network. */
    val offline: Boolean = false,
)

/**
 * The sync engine: sends what is waiting to be sent and receives what the other devices changed.
 *
 * It runs in both apps — the same code on the phone and on the computer. Without an account it does
 * nothing, and without a network it fails silently: what was left unsent stays marked and goes next
 * time.
 */
@OptIn(FlowPreview::class)
class SyncEngine(
    private val database: ImmichRoomDatabase,
    private val backend: SyncBackend,
    private val hashes: ContentHashRepository,
    /** The device registered in this account, or null while there is none: without it nothing goes up. */
    private val deviceId: () -> String?,
    /** This device's name, for conflicting copies. */
    private val deviceName: () -> String,
    /** The names sync writes into the data, in the app language. */
    private val texts: SyncTexts,
    private val scope: CoroutineScope,
    private val now: () -> String = { Instant.now().toString() },
    private val debounceMillis: Long = DEBOUNCE_MS,
    /** Milliseconds, for how long ago the last run went well. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val media = MediaResolver(database, hashes)
    private val translator = ReferenceTranslator(database, media)
    private val recipes = RemoteRecipeStore(database, media, translator)
    private val localData = AccountLocalData(database)

    /** The names of the other devices, requested once per run and only if there are conflicts. */
    private val names = object : DeviceNames {
        private var known: Map<String, String>? = null
        override val thisDevice: String get() = deviceName()
        override suspend fun of(deviceId: String?): String {
            if (deviceId != null && deviceId == this@SyncEngine.deviceId()) return thisDevice
            val map = known ?: runCatching { backend.devices().associate { it.id to it.name } }.getOrDefault(emptyMap()).also { known = it }
            return deviceId?.let(map::get) ?: texts.unknownDevice()
        }
        fun forget() { known = null }
    }

    // The order matters: recipes go up before the derived assets and projects that use them, and
    // recipe conflicts arrive after the recipes they belong to.
    private val entities: List<SyncedEntity> = listOf(
        SavedRecipeSync(database, translator, hashes),
        TemplateSync(database, translator, hashes),
        BrandKitSync(database, translator, hashes),
        RecipeSync(database, translator, recipes, media),
        RecipeConflictSync(recipes, names),
        DerivedAssetSync(database),
        ProjectSync(database, translator, hashes, texts, now),
        // Last: it depends on nothing, and a backend that does not know it yet only fails this one.
        BuiltInRecipeMarkSync(database),
    )
    private val mutableStatus = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    private val running = Mutex()
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The runs still to do. Several triggers together are one run. */
    private val passes = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    /**
     * Wires the triggers: signing in, a local save, a signal from another device and the 12-hour
     * safety net. Without a configured backend it wires nothing.
     */
    fun start() {
        if (started || !backend.isAvailable) return
        started = true
        scope.launch {
            // The only place that runs passes. Triggers only ask, and never cancel one halfway:
            // cancelling between the send and clearing the mark made the device disagree with itself.
            var retryIn: Long? = null
            while (true) {
                val wait = retryIn
                if (wait == null) passes.receive() else withTimeoutOrNull(wait) { passes.receive() }
                val failed = !syncNow() && mutableStatus.value.error != null && backend.session.value != null
                // Offline it tries again, further and further apart; a new trigger brings it forward.
                retryIn = if (failed) ((wait ?: (RETRY_FIRST_MS / 2)) * 2).coerceAtMost(RETRY_MAX_MS) else null
            }
        }
        scope.launch {
            backend.session.collectLatest { session ->
                mutableStatus.update { if (session == null) SyncStatus(pending = it.pending) else it.copy(signedIn = true) }
                if (session == null) return@collectLatest
                // The device registers right after the session; until then there is nothing to sign with.
                while (deviceId() == null) delay(DEVICE_WAIT_MS)
                running.withLock { localData.markUnsentForUpload() }
                requestMissingHashes()
                // Opening the app is not a reason on its own: what changed elsewhere since a run a few
                // minutes ago would have come live, and what changed here is pending and goes anyway.
                if (isStale()) passes.trySend(Unit)
                while (true) {
                    delay(SAFETY_NET_MS)
                    passes.trySend(Unit)
                }
            }
        }
        scope.launch {
            // A local save leaves the row waiting to be sent; that is the request — repositories do
            // not need to know sync exists. What arrives from the backend is already clean.
            // No filtering of repeats: editing a row already waiting to be sent is a request too.
            combine(entities.map { it.observePendingCount() }) { counts -> counts.sum() }
                .collect { pending ->
                    if (mutableStatus.value.pending != pending) mutableStatus.update { it.copy(pending = pending) }
                    if (pending > 0) requestSync()
                }
        }
        scope.launch {
            // A request does not sync right away: it waits so that saving five times in a row is one send.
            requests.collectLatest {
                delay(debounceMillis)
                passes.trySend(Unit)
            }
        }
        scope.launch {
            val handled = entities.map { it.entity }.toSet() + SyncEntity.RECIPE_CONFLICT
            // Another device changed something: there is nothing to wait for.
            backend.changes().filter { it in handled }.collect { passes.trySend(Unit) }
        }
        scope.launch {
            // A library linked just now may be the one for derived assets that already came, and for
            // references that were left unresolved.
            database.libraryLinkDao().observeAll().map { links -> links.map { it.remoteLibraryId }.toSet() }
                .distinctUntilChanged().drop(1).collect {
                    database.syncCursorDao().set(SyncCursorEntity(SyncEntity.DERIVED_ASSET.wireName, 0))
                    resolveWaiting(readFiles = true)
                    passes.trySend(Unit)
                }
        }
        scope.launch {
            // The catalogue changed: new photos may be the ones another device referred to.
            database.invalidationTracker.createFlow("assets", emitInitialState = false)
                .debounce(CATALOG_SETTLE_MS)
                .collect { runCatching { resolveWaiting(readFiles = false) } }
        }
        scope.launch {
            // A hash became ready after the record went up without it: it goes up again, complete.
            hashes.computed.collect { assetId ->
                running.withLock {
                    database.compositionProjectDao().markDirtyReferencing(assetId)
                    database.compositionTemplateDao().markDirtyReferencing(assetId)
                    database.brandKitDao().markDirtyReferencing(assetId)
                }
            }
        }
    }

    /** Asks for a run; it follows after the pause, and several requests in a row are a single one. */
    fun requestSync() {
        requests.tryEmit(Unit)
    }

    /** Asks for a run now, without the pause: the network returned. */
    fun syncSoon() {
        passes.trySend(Unit)
    }

    /**
     * Asks for a run now unless one went well less than [RECENT_MS] ago and nothing is waiting to go
     * up: the app came back to the foreground. Opening and closing it again and again is not news;
     * while it is open, another device's changes arrive live, and "Sync now" always runs.
     */
    fun syncIfStale() {
        scope.launch { if (isStale()) passes.trySend(Unit) }
    }

    /** Whether there is something to send, or the last good run is old enough to look again. */
    internal suspend fun isStale(): Boolean {
        if (pendingCount() > 0) return true
        val last = database.syncCursorDao().get(LAST_RUN_KEY) ?: return true
        return clock() - last >= RECENT_MS
    }

    /**
     * A full run: sends what is waiting to be sent and receives the rest. One at a time — requests
     * that arrive halfway are served by the next run.
     */
    suspend fun syncNow(): Boolean = running.withLock {
        val deviceId = deviceId() ?: return@withLock false
        if (backend.session.value == null) return@withLock false
        mutableStatus.update { it.copy(running = true, error = null, signedIn = true, offline = false) }
        names.forget()
        return@withLock try {
            var received = false
            for (entity in entities) {
                push(entity, deviceId)
                received = pull(entity) || received
            }
            if (received) resolveWaiting(readFiles = true)
            database.syncCursorDao().set(SyncCursorEntity(LAST_RUN_KEY, clock()))
            // The error was cleared at the start: if one is left, it was a refusal or a block in this run.
            mutableStatus.update { it.copy(running = false, pending = pendingCount(), lastSyncedAt = now()) }
            true
        } catch (cancel: CancellationException) {
            mutableStatus.update { it.copy(running = false) }
            throw cancel
        } catch (error: Exception) {
            val offline = (error as? AccountException)?.reason == AccountException.Reason.OFFLINE ||
                generateSequence<Throwable>(error) { it.cause }.any { it is java.io.IOException }
            mutableStatus.update {
                it.copy(running = false, pending = pendingCount(), error = (error as? UserMessageException)?.userMessage ?: UserMessage.SYNC_FAILED, offline = offline)
            }
            false
        }
    }

    private suspend fun pendingCount(): Int = entities.sumOf { it.pendingCount() }

    /** Batches of 50, and resolving a conflict is up to whoever found it. */
    private suspend fun push(entity: SyncedEntity, deviceId: String) {
        // Each key is handled once per run: one left unsent — because it was refused, blocked, or
        // because the conflict did not resolve — goes in the next run.
        val handled = mutableSetOf<String>()
        while (true) {
            val rows = entity.pending(BATCH + handled.size).filterNot { it.key in handled }.take(BATCH)
            if (rows.isEmpty()) return
            handled += rows.map { it.key }
            val (blocked, ready) = rows.partition { it.blocked != null }
            blocked.mapNotNull { it.blocked?.takeIf(String::isNotEmpty) }.firstOrNull()?.let {
                mutableStatus.update { it.copy(error = UserMessage.LIBRARY_NOT_LINKED) }
            }
            if (ready.isEmpty()) continue
            val results = backend.push(entity.entity, ready.map { it.outgoing(deviceId) })
            val byKey = ready.associateBy { it.key }
            for (result in results) {
                val sent = byKey[result.key] ?: continue
                when (result) {
                    is PushResult.Applied -> {
                        entity.markPushed(result.key, result.revision, sent.editedAt)
                        // The missing hash may have become ready while this was going up: without this
                        // check, the "ready" notice arrived first and was lost.
                        if (sent.missingHashes.any { media.cachedSha1(it) != null }) entity.markDirty(result.key)
                    }
                    is PushResult.Rejected -> {
                        // Not retried: an invalid change retried would be an endless loop.
                        entity.markPushed(result.key, sent.baseRevision, sent.editedAt)
                        mutableStatus.update { it.copy(error = UserMessage.SYNC_CHANGE_REJECTED) }
                    }
                    is PushResult.Conflict -> resolve(entity, sent, result.remote, deviceId)
                }
            }
        }
    }

    /**
     * The most recent edit wins; a tie is broken by the larger device. The losing version is never
     * discarded: it stays in the backend's history, or — for projects — in a copy.
     */
    private suspend fun resolve(entity: SyncedEntity, local: SyncRow, remote: IncomingChange, deviceId: String) {
        val localMoment = moment(local.editedAt)
        val remoteMoment = moment(remote.editedAt)
        val localWins = when {
            localMoment > remoteMoment -> true
            localMoment < remoteMoment -> false
            else -> deviceId > (remote.editedByDevice ?: "")
        }
        if (localWins) {
            val results = backend.push(entity.entity, listOf(local.outgoing(deviceId).copy(baseRevision = remote.revision)))
            val applied = results.firstOrNull() as? PushResult.Applied ?: return
            entity.markPushed(local.key, applied.revision, local.editedAt)
            if (!entity.keepRemoteLoser(remote, names)) {
                runCatching {
                    backend.recordConflict(entity.entity, remote.key, remote.revision, remote.payload, remote.editedAt, remote.editedByDevice)
                }
            }
        } else {
            // The local version loses: it is kept before being replaced.
            if (!entity.keepLocalLoser(local, names)) {
                runCatching { backend.recordConflict(entity.entity, local.key, remote.revision, local.payload, local.editedAt, deviceId) }
            }
            database.withTransaction { entity.apply(remote, resolvingConflict = true) }
        }
    }

    /** Pages by `seq`, and the cursor moves in the same transaction that saves the page. */
    private suspend fun pull(entity: SyncedEntity): Boolean {
        var received = false
        while (true) {
            val cursor = database.syncCursorDao().get(entity.entity.wireName) ?: 0L
            val page = backend.pull(entity.entity, cursor)
            if (page.rows.isEmpty()) return received
            received = true
            database.withTransaction {
                for (change in page.rows) {
                    val local = entity.local(change.key)
                    // A row waiting to be sent with another base is resolved on the next send;
                    // writing over it would lose the edit that has not gone up yet.
                    val keepLocal = local != null && local.dirty && local.baseRevision != change.revision
                    if (!keepLocal) entity.apply(change)
                }
                database.syncCursorDao().set(SyncCursorEntity(entity.entity.wireName, page.cursor))
            }
            if (page.rows.size < PULL_LIMIT) return received
        }
    }

    /**
     * Retries what was left unresolved — the photos of projects, templates and the brand kit, and
     * the recipes waiting for a photo. Resolving is not editing: nothing is left to send.
     */
    internal suspend fun resolveWaiting(readFiles: Boolean) {
        val marker = ReferenceTranslator.REMOTE_MARKER
        for (row in database.compositionProjectDao().withRemoteMedia(marker)) {
            translator.resolveStored(parseObject(row.projectJson), readFiles)?.let {
                database.compositionProjectDao().replaceJson(row.libraryKey, row.id, it.toString())
            }
        }
        for (row in database.compositionTemplateDao().withRemoteMedia(marker)) {
            translator.resolveStored(parseObject(row.templateJson), readFiles)?.let {
                database.compositionTemplateDao().replaceJson(row.libraryKey, row.id, it.toString())
            }
        }
        for (row in database.brandKitDao().withRemoteMedia(marker)) {
            translator.resolveStored(parseObject(row.kitJson), readFiles)?.let {
                database.brandKitDao().replaceJson(GLOBAL_LIBRARY_ID, it.toString())
            }
        }
        recipes.matchWaiting()
    }

    /**
     * Recipes that do not have a SHA-1 yet do not go up; it is computed in the background, and each
     * one follows when its own is ready.
     */
    private suspend fun requestMissingHashes() {
        for (recipe in database.recipeDao().withoutSha1()) {
            val assetId = AssetReference(recipe.libraryKey, recipe.assetId).encode()
            // A hash already known — the Immich checksum in the catalogue — does not go through the
            // computation that saves it in the recipe; it is saved here.
            val known = media.cachedSha1(assetId)
            if (known != null) database.recipeDao().setContentSha1(recipe.libraryKey, recipe.assetId, known)
            else hashes.request(assetId)
        }
    }

    private fun SyncRow.outgoing(deviceId: String) = OutgoingChange(
        key = key,
        baseRevision = baseRevision,
        payload = payload,
        editedAt = editedAt,
        deviceId = deviceId,
        deleted = deleted,
        hints = hints,
    )

    private companion object {
        const val BATCH = 50
        const val PULL_LIMIT = 500
        const val DEBOUNCE_MS = 5_000L
        const val SAFETY_NET_MS = 12L * 60 * 60 * 1000
        /** A run younger than this makes coming back to the app run nothing. */
        const val RECENT_MS = 15L * 60 * 1000
        /**
         * When the last run went well, kept with the cursors: it belongs to the account the same way,
         * and leaves with them. Not an entity's name, so no pull ever reads it as one.
         */
        const val LAST_RUN_KEY = "@last-run"
        const val DEVICE_WAIT_MS = 1_000L
        const val RETRY_FIRST_MS = 30_000L
        const val RETRY_MAX_MS = 15L * 60 * 1000
        const val CATALOG_SETTLE_MS = 5_000L
    }
}
