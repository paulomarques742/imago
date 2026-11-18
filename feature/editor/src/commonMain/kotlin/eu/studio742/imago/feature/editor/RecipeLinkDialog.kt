package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.interchange.NoRecipeLinks
import eu.studio742.imago.core.interchange.RecipeLinkImages
import eu.studio742.imago.core.interchange.RecipeGalleryLimits
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import eu.studio742.imago.core.interchange.PHOTO_BOUND_PARTS
import eu.studio742.imago.core.interchange.RecipeLinkError
import eu.studio742.imago.core.interchange.RecipeLinkException
import eu.studio742.imago.core.interchange.RecipeLinkService
import eu.studio742.imago.core.interchange.imagoFileName
import eu.studio742.imago.core.interchange.recipeLinkUrl
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.withPartsFrom
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.toImageBitmap
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

/** Provided at the root of each app, which is what knows about the account. */
val LocalRecipeLinks = staticCompositionLocalOf<RecipeLinkService> { NoRecipeLinks }

/** The state of sharing one recipe by link. The link's content goes through the exporter, like a file's. */
@Stable
internal class RecipeLinkController(
    private val service: RecipeLinkService,
    private val scope: CoroutineScope,
) {
    val available: Boolean get() = service.baseUrl != null

    var target by mutableStateOf<SavedRecipe?>(null)
        private set
    var linkId by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<RecipeLinkError?>(null)
        private set
    var updated by mutableStateOf(false)
        private set
    var askingAccount by mutableStateOf(false)
        private set
    var confirmingStop by mutableStateOf(false)
        private set

    val url: String? get() = linkId?.let { id -> service.baseUrl?.let { recipeLinkUrl(it, id) } }

    fun open(recipe: SavedRecipe) {
        target = recipe
        linkId = null
        error = null
        updated = false
        askingAccount = false
        confirmingStop = false
        if (!service.signedIn) return
        loading = true
        scope.launch {
            attempt { linkId = service.mine()[recipe.id] }
            loading = false
        }
    }

    fun close() {
        target = null
        askingAccount = false
        confirmingStop = false
    }

    /** False when an account has to come first, which this also asks for. */
    fun readyToPublish(): Boolean {
        if (service.signedIn) return true
        askingAccount = true
        return false
    }

    fun dismissAccount() {
        askingAccount = false
    }

    /** What the exporter produced, after the same confirmation and choice of parts as a file. */
    fun publish(content: ByteArray) {
        val recipe = target ?: return
        val existing = linkId
        working = true
        error = null
        updated = false
        scope.launch {
            attempt {
                if (existing == null) {
                    linkId = service.create(recipe.id, content)
                } else {
                    service.update(existing, content)
                    updated = true
                }
            }
            working = false
        }
    }

    fun askToStop() {
        confirmingStop = true
    }

    fun dismissStop() {
        confirmingStop = false
    }

    fun stop() {
        val id = linkId ?: return
        confirmingStop = false
        working = true
        error = null
        updated = false
        scope.launch {
            attempt {
                service.revoke(id)
                linkId = null
            }
            working = false
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RecipeLinkException) {
            error = failure.error
        }
    }
}

@Composable
internal fun rememberRecipeLinkController(): RecipeLinkController {
    val service = LocalRecipeLinks.current
    val scope = rememberCoroutineScope()
    return remember(service, scope) { RecipeLinkController(service, scope) }
}

internal fun recipeLinkErrorText(error: RecipeLinkError): StringResource = when (error) {
    RecipeLinkError.LIMIT -> Res.string.editor_recipe_link_limit
    RecipeLinkError.TOO_LARGE -> Res.string.editor_recipe_link_too_large
    RecipeLinkError.GONE -> Res.string.editor_recipe_link_gone
    RecipeLinkError.OFFLINE -> Res.string.editor_recipe_link_offline
    RecipeLinkError.NO_ACCOUNT, RecipeLinkError.FAILED -> Res.string.editor_recipe_link_failed
}

/**
 * «Share» for one recipe, in the layout of «Show your edit»: the image as it will come out, and what can be done
 * with it. [onPublish] goes through the exporter, so that an imported recipe asks first and what is tied to one
 * photo stays off unless it is ticked. [onSendFile] is null where the system has no share menu.
 */
