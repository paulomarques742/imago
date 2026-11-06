package eu.studio742.imago.core.data

import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.requireUser
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface CompositionFormatRepository {
    suspend fun load(): List<SavedCompositionFormat>
    suspend fun save(name: String, width: Int, height: Int, id: String? = null): SavedCompositionFormat
    suspend fun delete(id: String)
}

/** Device-local preferences deliberately keep the personal catalogue outside project synchronisation. */
class LocalCompositionFormatRepository(private val preferences: SecurePreferences) : CompositionFormatRepository {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class Document(val version: Int = 1, val formats: List<SavedCompositionFormat>)

    override suspend fun load(): List<SavedCompositionFormat> = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    override suspend fun save(name: String, width: Int, height: Int, id: String?): SavedCompositionFormat = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = cleanName(name)
            requireUser(clean.isNotEmpty(), UserMessage.COMPOSITION_FORMAT_NAME_REQUIRED)
            requireUser(width in MIN_PAGE_SIDE..MAX_PAGE_SIDE && height in MIN_PAGE_SIDE..MAX_PAGE_SIDE,
                UserMessage.COMPOSITION_FORMAT_SIZE_INVALID, MIN_PAGE_SIDE, MAX_PAGE_SIDE)
            val current = read()
            requireUser(id == null || current.any { it.id == id }, UserMessage.COMPOSITION_FORMAT_MISSING)
            requireUser(current.none { it.id != id && nameKey(it.name) == nameKey(clean) }, UserMessage.COMPOSITION_FORMAT_NAME_TAKEN)
            val duplicate = current.firstOrNull { it.id != id && it.pixelWidth == width && it.pixelHeight == height }
            requireUser(duplicate == null, UserMessage.COMPOSITION_FORMAT_SIZE_EXISTS, duplicate?.name.orEmpty())
            val saved = SavedCompositionFormat(id ?: UUID.randomUUID().toString(), clean, width, height)
            write(current.filterNot { it.id == saved.id } + saved)
            saved
        }
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read()
            if (current.any { it.id == id }) write(current.filterNot { it.id == id })
        }
    }

    private fun read(): List<SavedCompositionFormat> = try {
        val encoded = preferences.getString(KEY)
        if (encoded == null) emptyList() else {
            val document = json.decodeFromString<Document>(encoded)
            require(document.version == 1)
            val formats = document.formats
            require(formats.distinctBy { it.id }.size == formats.size)
            require(formats.distinctBy { nameKey(it.name) }.size == formats.size)
            require(formats.distinctBy { it.pixelWidth to it.pixelHeight }.size == formats.size)
            formats.sortedBy { nameKey(it.name) }
        }
    } catch (_: Exception) { throw UserMessageException(UserMessage.COMPOSITION_FORMATS_UNAVAILABLE) }

    private fun write(formats: List<SavedCompositionFormat>) {
        val encoded = json.encodeToString(Document.serializer(), Document(formats = formats))
        requireUser(preferences.write(mapOf(KEY to encoded)), UserMessage.CONFIGURATION_NOT_SAVED)
    }
    private fun cleanName(name: String) = Normalizer.normalize(name.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
    private fun nameKey(name: String) = cleanName(name).lowercase(Locale.ROOT)
    private companion object { const val KEY = "composition_formats_v1" }
}
