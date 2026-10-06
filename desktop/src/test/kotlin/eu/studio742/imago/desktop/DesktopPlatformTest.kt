package eu.studio742.imago.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.desktop.platform.AppPaths
import eu.studio742.imago.desktop.platform.ImagoProtocol
import eu.studio742.imago.desktop.platform.SingleInstance
import java.nio.file.Files

/**
 * What belongs only to this computer: one window at a time and the `imago://` protocol. The
 * screens, the data and sync are shared with Android and tested in their own modules.
 */
class DesktopPlatformTest {
    @Test fun aSecondOpeningHandsTheLinkToTheOpenWindow() {
        val paths = AppPaths(Files.createTempDirectory("imago-instance-test"))
        assertFalse("With no window open, this launch carries on", SingleInstance(paths).forward("imago://auth/callback?code=1"))
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        SingleInstance(paths).use { first ->
            first.listen { received.put(it ?: "<focus>") }
            assertTrue(SingleInstance(paths).forward("imago://auth/callback?code=1"))
            assertEquals("imago://auth/callback?code=1", received.poll(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(SingleInstance(paths).forward(null))
            assertEquals("<focus>", received.poll(2, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertFalse("Once the window is closed, there is no one to hand over to", SingleInstance(paths).forward(null))
    }

    @Test fun theRegisteredCommandStartsThisAppWithTheLink() {
        val paths = AppPaths(Files.createTempDirectory("imago-protocol-test"))
        val command = ImagoProtocol.launchCommand(paths)!!
        assertTrue(command.endsWith("\"%1\""))
        // Tests run in a java process: the development path, with the classpath in a file.
        assertTrue(command.contains("@\""))
        val arguments = Files.readString(paths.root.resolve("launch.args"))
        assertTrue(arguments.contains("eu.studio742.imago.desktop.MainKt"))
        assertEquals("imago://auth/recovery?code=2", ImagoProtocol.linkFrom(arrayOf("--x", "imago://auth/recovery?code=2")))
    }
}