@Composable
internal fun RecipeLinkDialog(
    links: RecipeLinkController,
    onPublish: () -> Unit,
    onSendFile: (() -> Unit)?,
    onSendLink: ((String) -> Unit)?,
    onOpenAccount: () -> Unit,
    onSaveImage: (fileName: String, bytes: ByteArray) -> Unit = { _, _ -> },
    onShareImage: ((fileName: String, bytes: ByteArray) -> Unit)? = null,
    sampleIndex: Int = 0,
    onSampleIndex: (Int) -> Unit = {},
) {
    val recipe = links.target ?: return
    val currentOpenAccount by rememberUpdatedState(onOpenAccount)
    val url = links.url
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val gallery = rememberRecipeGalleryController()
    LaunchedEffect(recipe.id) { gallery.check() }
    val kicker = stringResource(Res.string.editor_recipe_card_kicker)
    val hint = stringResource(if (url != null) Res.string.editor_recipe_card_scan else Res.string.editor_recipe_card_pending)

    // The card is made on one of the app's samples or on a photo of the person's own, which never leaves the
    // device unless they put it on the link's page. What is tied to one photo says nothing on another.
    var source by remember(recipe.id) { mutableStateOf(GallerySource.SAMPLE) }
    var own by remember(recipe.id) { mutableStateOf<ImageBitmap?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    val pick = rememberGalleryPhotoPicker { picked ->
        if (picked == null) {
            unreadable = own == null
        } else {
            own = picked
            unreadable = false
            source = GallerySource.OWN
        }
    }
    var before by remember(recipe.id) { mutableStateOf<ImageBitmap?>(null) }
    var photo by remember(recipe.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(recipe, sampleIndex, source, own) {
        before = null
        photo = null
        val base = when (source) {
            GallerySource.SAMPLE -> Res.readBytes(recipeSamplePath(sampleIndex)).let { bytes ->
                withContext(Dispatchers.Default) { bytes.decodeToImageBitmap().fittedTo(RecipeGalleryLimits.PHOTO_SIDE) }
            }
            GallerySource.OWN -> own ?: return@LaunchedEffect
        }
        before = base
        photo = withContext(Dispatchers.Default) { renderLook(recipe, base) }
    }
    LaunchedEffect(links.linkId, gallery.supporter) { gallery.loadImages(links.linkId) }
    // A person who stopped being a supporter goes back to the samples.
    LaunchedEffect(gallery.supporter) { if (!gallery.supporter) source = GallerySource.SAMPLE }
    var card by remember(recipe.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photo, url, hint) {
        val base = photo ?: return@LaunchedEffect
        card = withContext(Dispatchers.Default) {
            paintRecipeCard(base, RecipeCardTexts(recipe.name, kicker, hint), url?.let(::RecipeQrCode), measurer)
        }
    }

    fun sendImages() {
        val first = before ?: return
        val second = photo ?: return
        val id = links.linkId ?: return
        scope.launch {
            val encoded = try {
                withContext(Dispatchers.Default) { first.encodeForGallery() to second.encodeForGallery() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (encoded == null) gallery.failPreparing() else gallery.setImages(id, encoded.first, encoded.second)
        }
    }

    /** The file is made only when asked for: a PNG of this size takes a moment. */
    fun deliver(send: (String, ByteArray) -> Unit) {
        val image = card ?: return
        scope.launch {
            val bytes = withContext(Dispatchers.Default) { image.encodeToPng() }
            send(imagoFileName(recipe.name, kicker, "png"), bytes)
        }
    }

    Dialog(
        onDismissRequest = links::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = ImagoUi.colors.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth > maxHeight && maxWidth >= 600.dp
                val previewHeight = maxHeight * 0.55f
                val controls: @Composable ColumnScope.() -> Unit = {
                    LinkControls(
                        links = links,
                        url = url,
                        onPublish = onPublish,
                        onSendFile = onSendFile,
                        onSendLink = onSendLink,
                        canUseImage = card != null,
                        onSaveImage = { deliver(onSaveImage) },
                        onShareImage = onShareImage?.let { share -> { deliver(share) } },
                        gallery = gallery,
                        ownPhoto = source == GallerySource.OWN,
                        canSendImages = before != null && photo != null,
                        onSendImages = ::sendImages,
                    )
                }
                val preview: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                        RecipeCardPreview(card.takeUnless { pick.loading }, Modifier.weight(1f).fillMaxWidth())
                        CardPhotoChoice(gallery.supporter, source, { source = it }, own != null, pick, unreadable, sampleIndex, onSampleIndex)
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    LinkTopBar(onClose = links::close)
                    if (wide) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                        ) {
                            preview(Modifier.fillMaxHeight().weight(1f))
                            Column(
                                modifier = Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                content = controls,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        ) {
                            preview(Modifier.fillMaxWidth().height(previewHeight))
                            Spacer(Modifier.height(ImagoSpacing.Md))
                            controls()
                            Spacer(Modifier.height(ImagoSpacing.Xxl))
                        }
                    }
                }
            }
            pick.Overlay()
        }
    }
    val linked = links.linkId
    if (gallery.composing && linked != null) {
        RecipeGalleryDialog(gallery, recipe, linked, sampleIndex, onSampleIndex)
    }
    if (links.askingAccount) {
        AlertDialog(
            onDismissRequest = links::dismissAccount,
            text = { Text(stringResource(Res.string.editor_recipe_link_needs_account)) },
            confirmButton = {
                TextButton(onClick = { links.close(); currentOpenAccount() }) { Text(stringResource(Res.string.editor_recipe_link_sign_in)) }
            },
            dismissButton = { TextButton(onClick = links::dismissAccount) { Text(stringResource(Res.string.editor_cancel)) } },
        )
    }
    if (links.confirmingStop) {
        AlertDialog(
            onDismissRequest = links::dismissStop,
            title = { Text(stringResource(Res.string.editor_recipe_link_stop_title)) },
            text = { Text(stringResource(Res.string.editor_recipe_link_stop_text)) },
            confirmButton = {
                TextButton(onClick = { links.dismissStop(); links.linkId?.let { id -> scope.launch { gallery.clearImagesQuietly(id); links.stop() } } }) {
                    Text(stringResource(Res.string.editor_recipe_link_stop), color = ImagoUi.colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = links::dismissStop) { Text(stringResource(Res.string.editor_cancel)) } },
        )
    }
}

@Composable
private fun LinkTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.editor_cancel))
        }
        Text(stringResource(Res.string.editor_recipe_link_title), style = MaterialTheme.typography.titleLarge, color = ImagoUi.colors.text)
    }
}

