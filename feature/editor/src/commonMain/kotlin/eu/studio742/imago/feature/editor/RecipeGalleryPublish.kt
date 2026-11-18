package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.interchange.NoRecipeGallery
import eu.studio742.imago.core.interchange.PHOTO_BOUND_PARTS
import eu.studio742.imago.core.interchange.RecipeGalleryError
import eu.studio742.imago.core.interchange.RecipeGalleryException
import eu.studio742.imago.core.interchange.RecipeGalleryLimits
import eu.studio742.imago.core.interchange.RecipeGalleryService
import eu.studio742.imago.core.interchange.RecipeLinkImages
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.withPartsFrom
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.core.render.softwareBitmap
import eu.studio742.imago.core.render.toImageBitmap
import eu.studio742.imago.core.render.toPixelBuffer
import eu.studio742.imago.feature.library.AssetUiModel
import eu.studio742.imago.feature.library.LibraryPickerRoute
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
val LocalRecipeGallery = staticCompositionLocalOf<RecipeGalleryService> { NoRecipeGallery }

/**
 * Whether a recipe can go into the supporters' gallery, and sending it there. Nothing of the gallery
 * shows to someone the server does not answer for as a supporter.
 */
@Stable
internal class RecipeGalleryController(
    private val service: RecipeGalleryService,
    private val scope: CoroutineScope,
) {
    var supporter by mutableStateOf(false)
        private set
    var published by mutableStateOf<Set<String>>(emptySet())
        private set
    var composing by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<RecipeGalleryError?>(null)
        private set
    var done by mutableStateOf(false)
        private set
    var linkImages by mutableStateOf(RecipeLinkImages.NONE)
        private set
    var imagesWorking by mutableStateOf(false)
        private set
    var imagesDone by mutableStateOf(false)
        private set

    fun galleryUrl(portuguese: Boolean): String? = service.galleryUrl(portuguese)

    /** Asked when the share dialog opens; the answers only add an option, so a doubt leaves it out. */
    fun check() {
        supporter = false
        published = emptySet()
        if (service.galleryUrl(portuguese = false) == null) return
        scope.launch {
            supporter = service.isSupporter()
            if (supporter) published = service.publishedLinks()
        }
    }

    /** What the link's page shows now; asked again after every change, because the server can hide them on its own. */
    fun loadImages(linkId: String?) {
        linkImages = RecipeLinkImages.NONE
        imagesDone = false
        if (linkId == null || !supporter) return
        scope.launch { linkImages = service.linkImages(linkId) }
    }

    fun setImages(linkId: String, before: ByteArray, after: ByteArray) {
        imagesWorking = true
        imagesDone = false
        error = null
        scope.launch {
            try {
                service.setLinkImages(linkId, before, after)
                linkImages = RecipeLinkImages.SHOWN
                imagesDone = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RecipeGalleryException) {
                error = failure.error
            } finally {
                imagesWorking = false
            }
        }
    }

    fun clearImages(linkId: String) {
        imagesWorking = true
        imagesDone = false
        error = null
        scope.launch {
            try {
                service.clearLinkImages(linkId)
                linkImages = RecipeLinkImages.NONE
            } finally {
                imagesWorking = false
            }
        }
    }

    /** Before a link is revoked: its images would stay in the bucket otherwise. */
    suspend fun clearImagesQuietly(linkId: String) {
        if (supporter && linkImages != RecipeLinkImages.NONE) service.clearLinkImages(linkId)
    }

    fun start() {
        composing = true
        error = null
        done = false
    }

    fun dismiss() {
        composing = false
    }

    fun publish(linkId: String, title: String, authorName: String, before: ByteArray, after: ByteArray) {
        working = true
        error = null
        scope.launch {
            try {
                service.publish(linkId, title, authorName, before, after)
                published = published + linkId
                done = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RecipeGalleryException) {
                error = failure.error
                if (failure.error == RecipeGalleryError.ALREADY_PUBLISHED) published = published + linkId
            } finally {
                working = false
            }
        }
    }

    /** A photo that could not be read or prepared, before anything was sent. */
    fun failPreparing() {
        error = RecipeGalleryError.FAILED
    }
}

