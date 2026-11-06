package eu.studio742.imago.core.data

import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompositionFormatRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Preferences(private val file: File) : SecurePreferences {
        private var values: Map<String, String> = if (file.isFile) Json.decodeFromString(file.readText()) else emptyMap()
        var refuse = false
        override fun getString(key: String) = values[key]
        override fun write(changes: Map<String, String?>): Boolean {
            if (refuse) return false
            val next = values.toMutableMap()
            changes.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
            file.writeText(Json.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), next))
            values = next
            return true
        }
    }
    private suspend fun failure(expected: UserMessage, action: suspend () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (error: UserMessageException) { assertEquals(expected, error.userMessage) }
    }

    @Test fun `create reopen rename and delete persist without changing other preferences`() = runBlocking {
        val file = temporary.root.resolve("preferences.json")
        val preferences = Preferences(file)
        preferences.write(mapOf("other-setting" to "retained"))
        val repository = LocalCompositionFormatRepository(preferences)
        val saved = repository.save("  Poster  Print  ", 1234, 2345)
        assertEquals("Poster Print", saved.name)
        val reopened = LocalCompositionFormatRepository(Preferences(file))
        assertEquals(listOf(saved), reopened.load())
        val renamed = reopened.save("Exhibition", saved.pixelWidth, saved.pixelHeight, saved.id)
        assertEquals(saved.id, renamed.id)
        assertEquals(saved.format, renamed.format)
        val restarted = LocalCompositionFormatRepository(Preferences(file))
        assertEquals(listOf(renamed), restarted.load())
        restarted.delete(saved.id)
        assertTrue(LocalCompositionFormatRepository(Preferences(file)).load().isEmpty())
        assertEquals("retained", Preferences(file).getString("other-setting"))
    }

    @Test fun `names and dimensions cannot be accidentally duplicated including Unicode equivalents`() = runBlocking {
        val repository = LocalCompositionFormatRepository(Preferences(temporary.root.resolve("preferences.json")))
        val saved = repository.save("Café Poster", 1234, 2345)
        failure(UserMessage.COMPOSITION_FORMAT_NAME_TAKEN) { repository.save(" CAFE\u0301   POSTER ", 1000, 1500) }
        failure(UserMessage.COMPOSITION_FORMAT_SIZE_EXISTS) { repository.save("Another", 1234, 2345) }
        assertEquals(saved, repository.save(saved.name, saved.pixelWidth, saved.pixelHeight, saved.id))
        assertEquals(listOf(saved), repository.load())
    }

    @Test fun `blank names missing entries and out of range dimensions cannot be saved`() = runBlocking {
        val repository = LocalCompositionFormatRepository(Preferences(temporary.root.resolve("preferences.json")))
        failure(UserMessage.COMPOSITION_FORMAT_NAME_REQUIRED) { repository.save(" \n ", 1080, 1920) }
        for ((w, h) in listOf(239 to 1000, 4097 to 1000, 1000 to 239, 1000 to 4097)) {
            failure(UserMessage.COMPOSITION_FORMAT_SIZE_INVALID) { repository.save("Invalid", w, h) }
        }
        failure(UserMessage.COMPOSITION_FORMAT_MISSING) { repository.save("Missing", 1080, 1920, "missing") }
        assertTrue(repository.load().isEmpty())
        assertEquals(240, repository.save("Smallest", 240, 240).pixelWidth)
        assertEquals(4096, repository.save("Largest", 4096, 4096).pixelHeight)
    }

    @Test fun `a refused save rename or delete leaves the previous catalogue intact`() = runBlocking {
        val file = temporary.root.resolve("preferences.json")
        val preferences = Preferences(file)
        val repository = LocalCompositionFormatRepository(preferences)
        val saved = repository.save("Poster", 1234, 2345)
        val encoded = file.readText()
        preferences.refuse = true
        failure(UserMessage.CONFIGURATION_NOT_SAVED) { repository.save("Other", 1200, 1600) }
        failure(UserMessage.CONFIGURATION_NOT_SAVED) { repository.save("Renamed", 1234, 2345, saved.id) }
        failure(UserMessage.CONFIGURATION_NOT_SAVED) { repository.delete(saved.id) }
        assertEquals(encoded, file.readText())
        assertEquals(listOf(saved), repository.load())
    }

    @Test fun `unreadable or future catalogues are reported and never overwritten`() = runBlocking {
        for (encoded in listOf("invalid", """{"version":2,"formats":[]}""")) {
            val file = temporary.newFolder().resolve("preferences.json")
            val preferences = Preferences(file)
            preferences.write(mapOf("composition_formats_v1" to encoded))
            val original = file.readText()
            val repository = LocalCompositionFormatRepository(preferences)
            failure(UserMessage.COMPOSITION_FORMATS_UNAVAILABLE) { repository.load() }
            failure(UserMessage.COMPOSITION_FORMATS_UNAVAILABLE) { repository.save("New", 1080, 1920) }
            failure(UserMessage.COMPOSITION_FORMATS_UNAVAILABLE) { repository.delete("missing") }
            assertEquals(original, file.readText())
        }
    }

    @Test fun `concurrent changes retain every saved size`() = runBlocking {
        val repository = LocalCompositionFormatRepository(Preferences(temporary.root.resolve("preferences.json")))
        (1..10).map { index -> async { repository.save("Size $index", 1000 + index, 1500 + index) } }.awaitAll()
        assertEquals(10, repository.load().size)
    }

    @Test fun `catalogue changes leave existing projects and templates unchanged`() = runBlocking {
        val repository = LocalCompositionFormatRepository(Preferences(temporary.root.resolve("preferences.json")))
        val saved = repository.save("Poster", 1234, 2345)
        val project = CompositionProject(id = "project", name = "Project", format = saved.format,
            pages = listOf(CompositionPage("page", 0)), createdAt = "", updatedAt = "")
        val template = CompositionTemplate("template", "Template", project, false, "", "")
        val before = Json.encodeToString(CompositionTemplate.serializer(), template)
        repository.save("Renamed", 2345, 1234, saved.id)
        repository.delete(saved.id)
        assertEquals(before, Json.encodeToString(CompositionTemplate.serializer(), template))
        assertEquals(1234, project.format.pixelWidth)
        assertEquals(2345, project.format.pixelHeight)
    }
}