@Composable
private fun RecipeCardPreview(card: ImageBitmap?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.fillMaxHeight().aspectRatio(RECIPE_CARD_WIDTH.toFloat() / RECIPE_CARD_HEIGHT, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(16.dp)).background(ImagoUi.colors.workspace),
            contentAlignment = Alignment.Center,
        ) {
            if (card == null) {
                CircularProgressIndicator(color = ImagoUi.colors.secondaryText)
            } else {
                Image(
                    bitmap = card,
                    contentDescription = stringResource(Res.string.editor_recipe_card_description),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LinkControls(
    links: RecipeLinkController,
    url: String?,
    onPublish: () -> Unit,
    onSendFile: (() -> Unit)?,
    onSendLink: ((String) -> Unit)?,
    canUseImage: Boolean,
    onSaveImage: () -> Unit,
    onShareImage: (() -> Unit)?,
    gallery: RecipeGalleryController? = null,
    ownPhoto: Boolean = false,
    canSendImages: Boolean = false,
    onSendImages: () -> Unit = {},
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(url) { mutableStateOf(false) }
    val idle = !links.loading && !links.working
    val note = MaterialTheme.typography.bodySmall
    val body = MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
        when {
            !idle -> Box(Modifier.fillMaxWidth().padding(ImagoSpacing.Md), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ImagoUi.colors.emphasis)
            }
            url != null -> {
                LinkField(url, onCopy = { clipboard.setText(AnnotatedString(url)); copied = true })
                if (onShareImage != null) {
                    Button(onClick = onShareImage, enabled = canUseImage, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.editor_recipe_link_share_image))
                    }
                    OutlinedButton(onClick = onSaveImage, enabled = canUseImage, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.editor_recipe_link_save_image))
                    }
                } else {
                    Button(onClick = onSaveImage, enabled = canUseImage, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.editor_recipe_link_save_image))
                    }
                }
                onSendLink?.let { send ->
                    OutlinedButton(onClick = { send(url) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.editor_recipe_link_send))
                    }
                }
                Text(stringResource(Res.string.editor_recipe_link_snapshot), style = note, color = ImagoUi.colors.secondaryText)
                // The gallery is the supporters': to anyone else it is not there at all.
                val linked = links.linkId
                if (gallery != null && gallery.supporter && linked != null) {
                    if (linked in gallery.published) {
                        Text(stringResource(Res.string.editor_gallery_in), style = body, color = ImagoUi.colors.secondaryText)
                        gallery.localGalleryUrl()?.let { address ->
                            OutlinedButton(onClick = { uriHandler.openUri(address) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.editor_gallery_view))
                            }
                        }
                    } else {
                        OutlinedButton(onClick = gallery::start, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.editor_gallery_publish))
                        }
                    }
                    PageImages(gallery, ownPhoto, canSendImages, onSendImages) { gallery.clearImages(linked) }
                }
            }
            else -> {
                Text(stringResource(Res.string.editor_recipe_link_intro), style = body, color = ImagoUi.colors.secondaryText)
                Button(onClick = { if (links.readyToPublish()) onPublish() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.editor_recipe_link_create))
                }
            }
        }
        links.error?.let { error -> Text(stringResource(recipeLinkErrorText(error)), style = body, color = ImagoUi.colors.error) }
        if (links.updated) Text(stringResource(Res.string.editor_recipe_link_updated), style = body, color = ImagoUi.colors.secondaryText)
        if (copied) Text(stringResource(Res.string.editor_recipe_link_copied), style = body, color = ImagoUi.colors.secondaryText)
        onSendFile?.let { send ->
            OutlinedButton(onClick = { send(); links.close() }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.editor_recipe_link_send_file))
            }
        }
        if (url != null && idle) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (links.readyToPublish()) onPublish() }) { Text(stringResource(Res.string.editor_recipe_link_update)) }
                TextButton(onClick = links::askToStop) {
                    Text(stringResource(Res.string.editor_recipe_link_stop), color = ImagoUi.colors.danger)
                }
            }
        }
    }
}

