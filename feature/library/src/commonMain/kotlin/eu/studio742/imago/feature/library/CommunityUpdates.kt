package eu.studio742.imago.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import eu.studio742.imago.core.sync.CommunityRepository
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.stringResource

internal const val COMMUNITY_FORUM_URL = "https://imago.742studio.eu/forum/"

/** A completed check waits for the next foreground entry instead of scheduling another request. */
internal suspend fun observeCommunityReplies(repository: CommunityRepository, lifecycle: Lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        repository.refreshReplies()
        awaitCancellation()
    }
}

/** Both platforms check once when the app opens or returns to the foreground. */
@Composable
fun CommunityReplyNotice(repository: CommunityRepository?) {
    if (repository == null) return
    val replies by repository.replies.collectAsState()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(repository, owner) {
        observeCommunityReplies(repository, owner.lifecycle)
    }
    val unread = replies.filterValues { it.unread }.mapValues { it.value.activity }
    var dismissed by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var showPosts by remember { mutableStateOf(false) }
    if (unread.isNotEmpty() && unread != dismissed && !showPosts) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(Modifier.widthIn(max = 600.dp).semantics { liveRegion = LiveRegionMode.Polite },
                actionOnNewLine = true,
                action = { TextButton(onClick = { showPosts = true; dismissed = unread },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface)) {
                    Text(stringResource(Res.string.community_view_replies))
                } },
                dismissAction = { TextButton(onClick = { dismissed = unread },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface)) {
                    Text(stringResource(Res.string.community_close))
                } },
            ) { Text(stringResource(Res.string.community_unread)) }
        }
    }
    if (showPosts) CommunityPostsDialog(repository) { showPosts = false }
}
