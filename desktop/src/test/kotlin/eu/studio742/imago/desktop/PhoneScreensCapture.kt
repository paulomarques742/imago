package eu.studio742.imago.desktop

import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.filter
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.fillMaxSize
import eu.studio742.imago.core.data.DesktopDataGraph
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.i18n.AppSyncTexts
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import eu.studio742.imago.core.sync.SyncEngine
import eu.studio742.imago.desktop.platform.AppPaths
import eu.studio742.imago.desktop.platform.DesktopLanguageSettings
import eu.studio742.imago.desktop.platform.WindowsSecretStore
import eu.studio742.imago.desktop.sync.DesktopAccount
import eu.studio742.imago.feature.account.LocalAccountRepository
import eu.studio742.imago.feature.account.LocalSyncEngine
import eu.studio742.imago.feature.shell.ImagoApp
import eu.studio742.imago.feature.shell.shellViewModel
import java.io.File
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume
import org.junit.Test

/**
 * The app's real screens at a phone's size, for the promotional videos: the shared interface, the
 * same one the phone shows, rendered off screen and driven through its semantics, frame by frame.
 *
 * It only runs when asked: `-Pimago.capture.out=<folder>` (where the frames go),
 * `-Pimago.capture.photos=<folder>` (the library) and `-Pimago.capture.profile=<folder>` (a throwaway
 * data folder, never the person's own).
 */
@OptIn(ExperimentalTestApi::class)
class PhoneScreensCapture {
    private val out = System.getProperty("imago.capture.out")?.let(::File)
    private val photos = System.getProperty("imago.capture.photos")?.let { Path.of(it) }
    private val profile = System.getProperty("imago.capture.profile")?.let { Path.of(it) }
    /**
     * A `.env` file with `IMMICH_URL` and `IMMICH_API_KEY`: the run connects to that server and shows
     * its library instead of the folder. Read from the file so the key never goes on a command line.
     */
    private val immichEnv = System.getProperty("imago.capture.immichEnv")?.let(::File)

    /** Which scenes to make (`scroll,edit,compose`); all of them when not given. */
    private val scenes = System.getProperty("imago.capture.scenes")?.split(",")?.map(String::trim)

    private fun wants(scene: String) = scenes == null || scene in scenes

    /** `phone` (the default) or `tablet`: the window's size and density. */
    private val device = Device.valueOf((System.getProperty("imago.capture.device") ?: "phone").uppercase())

    @Test
    fun `the phone's screens, frame by frame`() {
        Assume.assumeTrue("needs -Pimago.capture.out, .photos and .profile", out != null && photos != null && profile != null)
        Locale.setDefault(Locale.ENGLISH)
        out!!.mkdirs()
        val paths = AppPaths(profile!!)
        val graph = DesktopDataGraph(paths.root)
        runBlocking { graph.folders.addFolder(photos!!) }
        immichEnv?.let { file ->
            val env = file.readLines().filter { "=" in it && !it.startsWith("#") }
                .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().trim('"') }
            runBlocking { graph.configuration.saveLibrary(null, "Home", env.getValue("IMMICH_URL"), env.getValue("IMMICH_API_KEY")) }
            graph.configuration.selectLibrary(graph.configuration.libraries.value.first { !it.isDevice }.id)
        }
        graph.configuration.completeWelcome()
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