@Composable
private fun LinkField(url: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ImagoUi.colors.elevated)
            .padding(start = ImagoSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoUi.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(Res.string.editor_recipe_link_copy), tint = ImagoUi.colors.secondaryText)
        }
    }
}

/** The before and the after on the link's page: supporters only, and the page says when they were hidden. */
@Composable
private fun PageImages(
    gallery: RecipeGalleryController,
    ownPhoto: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onRemove: () -> Unit,
) {
    val note = MaterialTheme.typography.bodySmall
    val body = MaterialTheme.typography.bodyMedium
    val shown = gallery.linkImages != RecipeLinkImages.NONE
    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
        Text(stringResource(Res.string.editor_recipe_link_images_intro), style = note, color = ImagoUi.colors.secondaryText)
        if (ownPhoto) Text(stringResource(Res.string.editor_recipe_link_images_own), style = note, color = ImagoUi.colors.secondaryText)
        when (gallery.linkImages) {
            RecipeLinkImages.SHOWN -> Text(stringResource(Res.string.editor_recipe_link_images_on), style = body, color = ImagoUi.colors.secondaryText)
            RecipeLinkImages.HIDDEN -> Text(stringResource(Res.string.editor_recipe_link_images_hidden), style = body, color = ImagoUi.colors.error)
            RecipeLinkImages.NONE -> Unit
        }
        gallery.error?.let { error -> Text(stringResource(recipeGalleryErrorText(error)), style = body, color = ImagoUi.colors.error) }
        if (gallery.imagesWorking) {
            Box(Modifier.fillMaxWidth().padding(ImagoSpacing.Sm), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ImagoUi.colors.emphasis)
            }
        } else {
            OutlinedButton(onClick = onSend, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (shown) Res.string.editor_recipe_link_images_update else Res.string.editor_recipe_link_images_add))
            }
            if (shown) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(Res.string.editor_recipe_link_images_remove), color = ImagoUi.colors.danger)
                }
            }
        }
    }
}

/** A sample, or a photo of the person's own, for the card: the second is the supporters', who are also the ones who can put it on the link's page. */
@Composable
private fun CardPhotoChoice(
    supporter: Boolean,
    source: GallerySource,
    onSource: (GallerySource) -> Unit,
    hasOwn: Boolean,
    pick: () -> Unit,
    unreadable: Boolean,
    sampleIndex: Int,
    onSampleIndex: (Int) -> Unit,
) {
    if (!supporter) {
        SampleSwitcher(sampleIndex, onSampleIndex)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
            SegmentedButton(
                selected = source == GallerySource.SAMPLE,
                onClick = { onSource(GallerySource.SAMPLE) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text(stringResource(Res.string.editor_gallery_source_sample)) }
            SegmentedButton(
                selected = source == GallerySource.OWN,
                onClick = { if (hasOwn) onSource(GallerySource.OWN) else pick() },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text(stringResource(Res.string.editor_gallery_source_own)) }
        }
        when (source) {
            GallerySource.SAMPLE -> SampleSwitcher(sampleIndex, onSampleIndex)
            GallerySource.OWN -> OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                Text(stringResource(Res.string.editor_gallery_choose_photo))
            }
        }
        if (unreadable) Text(stringResource(Res.string.editor_gallery_photo_failed), style = MaterialTheme.typography.bodySmall, color = ImagoUi.colors.error)
    }
}