@Composable
internal fun rememberRecipeGalleryController(): RecipeGalleryController {
    val service = LocalRecipeGallery.current
    val scope = rememberCoroutineScope()
    return remember(service, scope) { RecipeGalleryController(service, scope) }
}

internal fun recipeGalleryErrorText(error: RecipeGalleryError): StringResource = when (error) {
    RecipeGalleryError.NOT_SUPPORTER -> Res.string.editor_gallery_error_not_supporter
    RecipeGalleryError.IMPORTED -> Res.string.editor_gallery_error_imported
    RecipeGalleryError.ALREADY_PUBLISHED -> Res.string.editor_gallery_error_already
    RecipeGalleryError.INVALID -> Res.string.editor_gallery_invalid_fields
    RecipeGalleryError.LIMIT -> Res.string.editor_gallery_error_limit
    RecipeGalleryError.LINK_GONE -> Res.string.editor_gallery_error_link_gone
    RecipeGalleryError.IMAGE_UPLOAD -> Res.string.editor_gallery_error_image
    RecipeGalleryError.IMAGES_BLOCKED -> Res.string.editor_gallery_error_images_blocked
    RecipeGalleryError.OFFLINE -> Res.string.editor_recipe_link_offline
    RecipeGalleryError.FAILED -> Res.string.editor_recipe_link_failed
}

/** The gallery's page in the language the app is in. */
@Composable
internal fun RecipeGalleryController.localGalleryUrl(): String? = galleryUrl(portuguese = Locale.current.language == "pt")

/** The look of [recipe] on [photo]: what is tied to one photo says nothing on another. */
internal fun renderLook(recipe: SavedRecipe, photo: ImageBitmap): ImageBitmap {
    val source = PixelBuffer(photo.width, photo.height, IntArray(photo.width * photo.height).also { photo.readPixels(it) })
    val look = EditRecipe(assetId = "", originalChecksum = "", processVersion = recipe.recipe.processVersion, createdAt = "", updatedAt = "")
        .withPartsFrom(recipe.recipe, recipe.appliedParts - PHOTO_BOUND_PARTS)
    return RecipePixels.render(source, look).toImageBitmap()
}

/** [photo] no larger than [side] on its longer side, for sending and for the renderer. */
internal fun ImageBitmap.fittedTo(side: Int): ImageBitmap {
    val scale = minOf(1f, side.toFloat() / maxOf(width, height))
    if (scale >= 1f) return this
    val target = IntSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
    val out = ImageBitmap(target.width, target.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(target.width.toFloat(), target.height.toFloat())) {
        drawImage(this@fittedTo, dstOffset = IntOffset.Zero, dstSize = target, filterQuality = FilterQuality.High)
    }
    return out
}

/** As JPEG under the bucket's limit, lowering the quality if it has to: re-encoding also drops the metadata. */
internal fun ImageBitmap.encodeForGallery(): ByteArray =
    listOf(88, 80, 70, 60, 50).firstNotNullOfOrNull { quality -> encodeToJpeg(quality).takeIf { it.size <= RecipeGalleryLimits.PHOTO_MAX_BYTES } }
        ?: error("The photo does not fit the gallery's limit")

/**
 * A photo of the person's own, chosen in IMAGO's library — the same picker the composer uses — and not
 * in the system's: the photos someone edits with a recipe are the ones in here, server ones included.
 *
 * Invoking it opens the picker; [Overlay] has to be composed on top of the dialog that asks, since a
 * dialog's window covers anything drawn outside it.
 */
@Stable
internal class GalleryPhotoPicker(
    private val scope: CoroutineScope,
    private val load: suspend (AssetUiModel) -> ImageBitmap?,
    private val onPicked: () -> (ImageBitmap?) -> Unit,
) : () -> Unit {
    var open by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set

    override fun invoke() {
        if (!loading) open = true
    }

    fun cancel() {
        open = false
    }

    fun choose(asset: AssetUiModel) {
        open = false
        loading = true
        scope.launch {
            val photo = try { load(asset) } finally { loading = false }
            onPicked()(photo)
        }
    }
}