        // What a window gives its screens, and a test does not: an owner for the ViewModels and a
        // lifecycle that is already in the foreground.
        val owner = object : ViewModelStoreOwner, LifecycleOwner {
            override val viewModelStore = ViewModelStore()
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        runDesktopComposeUiTest(width = device.widthPx, height = device.heightPx) {
            setContent {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner,
                    LocalDensity provides Density(device.density),
                    LocalDesktopDataGraph provides graph,
                    LocalAccountRepository provides account,
                    LocalSyncEngine provides sync,
                    LocalLanguageSettings provides languageSettings,
                ) {
                    ProvideAppLanguage(languageSettings.preference.value, Locale.ENGLISH) {
                        ImagoTheme {
                            val shell = shellViewModel()
                            Surface(Modifier.fillMaxSize(), color = ImagoColors.Background) {
                                ImagoApp(shell, syncIndicator = null)
                            }
                        }
                    }
                }
            }
            settle(3_000)
            if (onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("Skip").performClick()
            settle(4_000)
            shot("01-library")

            val screen = onAllNodes(isRoot())[0]
            if (wants("scroll")) {
                // The library scrolling under a finger: a slow drag that stops before the end of the
                // photos, then the release.
                clip("02-scroll", frames = 60) { frame ->
                    when {
                        frame == 0 -> screen.performTouchInput { down(Offset(width * 0.5f, height * 0.8f)) }
                        frame < 45 -> screen.performTouchInput { moveBy(Offset(0f, -20f)) }
                        frame == 45 -> screen.performTouchInput { up() }
                    }
                }
                settle(1_000)
                screen.performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f) }
                settle(1_500)
                screen.performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f) }
                settle(1_500)
            }
            if (wants("edit")) {
                onNodeWithContentDescription("Open DSCF3168.jpg").performClick()
                settle(3_000)
                shot("03-detail")
                tree("03-detail")
                onNodeWithText("Edit").performClick()
                settle(4_000)
                shot("04-editor")
                tree("04-editor")
                if (onAllNodesWithText("Adjust").fetchSemanticsNodes().isNotEmpty()) onAllNodesWithText("Adjust")[0].performClick()
                settle(2_000)
                shot("05-adjust")
                tree("05-adjust")

                // The edit, done by a finger: each slider is found by its name when its turn comes,
                // since the list moves once it scrolls. A horizontal drag moves a slider by as much as
                // the finger moved.
                val gestures = Gestures(this)
                gestures.pause(10)
                gestures.drag("Exposure", dx = 110f, frames = 16)
                gestures.pause(8)
                gestures.drag("Contrast", dx = 150f, frames = 18)
                gestures.pause(8)
                gestures.scrollPanel(dy = -330f, frames = 14)
                gestures.pause(6)
                gestures.drag("Highlights", dx = -240f, frames = 20)
                gestures.pause(8)
                gestures.drag("Shadows", dx = 230f, frames = 20)
                gestures.pause(10)
                gestures.tap(contentDescription = "Color")
                gestures.pause(14)
                gestures.drag("Vibrance", dx = 180f, frames = 18)
                gestures.pause(24)
                clip("06-edit", frames = gestures.frames) { gestures.run(it) }
                tree("06-after-edit")
            }
            // Reel 02: a photo ruined by hand, then the original back, then the history.
            if (wants("ruin")) {
                onNodeWithContentDescription("Open DSCF9039.jpg").performClick()
                settle(3_000)
                onNodeWithText("Edit").performClick()
                settle(4_000)
                onAllNodesWithText("Adjust")[0].performClick()
                settle(2_000)
                val ruin = Gestures(this)
                ruin.drag("Exposure", dx = 190f, frames = 8)
                ruin.pause(8)
                ruin.drag("Contrast", dx = 420f, frames = 8)
                ruin.pause(8)
                ruin.tap(contentDescription = "Color")
                ruin.pause(8)
                ruin.scrollPanel(dy = -330f, frames = 8, from = "Temp")
                ruin.pause(6)
                ruin.drag("Vibrance", dx = 460f, frames = 8)
                ruin.pause(10)
                clip("21-ruin", frames = ruin.frames) { ruin.run(it) }
                shot("21-ruined")

                // The original, untouched, one tap away; and back to the ruin.
                val original = Gestures(this)
                original.pause(10)
                original.tap(contentDescription = "Show original")
                original.pause(45)
                // The same button, now offering the edit back.
                original.tap(contentDescription = "Back to the edited version")
                original.pause(12)
                clip("22-original", frames = original.frames) { original.run(it) }

                // The history, stepping back to before the ruin.
                val history = Gestures(this)
                history.tapText("History")
                history.pause(16)
                history.tapText("Contrast")
                history.pause(16)
                history.tapText("Exposure")
                history.pause(16)
                history.tap(contentDescription = "Undo")
                history.pause(20)
                clip("23-history", frames = history.frames) { history.run(it) }
                shot("23-history-end")
                tree("23-history-end")

                // A good edit, from the original.
                onAllNodesWithText("Adjust")[0].performClick()
                settle(1_500)
                if (onAllNodesWithContentDescription("Light").fetchSemanticsNodes().isNotEmpty()) onNodeWithContentDescription("Light").performClick()
                settle(1_500)
                val good = Gestures(this)
                good.pause(8)
                good.drag("Contrast", dx = 90f, frames = 14)
                good.pause(6)
                good.scrollPanel(dy = -330f, frames = 12)
                good.pause(4)
                good.drag("Highlights", dx = -230f, frames = 16)
                good.pause(6)
                good.drag("Shadows", dx = 150f, frames = 14)
                good.pause(8)
                good.tap(contentDescription = "Color")
                // The Color tab keeps where it was scrolled: Vibrance is already on screen.
                good.pause(10)
                good.drag("Vibrance", dx = 150f, frames = 14)
                good.pause(16)
                clip("24-good", frames = good.frames) { good.run(it) }
                tree("24-good")
            }
            // Reel 05: an orange café under tungsten light, and the white balance eyedropper.
            if (wants("wb")) {
                onNodeWithContentDescription("Open cafe-tungsten.jpg").performScrollTo().performClick()
                settle(3_000)
                onNodeWithText("Edit").performClick()
                settle(4_000)
                onAllNodesWithText("Adjust")[0].performClick()
                settle(1_500)
                onNodeWithContentDescription("Color").performClick()
                settle(1_500)
                shot("31-cafe")
                onNodeWithContentDescription("Pick the white balance on the photo").performClick()
                settle(2_000)
                shot("32-picker")
                // The loupe, from the middle of the photo to the white cups on the shelf: the colour
                // corrects itself live as it moves. Then accepted, and the original once for comparison.
                val wb = Gestures(this)
                wb.pause(12)
                wb.dragBetween(Offset(539f, 1104f), Offset(917f, 1017f), frames = 45)
                wb.pause(20)
                wb.tap(contentDescription = "Apply")
                wb.pause(24)
                wb.tap(contentDescription = "Show original")
                wb.pause(30)
                wb.tap(contentDescription = "Back to the edited version")
                wb.pause(20)
                clip("33-wb", frames = wb.frames) { wb.run(it) }
                tree("33-wb")
            }
            // Reel 03: the library on an Immich server, a photo edited, and the export back to it.
            if (wants("immich-explore")) {
                tree("41-immich-library")
                onNodeWithContentDescription("Open porto-river.jpg").performScrollTo().performClick()
                settle(4_000)
                onNodeWithText("Edit").performClick()
                settle(5_000)
                shot("42-editor")
                onNodeWithContentDescription("Export").performClick()
                settle(2_500)
                shot("43-export")
                tree("43-export")
            }
            // Reel 03, for real: the server's library scrolling, the photo edited by a finger, and the
            // edit sent back to Immich, where it is stacked on the original. The export happens once
            // per run: each run adds one more edit to the demo account's stack.
            if (wants("immich")) {
                clip("41-scroll", frames = 60) { frame ->
                    when {
                        frame == 0 -> screen.performTouchInput { down(Offset(width * 0.5f, height * 0.8f)) }
                        frame < 45 -> screen.performTouchInput { moveBy(Offset(0f, -20f)) }
                        frame == 45 -> screen.performTouchInput { up() }
                    }
                }
                settle(1_000)
                onNodeWithContentDescription("Open porto-river.jpg").performScrollTo().performClick()
                settle(4_000)
                onNodeWithText("Edit").performClick()
                settle(5_000)
                onAllNodesWithText("Adjust")[0].performClick()
                settle(2_000)
                val edit = Gestures(this)
                edit.pause(8)
                edit.drag("Exposure", dx = 70f, frames = 14)
                edit.pause(8)
                edit.drag("Contrast", dx = 130f, frames = 14)
                edit.pause(8)
                edit.scrollPanel(dy = -330f, frames = 12)
                edit.pause(4)
                edit.drag("Highlights", dx = -220f, frames = 16)
                edit.pause(6)
                edit.drag("Shadows", dx = 170f, frames = 14)
                edit.pause(8)
                edit.tap(contentDescription = "Color")
                edit.pause(10)
                edit.drag("Vibrance", dx = 200f, frames = 14)
                edit.pause(16)
                clip("42-edit", frames = edit.frames) { edit.run(it) }
                tree("42-edit")

                val send = Gestures(this)
                send.pause(6)
                send.tap(contentDescription = "Export")
                send.pause(24)
                send.tapText("Send to Immich")
                send.pause(60)
                clip("43-send", frames = send.frames) { send.run(it) }
                settle(6_000)
                shot("44-sent")
                tree("44-sent")
            }
            // Reel 06, One slider: Dehaze. A hazy valley, the slider pushed both ways, then the original.
            if (wants("dehaze")) {
                onNodeWithContentDescription("Open haze-valley.jpg").performScrollTo().performClick()
                settle(3_000)
                onNodeWithText("Edit").performClick()
                settle(4_000)
                onAllNodesWithText("Adjust")[0].performClick()
                settle(1_500)
                onNodeWithContentDescription("Detail").performClick()
                settle(1_500)
                shot("51-detail")
                tree("51-detail")
                val dehaze = Gestures(this)
                dehaze.pause(14)
                dehaze.drag("Dehaze", dx = 275f, frames = 30)
                dehaze.pause(30)
                dehaze.drag("Dehaze", dx = -495f, frames = 36)
                dehaze.pause(30)
                dehaze.drag("Dehaze", dx = 385f, frames = 26)
                dehaze.pause(24)
                dehaze.tap(contentDescription = "Show original")
                dehaze.pause(30)
                dehaze.tap(contentDescription = "Back to the edited version")
                dehaze.pause(20)
                clip("52-dehaze", frames = dehaze.frames) { dehaze.run(it) }
                tree("52-dehaze")
            }
            // Reel 07: a continuous carousel made in the composer, and its pages exported by the app.
            if (wants("carousel")) {
                onNodeWithText("Compose").performClick()
                settle(2_000)
                onNodeWithText("New").performClick()
                settle(2_000)
                onAllNodesWithText("Panorama · 2 pages")[0].performClick()
                settle(3_000)
                val build = Gestures(this)
                build.pause(12)
                build.step { onAllNodesWithContentDescription("Media slot")[0].performClick() }
                build.pause(24)
                build.step { onNodeWithContentDescription("Select DSCF7951.jpg").performClick() }
                build.pause(12)
                build.step { addButton().performClick() }
                build.pause(36)
                // Out, until both pages are on the screen.
                repeat(3) {
                    build.tapText("−")
                    build.pause(14)
                }
                build.pause(12)
                // A title, typed a letter at a time, on the first page.
                build.step { onNodeWithContentDescription("Text").performClick() }
                build.pause(18)
                build.step { onNodeWithContentDescription("Edit").performClick() }
                build.pause(14)
                val title = "Into the dunes"
                for (length in 1..title.length) {
                    build.step { onNode(hasSetTextAction()).performTextReplacement(title.take(length)) }
                    build.pause(1)
                }
                build.pause(10)
                build.step { onNodeWithText("Apply").performClick() }
                build.pause(36)
                clip("64-carousel", frames = build.frames) { build.run(it) }
                shot("65-carousel")
                tree("65-carousel")

                val project = runBlocking { graph.compositions.observeAll().first().maxBy { it.updatedAt } }
                val pages = runBlocking {
                    eu.studio742.imago.feature.composer.DesktopCompositionExports(graph.compositionMedia, picturesFolder = File(out, "67-pages").toPath())
                        .export(project, eu.studio742.imago.feature.composer.StaticExportFormat.JPEG, pageIndex = null, targetLibraryId = null) { _, _ -> }
                }
                File(out, "67-pages.txt").writeText(pages.locations.joinToString("\n"))
            }
            // The tablet: the same photo, before and after the phone edited it (run with
            // -Pimago.capture.device=tablet on the profile the phone's run used).
            for (moment in listOf("before", "after")) {
                if (!wants("tablet-$moment")) continue
                onNodeWithContentDescription("Open DSCF3168.jpg").performClick()
                settle(3_000)
                onNodeWithText("Edit").performClick()
                settle(4_000)
                if (onAllNodesWithText("Adjust").fetchSemanticsNodes().isNotEmpty()) onAllNodesWithText("Adjust")[0].performClick()
                settle(2_500)
                shot("13-tablet-$moment")
                tree("13-tablet-$moment")
            }
            if (wants("compose")) {
                // From the editor (or the photo) back to the library, where the Compose tab is.
                repeat(3) {
                    if (onAllNodesWithContentDescription("Back to library").fetchSemanticsNodes().isNotEmpty()) {
                        onAllNodesWithContentDescription("Back to library")[0].performClick()
                        settle(1_500)
                    }
                }
                onNodeWithText("Compose").performClick()
                settle(2_000)
                shot("07-compose")
                tree("07-compose")
                onNodeWithText("New").performClick()
                settle(2_000)
                // The formats, one family after the other: social, prints, editorial.
                val formats = Gestures(this)
                formats.pause(10)
                formats.tapText("Photo prints")
                formats.pause(22)
                formats.tapText("Editorial")
                formats.pause(22)
                formats.tapText("Social media")
                formats.pause(14)
                clip("11-formats", frames = formats.frames) { formats.run(it) }
                tree("11-formats")
                onNodeWithText("Hero + two").performClick()
                settle(3_000)
                // The three photos chosen at once, the edited train first: the layout fills its slots in order.
                onAllNodesWithContentDescription("Media slot")[0].performClick()
                settle(2_500)
                for (photo in listOf("DSCF3168.jpg", "DSCF9039.jpg", "white-village.jpg")) {
                    onNodeWithContentDescription("Select $photo").performClick()
                    settle(400)
                }
                // The button says how many are chosen ("Add", "Add 3"…): the clickable one that starts so.
                onAllNodes(hasClickAction() and hasText("Add", substring = true))
                    .filter(SemanticsMatcher("not the title") { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().none { it.text == "Add media" }
                    })[0]
                    .performClick()
                settle(4_000)
                shot("12-composed")
                tree("12-composed")
            }
        }
        graph.close()
    }

    /** Lets the app work in real time — thumbnails decode off the test clock — then idles. */
    private fun ComposeUiTest.settle(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            mainClock.advanceTimeBy(100)
            Thread.sleep(100)
        }
        waitForIdle()
    }

    /**
     * A finger's script, frame by frame: each gesture queues what happens on each of its frames, and
     * looks for its slider only when its first frame comes.
     */
    private inner class Gestures(private val test: ComposeUiTest) {
        private val steps = mutableListOf<() -> Unit>()
        val frames get() = steps.size

        fun pause(count: Int) = repeat(count) { steps += {} }

        fun drag(label: String, dx: Float, frames: Int) {
            var start = Offset.Zero
            for (frame in 0 until frames) {
                steps += {
                    val root = test.onAllNodes(isRoot())[0]
                    when (frame) {
                        0 -> {
                            start = test.sliderUnder(label)
                            root.performTouchInput { down(start) }
                        }
                        frames - 1 -> root.performTouchInput { moveTo(start + Offset(dx * ease(1f), 0f)); up() }
                        else -> root.performTouchInput { moveTo(start + Offset(dx * ease(frame / (frames - 1f)), 0f)) }
                    }
                }
            }
        }

        /** Scrolls the panel by dragging from the slider under [from], which has to be on screen. */
        fun scrollPanel(dy: Float, frames: Int, from: String = "Contrast") {
            var start = Offset.Zero
            for (frame in 0 until frames) {
                steps += {
                    val root = test.onAllNodes(isRoot())[0]
                    when (frame) {
                        0 -> {
                            start = test.sliderUnder(from)
                            root.performTouchInput { down(start) }
                        }
                        frames - 1 -> root.performTouchInput { moveTo(start + Offset(0f, dy)); up() }
                        else -> root.performTouchInput { moveTo(start + Offset(0f, dy * ease(frame / (frames - 1f)))) }
                    }
                }
            }
        }

        /** A finger from [from] to [to], in the window's pixels: the white balance loupe, for one. */
        fun dragBetween(from: Offset, to: Offset, frames: Int) {
            for (frame in 0 until frames) {
                steps += {
                    val root = test.onAllNodes(isRoot())[0]
                    val at = from + (to - from) * ease(frame / (frames - 1f))
                    when (frame) {
                        0 -> root.performTouchInput { down(from) }
                        frames - 1 -> root.performTouchInput { moveTo(to); up() }
                        else -> root.performTouchInput { moveTo(at) }
                    }
                }
            }
        }

        /** Anything else a frame has to do, against the test. */
        fun step(action: ComposeUiTest.() -> Unit) {
            steps += { test.action() }
        }

        fun tapText(text: String) {
            // The first match: a family's tab comes before a layout that may share its name.
            steps += { test.onAllNodesWithText(text)[0].performClick() }
        }

        fun tap(contentDescription: String) {
            steps += { test.onNodeWithContentDescription(contentDescription).performClick() }
        }

        fun run(frame: Int) = steps[frame]()

        private fun ease(t: Float) = t * t * (3 - 2 * t)
    }

    /** The picker's "Add" button, whose text says how many are chosen ("Add", "Add 3"…). */
    private fun ComposeUiTest.addButton() =
        onAllNodes(hasClickAction() and hasText("Add", substring = true))
            .filter(SemanticsMatcher("not the title") { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().none { it.text == "Add media" }
            })[0]

    /** The middle of the slider under the label [label], in the window's pixels. */
    private fun ComposeUiTest.sliderUnder(label: String): Offset {
        val text = onAllNodesWithText(label).fetchSemanticsNodes().first { it.boundsInRoot.top > 1000f }.boundsInRoot
        val slider = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.top >= text.bottom - 4f }
            .minBy { it.top }
        return slider.center
    }

    /**
     * A clip, frame by frame at 30 fps: the clock stops running by itself, [step] moves the finger
     * for each frame, and the clock advances a frame's time before the picture is taken.
     */
    private fun ComposeUiTest.clip(name: String, frames: Int, step: (Int) -> Unit) {
        val dir = File(out, name).apply { deleteRecursively(); mkdirs() }
        mainClock.autoAdvance = false
        for (frame in 0 until frames) {
            step(frame)
            mainClock.advanceTimeBy(FRAME_MS)
            val image = onAllNodes(isRoot())[0].captureToImage()
            File(dir, "%04d.png".format(frame)).writeBytes(
                Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes,
            )
        }
        mainClock.autoAdvance = true
    }

    /** What is on screen and can be tapped, to write the next step against. */
    private fun ComposeUiTest.tree(name: String) {
        File(out, "$name.txt").writeText(onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n\n") {
            onAllNodes(isRoot())[it].printToString(maxDepth = Int.MAX_VALUE)
        })
    }

    private fun ComposeUiTest.shot(name: String) {
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
        for (index in 0 until roots) {
            val image = onAllNodes(isRoot())[index].captureToImage()
            val png = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, if (index == 0) "$name.png" else "$name-layer$index.png").writeBytes(png)
        }
    }

    private companion object {
        const val FRAME_MS = 1000L / 30
    }

    /**
     * The app's part of each screen; the status and navigation bars, 24 dp each, are drawn in the
     * video. The phone is 412 × 843 dp (of a 412 × 891 dp screen) at the density that makes it 1080 px
     * wide; the tablet, lying down, 1280 × 752 dp (of 1280 × 800 dp) at 2 px per dp.
     */
    private enum class Device(val widthPx: Int, val heightPx: Int, val density: Float) {
        PHONE(1080, 2210, 1080f / 412f),
        TABLET(2560, 1504, 2f),
    }
}
