package eu.studio742.imago.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import eu.studio742.imago.desktop.platform.AppPaths
import eu.studio742.imago.desktop.platform.DesktopLanguageSettings
import eu.studio742.imago.desktop.platform.ImagoProtocol
import eu.studio742.imago.desktop.platform.SingleInstance
import eu.studio742.imago.desktop.platform.WindowsSecretStore
import eu.studio742.imago.desktop.sync.DesktopAccount
import eu.studio742.imago.core.data.DesktopDataGraph
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.i18n.AppSyncTexts
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import eu.studio742.imago.core.sync.SyncEngine
import eu.studio742.imago.feature.account.LocalAccountRepository
import eu.studio742.imago.feature.account.LocalSyncEngine
import eu.studio742.imago.feature.account.toIndicator
import eu.studio742.imago.feature.shell.ImagoApp
import eu.studio742.imago.feature.shell.shellViewModel
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * The IMAGO app for Windows.
 *
 * What you see is the same as on the phone — [ImagoApp], with the shared screens. This window only
 * brings in what belongs to this computer: the data layer, the account, the `imago://` protocol, a
 * single instance, and dropping folders on the window.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // The Windows language, read before the app can change the process locale.
    val systemLocale = java.util.Locale.getDefault()
    val paths = AppPaths()
    val link = ImagoProtocol.linkFrom(args)
    val instance = SingleInstance(paths)
    // A window is already open: the link (or just the focus request) goes to it, and this launch ends.
    if (instance.forward(link)) return

    val graph = DesktopDataGraph(paths.root)
    val languageSettings = DesktopLanguageSettings(paths.root.resolve("language"))
    val secrets = WindowsSecretStore(paths.secrets)
    val backend = DesktopAccount.backend(secrets)
    val account = DesktopAccount.create(paths, secrets, graph, backend = backend)
    val sync = SyncEngine(
        graph.database, backend, graph.contentHashes,
        deviceId = { account.currentDeviceId },
        deviceName = { account.deviceName.value },
        texts = AppSyncTexts,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
        .also(SyncEngine::start)

    application {
        var focusRequests by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            ImagoProtocol.register(paths).onFailure { System.err.println("[Imago] imago:// protocol not registered: ${it.message}") }
            instance.listen { request ->
                request?.let(account::handleAuthLink)
                focusRequests++
            }
            link?.let(account::handleAuthLink)
        }
        Window(
            onCloseRequest = { graph.close(); instance.close(); exitApplication() },
            title = "IMAGO",
            state = rememberWindowState(width = 1440.dp, height = 940.dp),
        ) {
            DisposableEffect(window) {
                // Returning to the window is the computer's "back to the foreground".
                val focus = object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(event: java.awt.event.WindowEvent?) = sync.syncSoon()
                }
                window.addWindowFocusListener(focus)
                onDispose { window.removeWindowFocusListener(focus) }
            }
            val indicator = remember { sync.status.map { it.toIndicator() } }
            LaunchedEffect(focusRequests) {
                if (focusRequests > 0) { window.isMinimized = false; window.toFront(); window.requestFocus() }
            }
            val languagePreference by languageSettings.preference.collectAsState()
            CompositionLocalProvider(
                LocalDesktopDataGraph provides graph,
                LocalAccountRepository provides account,
                LocalSyncEngine provides sync,
                LocalLanguageSettings provides languageSettings,
            ) {
                ProvideAppLanguage(languagePreference, systemLocale) {
                ImagoTheme {
                    val shell = shellViewModel()
                    // An account link opens the account, where the outcome is shown — as on Android.
                    LaunchedEffect(focusRequests) { if (account.passwordRecoveryPending.value) shell.openAccount() }
                    var dragging by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    val dropTarget = remember {
                        object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) { dragging = true }
                            override fun onExited(event: DragAndDropEvent) { dragging = false }
                            override fun onEnded(event: DragAndDropEvent) { dragging = false }
                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                dragging = false
                                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                                    .mapNotNull { runCatching { Path.of(URI(it)) }.getOrNull() }
                                if (files.isEmpty()) return false
                                scope.launch {
                                    // A dropped photo adds the folder it is in.
                                    files.map { if (Files.isDirectory(it)) it else it.parent }.distinct()
                                        .forEach { folder -> runCatching { graph.folders.addFolder(folder) } }
                                }
                                return true
                            }
                        }
                    }
                    Surface(
                        Modifier.fillMaxSize()
                            .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = dropTarget)
                            .then(if (dragging) Modifier.border(2.dp, ImagoColors.Ivory) else Modifier),
                        color = ImagoColors.Background,
                    ) {
                        ImagoApp(shell, syncIndicator = indicator)
                    }
                }
                }
            }
        }
    }
}
