package eu.studio742.imago.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import eu.studio742.imago.core.composition.CompositionProject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompositionExportSnapshots @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "composition-export-jobs")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "elementType" }
    suspend fun save(project: CompositionProject): String = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        file(id).writeText(json.encodeToString(project))
        id
    }
    suspend fun read(id: String): CompositionProject = withContext(Dispatchers.IO) {
        json.decodeFromString(file(id).readText())
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { file(id).delete(); Unit }
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid export." }
        return File(directory, "$id.json")
    }
}
