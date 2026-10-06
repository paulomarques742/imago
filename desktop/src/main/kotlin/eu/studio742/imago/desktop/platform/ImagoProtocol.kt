package eu.studio742.imago.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The `imago://` protocol on Windows: account links (email confirmation, password recovery) open
 * this app, as they open the app on Android.
 *
 * It is registered for the current user (HKCU), without asking for administrator rights, and
 * points to the running executable. In the installed app that is IMAGO.exe; in development it is
 * Gradle's `java`, and the classpath goes in an argument file because it does not fit on a
 * command line.
 */
object ImagoProtocol {
    private const val SCHEME = "imago"
    private const val MAIN_CLASS = "eu.studio742.imago.desktop.MainKt"
    private const val KEY = "Software\\Classes\\$SCHEME"
    private const val COMMAND_KEY = "$KEY\\shell\\open\\command"

    /** The account link passed on the command line, if the app was opened by one. */
    fun linkFrom(args: Array<String>): String? = args.firstOrNull { it.startsWith("$SCHEME://") }

    /** Registers (or updates) the protocol. Only writes to the registry when the command changed. */
    fun register(paths: AppPaths): Result<Unit> = runCatching {
        val command = launchCommand(paths) ?: return@runCatching
        val hive = WinReg.HKEY_CURRENT_USER
        if (Advapi32Util.registryKeyExists(hive, COMMAND_KEY) &&
            Advapi32Util.registryGetStringValue(hive, COMMAND_KEY, "") == command) return@runCatching
        Advapi32Util.registryCreateKey(hive, KEY)
        Advapi32Util.registrySetStringValue(hive, KEY, "", "URL:IMAGO")
        Advapi32Util.registrySetStringValue(hive, KEY, "URL Protocol", "")
        Advapi32Util.registryCreateKey(hive, COMMAND_KEY)
        Advapi32Util.registrySetStringValue(hive, COMMAND_KEY, "", command)
    }

    internal fun launchCommand(paths: AppPaths): String? {
        val executable = ProcessHandle.current().info().command().orElse(null)?.let(Path::of) ?: return null
        // Without ".exe" too, so the development path is the same wherever the tests run.
        val name = executable.fileName.toString().lowercase().removeSuffix(".exe")
        if (name != "java" && name != "javaw") return "\"$executable\" \"%1\""
        // In development: javaw, so the link does not also open a console.
        val launcher = executable.resolveSibling("javaw.exe").takeIf(Files::exists) ?: executable
        val arguments = paths.root.resolve("launch.args")
        val classpath = System.getProperty("java.class.path").replace("\\", "\\\\")
        atomicWrite(arguments, "-cp \"$classpath\"\n$MAIN_CLASS\n".toByteArray(Charsets.UTF_8))
        return "\"$launcher\" @\"$arguments\" \"%1\""
    }
}

/**
 * A single app window at a time. Opening the app again — or opening an `imago://` link — hands the
 * request to the window that is already open, instead of opening a second one on the same
 * database.
 *
 * They talk over loopback, on a port chosen by the system and stored, with a secret, in the user's
 * app folder. Other users of the computer have no access to that folder.
 */
class SingleInstance(private val paths: AppPaths) : AutoCloseable {
    private val file = paths.root.resolve("instance")
    private var server: ServerSocket? = null

    /** Hands [link] (or just a focus request) to the open instance. `true` if there was one: this one should exit. */
    fun forward(link: String?): Boolean {
        val (port, token) = runCatching { Files.readAllLines(file).let { it[0].toInt() to it[1] } }.getOrNull() ?: return false
        return runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 2_000
                PrintWriter(socket.getOutputStream().writer(Charsets.UTF_8), true).println("$token ${link.orEmpty()}")
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine() == "ok"
            }
        }.getOrDefault(false)
    }

    /** Starts receiving requests from other launches. [onRequest] gets the link, or null just to take focus. */
    fun listen(onRequest: (String?) -> Unit) {
        val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = socket
        atomicWrite(file, "${socket.localPort}\n$token\n".toByteArray(Charsets.UTF_8))
        thread(name = "imago-single-instance", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                client.use {
                    it.soTimeout = 2_000
                    val line = runCatching { BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8)).readLine() }.getOrNull()
                    val parts = line?.split(' ', limit = 2)
                    if (parts?.firstOrNull() == token) {
                        PrintWriter(it.getOutputStream().writer(Charsets.UTF_8), true).println("ok")
                        onRequest(parts.getOrNull(1)?.takeIf(String::isNotBlank))
                    }
                }
            }
        }
    }

    override fun close() {
        server?.close()
        runCatching { Files.deleteIfExists(file) }
    }
}