/** [onPicked] gets the photo oriented, sRGB and no larger than the gallery's side, or null when it could not be read. */
@Composable
internal fun rememberGalleryPhotoPicker(onPicked: (ImageBitmap?) -> Unit): GalleryPhotoPicker {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    return remember(context, scope) { GalleryPhotoPicker(scope, { loadGalleryPhoto(context, it) }, { picked }) }
}

/** The library over the whole dialog, photos only, one at a time: tapping one decides. */
@Composable
internal fun GalleryPhotoPicker.Overlay() {
    if (!open) return
    Surface(
        color = ImagoUi.colors.background,
        modifier = Modifier
            .fillMaxSize()
            // Opaque is not enough: the touches falling outside the grid would reach the dialog underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LibraryPickerRoute(
            title = stringResource(Res.string.editor_gallery_pick_title),
            allowsVideo = false,
            multiple = false,
            onConfirm = { chosen -> chosen.firstOrNull()?.let(::choose) ?: cancel() },
            onCancel = ::cancel,
        )
    }
}

/**
 * The photo as it came from the camera, not with the recipe it may have in IMAGO: the look being shared is
 * laid over it. Decoding and drawing it again keeps none of the file's metadata.
 */
private suspend fun loadGalleryPhoto(context: PlatformContext, asset: AssetUiModel): ImageBitmap? = try {
    val result = SingletonImageLoader.get(context).execute(
        ImageRequest.Builder(context)
            .data(asset.previewUrl)
            .libraryAuth(asset.apiKey)
            .size(RecipeGalleryLimits.PHOTO_SIDE)
            .softwareBitmap()
            .memoryCachePolicy(CachePolicy.READ_ONLY)
            .build(),
    )
    when (result) {
        is SuccessResult -> withContext(Dispatchers.Default) {
            result.image.toBitmap().toPixelBuffer().toImageBitmap().fittedTo(RecipeGalleryLimits.PHOTO_SIDE)
        }
        is ErrorResult -> {
            LocalDiagnostics.failure(DiagnosticArea.IMAGE, result.throwable, DiagnosticOperation.GALLERY)
            null
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    LocalDiagnostics.failure(DiagnosticArea.IMAGE, error, DiagnosticOperation.GALLERY)
    null
}

internal enum class GallerySource { SAMPLE, OWN }

/**
 * Publishing [recipe]'s link in the gallery: the before and the after on one of the app's sample photos
 * or on a photo of the person's own, the title and the name it shows under.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeGalleryDialog(
    gallery: RecipeGalleryController,
    recipe: SavedRecipe,
    linkId: String,
    sampleIndex: Int,
    onSampleIndex: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val galleryUrl = gallery.localGalleryUrl()
    var source by remember { mutableStateOf(GallerySource.SAMPLE) }
    var own by remember { mutableStateOf<ImageBitmap?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var title by remember(recipe.id) { mutableStateOf(recipe.name.take(RecipeGalleryLimits.TITLE.last)) }
    var author by remember { mutableStateOf("") }
    var before by remember { mutableStateOf<ImageBitmap?>(null) }
    var after by remember { mutableStateOf<ImageBitmap?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val pick = rememberGalleryPhotoPicker { picked ->
        if (picked == null) {
            unreadable = own == null
        } else {
            own = picked
            unreadable = false
            source = GallerySource.OWN
        }
    }

    LaunchedEffect(recipe, source, sampleIndex, own) {
        before = null
        after = null
        val photo = when (source) {
            GallerySource.SAMPLE -> Res.readBytes(recipeSamplePath(sampleIndex)).let { bytes ->
                withContext(Dispatchers.Default) { bytes.decodeToImageBitmap().fittedTo(RecipeGalleryLimits.PHOTO_SIDE) }
            }
            GallerySource.OWN -> own ?: return@LaunchedEffect
        }
        before = photo
        after = withContext(Dispatchers.Default) { renderLook(recipe, photo) }
    }

    val fieldsValid = title.trim().length in RecipeGalleryLimits.TITLE && author.trim().length in RecipeGalleryLimits.AUTHOR
    val busy = gallery.working || preparing || pick.loading

    fun send() {
        val first = before ?: return
        val second = after ?: return
        preparing = true
        scope.launch {
            val photos = try {
                withContext(Dispatchers.Default) { first.encodeForGallery() to second.encodeForGallery() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                preparing = false
            }
            if (photos == null) gallery.failPreparing() else gallery.publish(linkId, title, author, photos.first, photos.second)
        }
    }

    Dialog(
        onDismissRequest = gallery::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = ImagoUi.colors.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = gallery::dismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.editor_cancel))
                    }
                    Text(stringResource(Res.string.editor_gallery_publish), style = MaterialTheme.typography.titleLarge, color = ImagoUi.colors.text)
                }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                        val body = MaterialTheme.typography.bodyMedium
                        if (gallery.done) {
                            Text(stringResource(Res.string.editor_gallery_done), style = MaterialTheme.typography.titleMedium, color = ImagoUi.colors.text)
                            galleryUrl?.let { url ->
                                Button(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(Res.string.editor_gallery_view))
                                }
                            }
                            OutlinedButton(onClick = gallery::dismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.editor_gallery_close))
                            }
                            return@Column
                        }
                        Text(stringResource(Res.string.editor_gallery_intro), style = body, color = ImagoUi.colors.secondaryText)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = source == GallerySource.SAMPLE,
                                onClick = { source = GallerySource.SAMPLE },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text(stringResource(Res.string.editor_gallery_source_sample)) }
                            SegmentedButton(
                                selected = source == GallerySource.OWN,
                                onClick = { if (own == null) pick() else source = GallerySource.OWN },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text(stringResource(Res.string.editor_gallery_source_own)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                            GalleryPhoto(before.takeUnless { pick.loading }, stringResource(Res.string.editor_gallery_before), Modifier.weight(1f))
                            GalleryPhoto(after.takeUnless { pick.loading }, stringResource(Res.string.editor_gallery_after), Modifier.weight(1f))
                        }
                        when (source) {
                            GallerySource.SAMPLE -> SampleSwitcher(sampleIndex, onSampleIndex)
                            GallerySource.OWN -> {
                                OutlinedButton(onClick = pick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(Res.string.editor_gallery_choose_photo))
                                }
                                Text(stringResource(Res.string.editor_gallery_own_note), style = MaterialTheme.typography.bodySmall, color = ImagoUi.colors.secondaryText)
                            }
                        }
                        if (unreadable) Text(stringResource(Res.string.editor_gallery_photo_failed), style = body, color = ImagoUi.colors.error)
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(RecipeGalleryLimits.TITLE.last) },
                            label = { Text(stringResource(Res.string.editor_gallery_field_title)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = author,
                            onValueChange = { author = it.take(RecipeGalleryLimits.AUTHOR.last) },
                            label = { Text(stringResource(Res.string.editor_gallery_field_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        gallery.error?.let { error -> Text(stringResource(recipeGalleryErrorText(error)), style = body, color = ImagoUi.colors.error) }
                        if (busy) {
                            Box(Modifier.fillMaxWidth().padding(ImagoSpacing.Md), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = ImagoUi.colors.emphasis)
                            }
                        } else {
                            Button(onClick = ::send, enabled = fieldsValid && before != null && after != null, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.editor_gallery_send))
                            }
                        }
                        Spacer(Modifier.height(ImagoSpacing.Xxl))
                    }
                }
            }
            pick.Overlay()
        }
    }
}

@Composable
private fun GalleryPhoto(photo: ImageBitmap?, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(12.dp)).background(ImagoUi.colors.workspace),
            contentAlignment = Alignment.Center,
        ) {
            if (photo == null) {
                CircularProgressIndicator(color = ImagoUi.colors.secondaryText)
            } else {
                Image(bitmap = photo, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = ImagoUi.colors.secondaryText)
    }
}
