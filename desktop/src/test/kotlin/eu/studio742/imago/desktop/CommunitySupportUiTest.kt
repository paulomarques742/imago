package eu.studio742.imago.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.studio742.imago.core.data.DesktopDataGraph
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.i18n.LanguagePreference
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import eu.studio742.imago.core.sync.*
import eu.studio742.imago.feature.library.LibrarySettingsRoute
import eu.studio742.imago.feature.library.LibrarySettingsViewModel
import eu.studio742.imago.feature.library.CommunityReplyNotice
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Real settings at phone width, with local forum fixtures and intercepted browser links. */
@OptIn(ExperimentalTestApi::class)
class CommunitySupportUiTest {
    @Test fun supportLinksAndOwnTopicRepliesInEnglish() = checkSupport(false)
    @Test fun supportLinksAndOwnTopicRepliesInPortuguese() = checkSupport(true)
    @Test fun publicForumLinkStaysVisibleWithoutCommunityBackendInEnglish() = checkSupport(false, withCommunity = false)
    @Test fun publicForumLinkStaysVisibleWithoutCommunityBackendInPortuguese() = checkSupport(true, withCommunity = false)
    @Test fun publicForumLinkDoesNotNeedADevelopmentForumWebsite() = checkSupport(false, topicWebsite = false)

    private fun checkSupport(portuguese: Boolean, withCommunity: Boolean = true, topicWebsite: Boolean = true) {
        val previousLocale = Locale.getDefault()
        // Other language fixtures restore the process locale independently of AppLocale's cached state.
        Locale.setDefault(if (portuguese) Locale.forLanguageTag("pt-PT") else Locale.ENGLISH)
        val root = Files.createTempDirectory("forum-ui")
        val graph = DesktopDataGraph(root)
        val id = "0f215197-027b-4d94-94b8-40f45d7e60fe"
        var activity = ForumActivity(id, 1, "2026-10-28T10:00:00Z")
        val backend = object : ForumBackend {
            override val websiteUrl = if (topicWebsite) "https://imago.742studio.eu" else null
            override suspend fun profile() = ForumProfile(false, null)
            override suspend fun publish(draft: ForumDraft, images: List<ForumImage>) = ForumPost(id, "private-fixture-token", draft.title)
            override suspend fun delete(post: ForumPost) = error("Replied topics must not offer deletion")
            override suspend fun activity(topicIds: List<String>): List<ForumActivity> {
                assertEquals(listOf(id), topicIds)
                return listOf(activity)
            }
        }
        val data = mutableMapOf<String, String>()
        val preferences = object : SecurePreferences {
            override fun getString(key: String) = data[key]
            override fun write(changes: Map<String, String?>): Boolean {
                changes.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
                return true
            }
        }
        val repository = CommunityRepository(backend, preferences)
        runBlocking {
            repository.publish(ForumDraft(ForumCategory.QUESTION, "A question about editing", "Public fixture", "Guest"), emptyList())
            repository.refreshReplies()
        }
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        var opened: String? = null
        var browserFails = false
        val showBanner = mutableStateOf(false)
        val settings = LibrarySettingsViewModel(graph.configuration, graph.folders, graph.library)
        val uri = object : UriHandler {
            override fun openUri(uri: String) {
                if (browserFails) error("Browser unavailable")
                opened = uri
            }
        }
        try {
            runDesktopComposeUiTest(width = 390, height = 844) {
                setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDesktopDataGraph provides graph, LocalUriHandler provides uri) {
                        ProvideAppLanguage(if (portuguese) LanguagePreference.PORTUGUESE else LanguagePreference.ENGLISH, Locale.ENGLISH) {
                            ImagoTheme {
                                Surface(Modifier.fillMaxSize()) {
                                    Box {
                                        LibrarySettingsRoute({}, viewModel = settings, community = repository.takeIf { withCommunity })
                                        if (showBanner.value) CommunityReplyNotice(repository)
                                    }
                                }
                            }
                        }
                    }
                }
                onNodeWithText(if (portuguese) "Contactar suporte" else "Contact support").assertDoesNotExist()
                onNodeWithText(if (portuguese) "Abrir fórum" else "Open forum").performScrollTo().performClick()
                assertEquals("https://imago.742studio.eu/forum/", opened)
                onNodeWithText("https://imago.742studio.eu/forum/").assertIsDisplayed()
                val suffix = "${if (portuguese) "pt" else "en"}-${if (!withCommunity) "no-backend" else if (!topicWebsite) "no-site" else "configured"}"
                capture("support-collapsed-$suffix")
                if (!withCommunity) return@runDesktopComposeUiTest
                onNodeWithText(if (portuguese) "Suporte e diagnóstico · Respostas novas" else "Support and diagnostics · New replies")
                    .performScrollTo().performClick()
                val base = "https://imago.742studio.eu/${if (portuguese) "pt/" else ""}forum/"
                capture("support-${if (portuguese) "pt" else "en"}")
                onNodeWithText(if (portuguese) "Ver respostas" else "View replies").performScrollTo().performClick()
                onNodeWithText("A question about editing").assertIsDisplayed()
                onNodeWithText(if (portuguese) "Respostas novas" else "New replies").assertIsDisplayed()
                onNodeWithText(if (portuguese) "Apagar" else "Delete").assertDoesNotExist()
                if (!topicWebsite) {
                    onNodeWithText(if (portuguese) "Ver tópico" else "View topic").assertDoesNotExist()
                    return@runDesktopComposeUiTest
                }
                capture("replies-${if (portuguese) "pt" else "en"}")
                onNodeWithText(if (portuguese) "Ver tópico" else "View topic").performClick()
                assertEquals("${base}?t=$id", opened)
                waitUntil { repository.replies.value[id]?.unread == false }
                onNodeWithText(if (portuguese) "Respostas vistas" else "Replies viewed").assertIsDisplayed()
                activity = activity.copy(replyCount = 2, lastActivity = "2026-10-28T11:00:00Z")
                runBlocking { repository.refreshReplies() }
                browserFails = true
                onNodeWithText(if (portuguese) "Ver tópico" else "View topic").performClick()
                assertTrue(repository.replies.value.getValue(id).unread)
                onNodeWithText(if (portuguese) "Fechar" else "Close").performClick()
                runOnIdle { showBanner.value = true }
                waitForIdle()
                capture("banner-${if (portuguese) "pt" else "en"}")
                onAllNodesWithText(if (portuguese) "Ver respostas" else "View replies").onLast().performClick()
                onNodeWithText("A question about editing").assertIsDisplayed()
            }
        } finally {
            graph.close()
            root.toFile().deleteRecursively()
            Locale.setDefault(previousLocale)
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        val output = System.getProperty("imago.capture.out")?.let(::File) ?: return
        output.mkdirs()
        onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = onAllNodes(isRoot())[index].captureToImage().asSkiaBitmap()
            File(output, "$name-$index.png").writeBytes(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
