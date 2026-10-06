package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.CompositionTemplate
import eu.studio742.imago.core.data.db.BrandKitEntity
import eu.studio742.imago.core.data.db.CompositionProjectEntity
import eu.studio742.imago.core.data.db.CompositionTemplateEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.SyncState

private val compositionJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "elementType"
}

/*
 * The three repositories follow the same sync rule: saving marks the record as waiting to be sent,
 * except when nothing changed — the composer saves on exit, and that is not an edit —, and deleting
 * a record that already reached the backend leaves a mark instead of removing it. Saving also asks
 * for the hash of the device photos in it, to recognise them on another device.
 */

@Singleton
class RoomCompositionRepository @Inject constructor(
    private val database: ImmichRoomDatabase,
    private val configuration: ConfigurationRepository,
    private val hashes: ContentHashRepository,
) : CompositionRepository {
    override fun observeAll(): Flow<List<CompositionProject>> =
        database.compositionProjectDao().observeAll(libraryKey()).map { rows -> rows.map(::decodeProject) }

    override suspend fun get(id: String): CompositionProject? =
        database.compositionProjectDao().get(libraryKey(), id)?.let(::decodeProject)

    override suspend fun save(project: CompositionProject) {
        val entity = project.toEntity(libraryKey())
        database.withTransaction {
            val dao = database.compositionProjectDao()
            val previous = dao.getAny(libraryKey(), project.id)
            if (previous != null && previous.sync.deletedAt == null && previous.copy(sync = SyncState()) == entity) return@withTransaction
            dao.upsert(entity.copy(sync = (previous?.sync ?: SyncState()).edited(Instant.now().toString())))
        }
        hashes.requestDeviceMedia(compositionJson.parseToJsonElement(entity.projectJson))
    }

    override suspend fun delete(id: String) = database.withTransaction {
        val dao = database.compositionProjectDao()
        val previous = dao.getAny(libraryKey(), id) ?: return@withTransaction
        if (previous.sync.remoteRevision == null) dao.delete(libraryKey(), id)
        else if (previous.sync.deletedAt == null) dao.upsert(previous.copy(sync = previous.sync.deleted(Instant.now().toString())))
    }

    override suspend fun duplicate(id: String, name: suspend (original: String) -> String): CompositionProject {
        val source = get(id) ?: throw UserMessageException(UserMessage.COMPOSITION_UNAVAILABLE)
        val now = Instant.now().toString()
        return source.copy(
            id = UUID.randomUUID().toString(),
            name = name(source.name),
            revision = 1,
            createdAt = now,
            updatedAt = now,
        ).also { save(it) }
    }

    private fun libraryKey() = eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
}

@Singleton
class RoomCompositionTemplateRepository @Inject constructor(
    private val database: ImmichRoomDatabase,
    private val configuration: ConfigurationRepository,
    private val hashes: ContentHashRepository,
) : CompositionTemplateRepository {
    override fun observeAll(): Flow<List<CompositionTemplate>> =
        database.compositionTemplateDao().observeAll(libraryKey()).map { rows ->
            rows.map { compositionJson.decodeFromString<CompositionTemplate>(it.templateJson) }
        }

    override suspend fun get(id: String): CompositionTemplate? = database.compositionTemplateDao()
        .get(libraryKey(), id)?.let { compositionJson.decodeFromString(it.templateJson) }

    override suspend fun save(template: CompositionTemplate) {
        val entity = CompositionTemplateEntity(
            libraryKey(), template.id, template.name, compositionJson.encodeToString(template),
            template.createdAt, template.updatedAt,
        )
        database.withTransaction {
            val dao = database.compositionTemplateDao()
            val previous = dao.getAny(libraryKey(), template.id)
            if (previous != null && previous.sync.deletedAt == null && previous.copy(sync = SyncState()) == entity) return@withTransaction
            dao.upsert(entity.copy(sync = (previous?.sync ?: SyncState()).edited(Instant.now().toString())))
        }
        hashes.requestDeviceMedia(compositionJson.parseToJsonElement(entity.templateJson))
    }

    override suspend fun delete(id: String) = database.withTransaction {
        val dao = database.compositionTemplateDao()
        val previous = dao.getAny(libraryKey(), id) ?: return@withTransaction
        if (previous.sync.remoteRevision == null) dao.delete(libraryKey(), id)
        else if (previous.sync.deletedAt == null) dao.upsert(previous.copy(sync = previous.sync.deleted(Instant.now().toString())))
    }

    private fun libraryKey() = eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
}

@Singleton
class RoomBrandKitRepository @Inject constructor(
    private val database: ImmichRoomDatabase,
    private val configuration: ConfigurationRepository,
    private val hashes: ContentHashRepository,
) : BrandKitRepository {
    override fun observe(): Flow<BrandKit?> = database.brandKitDao().observe(libraryKey()).map { row ->
        row?.let { compositionJson.decodeFromString<BrandKit>(it.kitJson) }
    }

    override suspend fun save(brandKit: BrandKit) {
        val entity = BrandKitEntity(libraryKey(), compositionJson.encodeToString(brandKit), brandKit.updatedAt)
        database.withTransaction {
            val dao = database.brandKitDao()
            val previous = dao.getAny(libraryKey())
            if (previous != null && previous.sync.deletedAt == null && previous.copy(sync = SyncState()) == entity) return@withTransaction
            dao.upsert(entity.copy(sync = (previous?.sync ?: SyncState()).edited(Instant.now().toString())))
        }
        hashes.requestDeviceMedia(compositionJson.parseToJsonElement(entity.kitJson))
    }

    private fun libraryKey() = eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID
}

private fun decodeProject(entity: CompositionProjectEntity): CompositionProject =
    compositionJson.decodeFromString(entity.projectJson)

private fun CompositionProject.toEntity(libraryKey: String) = CompositionProjectEntity(
    libraryKey, id, name, compositionJson.encodeToString(this), revision, createdAt, updatedAt,
)
