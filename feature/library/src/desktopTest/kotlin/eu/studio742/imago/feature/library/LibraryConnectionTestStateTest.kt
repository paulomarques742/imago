package eu.studio742.imago.feature.library

import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DesktopDataGraph
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.LibrarySource
import eu.studio742.imago.core.model.ServerVersion
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryConnectionTestStateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resultsBelongToTheirServerAndNeverReplaceThePageMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = DesktopDataGraph(temporary.newFolder().toPath())
        try {
            val answer = CompletableDeferred<ServerVersion>()
            val configuration = object : ConfigurationRepository by graph.configuration {
                override suspend fun testLibrary(id: String): ServerVersion {
                    if (id == "first") return answer.await()
                    throw IOException("Test failure")
                }
                override suspend fun missingOptionalPermissions(connection: ImmichConnection) = emptyList<String>()
                override fun source(id: String) = LibrarySource(id, id, listOf("https://test.invalid"), apiKey = "test-key")
            }
            val model = LibrarySettingsViewModel(configuration, graph.folders, graph.library)
            val original = uiText(Res.string.library_saved)
            model.message.value = original
            model.test("first")
            runCurrent()
            assertTrue(model.connectionTests.value.getValue("first").running)
            assertTrue(model.busy.value)
            assertNull(model.connectionTests.value["second"])
            answer.complete(ServerVersion(3, 3, 5))
            runCurrent()
            assertEquals(uiText(Res.string.library_connection_ok, ServerVersion(3, 3, 5)), model.connectionTests.value.getValue("first").result)
            assertFalse(model.busy.value)
            model.test("second")
            runCurrent()
            assertTrue(model.connectionTests.value.getValue("second").failed)
            assertFalse(model.connectionTests.value.getValue("first").failed)
            assertEquals(original, model.message.value)
        } finally { graph.close(); Dispatchers.resetMain() }
    }

    @Test fun retryReplacesOnlyItsOwnPreviousResultAndKeepsLimitedPermissionNotice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = DesktopDataGraph(temporary.newFolder().toPath())
        try {
            var fail = true
            val answer = CompletableDeferred<ServerVersion>()
            val configuration = object : ConfigurationRepository by graph.configuration {
                override suspend fun testLibrary(id: String): ServerVersion {
                    if (fail) throw IOException("Test failure")
                    return answer.await()
                }
                override suspend fun missingOptionalPermissions(connection: ImmichConnection) = listOf("album.read")
                override fun source(id: String) = LibrarySource(id, id, listOf("https://test.invalid"), apiKey = "test-key")
            }
            val model = LibrarySettingsViewModel(configuration, graph.folders, graph.library)
            model.test("server")
            runCurrent()
            assertTrue(model.connectionTests.value.getValue("server").failed)
            fail = false
            model.test("server")
            runCurrent()
            assertTrue(model.connectionTests.value.getValue("server").running)
            assertNull(model.connectionTests.value.getValue("server").result)
            answer.complete(ServerVersion(3, 3, 5))
            runCurrent()
            val result = model.connectionTests.value.getValue("server")
            assertFalse(result.failed)
            assertEquals(uiText(Res.string.library_connection_ok_limited, ServerVersion(3, 3, 5), "album.read"), result.result)
        } finally { graph.close(); Dispatchers.resetMain() }
    }
}
