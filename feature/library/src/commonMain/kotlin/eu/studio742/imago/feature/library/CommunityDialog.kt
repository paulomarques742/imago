package eu.studio742.imago.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.studio742.imago.core.render.decodePixels
import eu.studio742.imago.core.render.toImageBitmap
import eu.studio742.imago.core.sync.*
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal expect fun rememberCommunityImages(onImages: (List<ForumImage>) -> Unit, onFailure: () -> Unit, onBusy: (Boolean) -> Unit): () -> Unit

/** Only a public topic ID enters the URL. Ownership tokens always stay on the device. */
internal fun communityTopicUrl(baseUrl: String, id: String? = null, portuguese: Boolean = false): String =
    "${baseUrl.trimEnd('/')}/${if (portuguese) "pt/" else ""}forum/" + if (id == null) "" else "?t=$id"

@Composable
internal fun CommunityDialog(repository: CommunityRepository, onDismiss: () -> Unit) {
    val model = viewModel(key = "community-composer") { CommunityComposer(repository) }
    val draft by model.draft.collectAsState()
    val images by model.images.collectAsState()
    val publishing by model.busy.collectAsState()
    val preparingImages by model.preparingImages.collectAsState()
    val busy = publishing || preparingImages
    val loading by model.loading.collectAsState()
    val loaded by model.profileLoaded.collectAsState()
    val review by model.reviewing.collectAsState()
    val created by model.created.collectAsState()
    val error by model.error.collectAsState()
    val imageError by model.imageError.collectAsState()
    var nameEditing by rememberSaveable { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val portuguese = stringResource(Res.string.community_language) == "pt"
    var linkFailed by remember { mutableStateOf(false) }
    val picker = rememberCommunityImages(model::attach, { model.imageError.value = true }, { model.preparingImages.value = it })
    val dismiss = { if (!busy) { model.reset(); onDismiss() } }
    LaunchedEffect(model) { model.loadProfile() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.systemBarsPadding().imePadding().padding(12.dp).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.95f),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (created != null) Res.string.community_success else if (review) Res.string.community_review else Res.string.community_new),
                    style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.community_public), style = MaterialTheme.typography.bodySmall)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!loaded && !loading) TextButton(onClick = model::loadProfile) { Text(stringResource(Res.string.community_retry_profile)) }
                    if (created != null) {
                        Text(stringResource(Res.string.community_published))
                        Text(draft.title, style = MaterialTheme.typography.titleMedium)
                        if (repository.storageFailed) Text(stringResource(Res.string.community_storage_failed), color = MaterialTheme.colorScheme.error)
                    } else if (review) {
                        Text(stringResource(draft.category.label()), style = MaterialTheme.typography.labelLarge)
                        Text(draft.title, style = MaterialTheme.typography.titleMedium)
                        Text(draft.body)
                        Text(stringResource(Res.string.community_as, draft.name), style = MaterialTheme.typography.labelLarge)
                        if (draft.category == ForumCategory.BUG) {
                            Text(stringResource(Res.string.community_version_value, draft.appVersion))
                            Text(stringResource(Res.string.community_device_value, draft.device))
                        }
                    } else {
                        Box {
                            OutlinedButton(enabled = loaded && !busy, onClick = { categoryExpanded = true }) {
                                Text(stringResource(Res.string.community_category_value, stringResource(draft.category.label())))
                            }
                            DropdownMenu(categoryExpanded, { categoryExpanded = false }) {
                                ForumCategory.entries.forEach { category ->
                                    DropdownMenuItem(text = { Text(stringResource(category.label())) }, onClick = {
                                        model.draft.value = draft.copy(category = category); categoryExpanded = false
                                    })
                                }
                            }
                        }
                        OutlinedTextField(draft.title, { model.draft.value = draft.copy(title = it.take(120)) },
                            label = { Text(stringResource(Res.string.community_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        OutlinedTextField(draft.body, { model.draft.value = draft.copy(body = it.take(5000)) },
                            label = { Text(stringResource(Res.string.community_body)) }, minLines = 5, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        if (draft.name.isBlank() || nameEditing) {
                            OutlinedTextField(draft.name, { nameEditing = true; model.draft.value = draft.copy(name = it.take(40)) },
                                label = { Text(stringResource(Res.string.community_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = loaded && !busy)
                        } else {
                            Text(stringResource(Res.string.community_as, draft.name))
                            TextButton(onClick = { nameEditing = true }) { Text(stringResource(Res.string.community_change_name)) }
                        }
                        if (draft.category == ForumCategory.BUG) {
                            OutlinedTextField(draft.appVersion, { model.draft.value = draft.copy(appVersion = it.take(40)) },
                                label = { Text(stringResource(Res.string.community_version)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.device, { model.draft.value = draft.copy(device = it.take(80)) },
                                label = { Text(stringResource(Res.string.community_device)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedButton(onClick = picker, enabled = images.size < 4 && !busy) { Text(stringResource(Res.string.community_add_images)) }
                        Text(stringResource(Res.string.community_images_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    if (created == null) images.forEachIndexed { index, image ->
                        val bitmap = remember(image) { decodePixels(image.bytes)?.toImageBitmap() }
                        if (bitmap != null) Image(bitmap, stringResource(Res.string.community_image, index + 1),
                            Modifier.fillMaxWidth().heightIn(max = 200.dp), contentScale = ContentScale.Fit)
                        if (!review) TextButton(onClick = { model.images.value = images.filterIndexed { i, _ -> i != index } }, enabled = !busy) {
                            Text(stringResource(Res.string.community_remove_image, index + 1))
                        }
                    }
                    if (imageError) Text(stringResource(Res.string.community_image_failed), color = MaterialTheme.colorScheme.error)
                    if (error != null) Text(stringResource(error!!.label()), color = MaterialTheme.colorScheme.error)
                    if (linkFailed) Text(stringResource(Res.string.community_link_failed), color = MaterialTheme.colorScheme.error)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    repository.websiteUrl?.let { website ->
                        TextButton(enabled = !busy, onClick = {
                            linkFailed = runCatching { uri.openUri(communityTopicUrl(website, created?.id, portuguese)) }.isFailure
                        }) { Text(stringResource(Res.string.community_open_forum)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = {
                        if (review && created == null) { model.reviewing.value = false; model.error.value = null } else dismiss()
                    }) { Text(stringResource(if (created != null) Res.string.community_close else if (review) Res.string.community_edit else Res.string.library_cancel)) }
                    if (created == null) Button(enabled = !busy && loaded && draft.isValid(), onClick = {
                        if (review) model.publish() else model.reviewing.value = true
                    }) { Text(stringResource(if (review) Res.string.community_publish else Res.string.community_review)) }
                }
            }
        }
    }
}

@Composable
internal fun CommunityPostsDialog(repository: CommunityRepository, onDismiss: () -> Unit) {
    val posts by repository.posts.collectAsState()
    var deleting by remember { mutableStateOf<ForumPost?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ForumException.Reason?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val portuguese = stringResource(Res.string.community_language) == "pt"
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(Res.string.community_my_posts)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.community_my_posts_hint))
            posts.forEach { post ->
                Text(post.title, style = MaterialTheme.typography.titleSmall)
                Row {
                    repository.websiteUrl?.let { website ->
                        TextButton(enabled = !busy, onClick = { runCatching { uri.openUri(communityTopicUrl(website, post.id, portuguese)) }
                            .onFailure { error = ForumException.Reason.FAILED } }) { Text(stringResource(Res.string.community_view)) }
                    }
                    TextButton(enabled = !busy, onClick = { deleting = post }) { Text(stringResource(Res.string.community_delete)) }
                }
            }
            if (error != null) Text(stringResource(error!!.label()), color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.community_close)) } })
    deleting?.let { post ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text(stringResource(Res.string.community_delete_confirm)) },
            text = { Text(post.title) }, confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; error = null
                    try { repository.delete(post) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = (failure as? ForumException)?.reason ?: ForumException.Reason.FAILED }
                    finally { busy = false; deleting = null }
                }
            }) { Text(stringResource(Res.string.community_delete)) } }, dismissButton = {
                TextButton(enabled = !busy, onClick = { deleting = null }) { Text(stringResource(Res.string.library_cancel)) }
            })
    }
}

internal fun ForumCategory.label(): StringResource = when (this) {
    ForumCategory.BUG -> Res.string.community_bug
    ForumCategory.IDEA -> Res.string.community_idea
    ForumCategory.QUESTION -> Res.string.community_question
    ForumCategory.OTHER -> Res.string.community_other
}

private fun ForumException.Reason.label(): StringResource = when (this) {
    ForumException.Reason.INVALID -> Res.string.community_error_invalid
    ForumException.Reason.RATE_LIMITED, ForumException.Reason.BUSY -> Res.string.community_error_rate
    ForumException.Reason.TOO_MANY_LINKS -> Res.string.community_error_links
    ForumException.Reason.REJECTED -> Res.string.community_error_rejected
    ForumException.Reason.IMAGE_UPLOAD, ForumException.Reason.IMAGE_MISSING -> Res.string.community_error_images
    ForumException.Reason.SESSION_EXPIRED -> Res.string.community_error_session
    ForumException.Reason.FORBIDDEN -> Res.string.community_error_forbidden
    ForumException.Reason.HAS_REPLIES -> Res.string.community_error_replies
    ForumException.Reason.NOT_FOUND -> Res.string.community_error_not_found
    ForumException.Reason.OFFLINE, ForumException.Reason.FAILED -> Res.string.community_error_failed
}
