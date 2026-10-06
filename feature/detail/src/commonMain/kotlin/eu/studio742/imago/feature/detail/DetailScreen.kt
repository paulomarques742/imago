@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.detail

import eu.studio742.imago.core.designsystem.i18n.LocalAppLocale
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.detail.resources.*
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.core.render.withRecipe
import coil3.request.crossfade
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import eu.studio742.imago.core.designsystem.GlassIconButton
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoMotion
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.PhotoTransform
import eu.studio742.imago.core.render.drag
import eu.studio742.imago.core.render.pinch
import eu.studio742.imago.core.render.settle
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val FILMSTRIP_THUMB = 64.dp

/**
 * The width, in pixels, at which the placeholder thumbnail is requested.
 *
 * Fixed, and not the screen's, for two reasons. It is what makes the wait short — a recipe applied at
 * 360 px costs a fraction of what it costs applied to the whole preview — and, being always the same,
 * every page of the pager shares the same entry in Coil's cache.
 */
private const val PLACEHOLDER_PIXELS = 360

/** The size of the double-tap heart, over the photo. */
private val BURST_HEART = 108.dp

/** How long the heart stays still before going away. */
private const val HEART_HOLD_MS = 380L

@Composable
fun DetailRoute(
    assets: List<DetailAsset>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onBack: () -> Unit,
    onEdit: (DetailAsset) -> Unit,
    /** Sends this photo to a composition, new or existing. */
    onAddToComposition: () -> Unit,
    onDeleted: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = detailViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val asset = assets.getOrNull(selectedIndex) ?: return
    LaunchedEffect(asset.id) { viewModel.open(asset) }
    BackHandler(onBack = onBack)
    DetailScreen(
        modifier = modifier,
        assets = assets,
        selectedIndex = selectedIndex,
        state = state,
        onSelectIndex = onSelectIndex,
        onBack = onBack,
        onEdit = { onEdit(asset) },
        onAddToComposition = onAddToComposition,
        onToggleFavorite = viewModel::toggleFavorite,
        onFavorite = viewModel::favorite,
        onDelete = { viewModel.delete(asset.id) { onDeleted(asset.id) } },
        onShare = viewModel::share,
        onConsumeMessage = viewModel::consumeMessage,
    )
}

@Composable
private fun DetailScreen(
    modifier: Modifier,
    assets: List<DetailAsset>,
    selectedIndex: Int,
    state: DetailUiState,
    onSelectIndex: (Int) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddToComposition: () -> Unit,
    onToggleFavorite: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onShare: (DetailAsset, (java.io.File, String) -> Unit) -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val shareFile = rememberShareFile()
    val asset = assets.getOrNull(selectedIndex) ?: return
    val pagerState = rememberPagerState(initialPage = selectedIndex) { assets.size }
    val filmstripState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showInfo by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // Zoomed in, the photo gets the whole finger: the drag becomes its own and not the pager's.
    // Without this, pulling a zoomed photo sideways jumped to the next one.
    var zoomed by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        val message = state.error ?: state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolveNow())
        onConsumeMessage()
    }
    LaunchedEffect(selectedIndex) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex.coerceIn(0, assets.lastIndex))
        }
        filmstripState.animateScrollToItem(selectedIndex.coerceAtLeast(0))
    }
    LaunchedEffect(pagerState.settledPage) {
        // Every photo starts fitted; the one left behind drops the zoom it had.
        zoomed = false
        if (pagerState.settledPage != selectedIndex) onSelectIndex(pagerState.settledPage)
    }
    // A single player, the settled page's, created here and not inside the page: the controls live in
    // the bottom strip, outside the pager, and need to reach it.
    val settledAsset = assets.getOrNull(pagerState.settledPage)
    val playback = rememberVideoPlayback(
        url = settledAsset?.takeIf { it.isVideo }?.videoUrl,
        apiKey = settledAsset?.apiKey.orEmpty(),
    )

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The photo goes to the edges; only the controls step back to the safe areas.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                key = { page -> assets[page].id },
                beyondViewportPageCount = 1,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val isCurrent = page == pagerState.settledPage
                DetailPage(
                    asset = assets[page],
                    // The open page uses the recipe just read again; the neighbours keep the one that
                    // came from the library, which is what their thumbnails already showed.
                    recipe = if (assets[page].id == state.assetId) state.recipe else assets[page].recipe,
                    // Only the settled page gets a player: one ExoPlayer per neighbouring page was a
                    // decoder open for every video the finger passes by.
                    isCurrent = isCurrent,
                    playback = if (isCurrent) playback else null,
                    onZoomedChange = { if (isCurrent) zoomed = it },
                    onFavorite = onFavorite,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.detail_back_to_library),
                    onClick = onBack,
                )
                Spacer(Modifier.weight(1f))
                GlassIconButton(
                    icon = if (state.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (state.isFavorite) stringResource(Res.string.detail_unfavorite) else stringResource(Res.string.detail_favorite),
                    onClick = onToggleFavorite,
                )
                Box(Modifier.padding(start = ImagoSpacing.Sm)) {
                    GlassIconButton(
                        icon = Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(Res.string.detail_more_options),
                        onClick = { showMenu = true },
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.detail_info)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showInfo = true
                            },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // The gradient is what keeps the text readable whatever the photo; without it, a
                    // light sky swallowed the date and the EXIF line.
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f), Color.Black),
                        ),
                    )
                    .navigationBarsPadding(),
            ) {
                // Immich's caption, when there is one, is the best title the photo has; otherwise the
                // file name stays. It is not editable here: Immich does not expose renaming, and
                // editing the caption would be promising one thing by doing another.
                Text(
                    text = state.description?.takeIf(String::isNotBlank) ?: asset.fileName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = ImagoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = ImagoSpacing.Lg,
                        end = ImagoSpacing.Lg,
                        top = ImagoSpacing.Xxxl,
                    ),
                )
                Text(
                    text = formatTakenAt(asset.date.ifBlank { asset.fileCreatedAt }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Xs),
                )
                ExifStrip(
                    exif = state.exif,
                    isLoading = state.isLoading,
                    modifier = Modifier.padding(top = ImagoSpacing.Sm, bottom = ImagoSpacing.Md),
                )
                // In the middle of a swipe, the player is still the page's we left.
                if (playback != null && settledAsset?.id == asset.id) {
                    VideoControls(playback, Modifier.padding(bottom = ImagoSpacing.Sm))
                }
                Filmstrip(
                    assets = assets,
                    selectedIndex = selectedIndex,
                    listState = filmstripState,
                    onSelect = onSelectIndex,
                )
                DetailActionBar(
                    // The editor works on bitmaps: a video has nothing to do there, and a button that
                    // opened a useless editor would be worse than not being there.
                    canEdit = !asset.isVideo,
                    onShare = { onShare(asset, shareFile) },
                    onEdit = onEdit,
                    onCompose = onAddToComposition,
                    onInfo = { showInfo = true },
                    onDelete = { showDeleteConfirmation = true },
                )
            }

            if (state.isBusy) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ImagoColors.Ivory)
                        state.busyLabel?.let {
                            Text(
                                it.resolve(),
                                color = ImagoColors.TextSecondary,
                                modifier = Modifier.padding(top = ImagoSpacing.Md),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        InfoDialog(asset = asset, exif = state.exif, onDismiss = { showInfo = false })
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(if (asset.isVideo) stringResource(Res.string.detail_delete_video_question) else stringResource(Res.string.detail_delete_photo_question)) },
            text = {
                Text(
                    if (eu.studio742.imago.core.model.AssetReference.parse(asset.id).libraryId == eu.studio742.imago.core.model.DEVICE_LIBRARY_ID)
                        stringResource(Res.string.detail_delete_device_body, asset.fileName)
                    else stringResource(Res.string.detail_delete_server_body, asset.fileName),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                ) { Text(stringResource(Res.string.detail_delete), color = ImagoColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text(stringResource(Res.string.detail_cancel)) }
            },
        )
    }
}

/**
 * A page of the pager: the processed photo, or the video.
 *
 * While it is not the settled page, the video is just the thumbnail with the triangle on top —
 * enough to know what is coming without opening a decoder to confirm it.
 */
@Composable
private fun DetailPage(
    asset: DetailAsset,
    recipe: EditRecipe?,
    isCurrent: Boolean,
    playback: VideoPlayback?,
    onZoomedChange: (Boolean) -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (asset.isVideo && isCurrent && asset.videoUrl != null) {
        Box(modifier.background(Color.Black)) {
            DetailVideoPlayer(
                playback = playback,
                url = asset.videoUrl,
                apiKey = asset.apiKey,
                modifier = Modifier.fillMaxSize(),
            )
            // A tap on the video pauses or resumes. Only the tap: the drag still belongs to the pager,
            // to move to the next video.
            if (playback != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(playback) {
                            detectTapGestures { if (playback.isPlaying) playback.pause() else playback.play() }
                        },
                )
            }
        }
        return
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        ZoomablePhoto(
            asset = asset,
            recipe = recipe,
            isCurrent = isCurrent,
            onZoomedChange = onZoomedChange,
            onFavorite = onFavorite,
        )
        if (asset.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = ImagoColors.BrandWhite,
                modifier = Modifier.size(ImagoSizes.TouchTarget),
            )
        }
    }
}

/**
 * The detail's photo: thumbnail first, preview on top, and the gestures.
 *
 * The thumbnail underneath exists because of photos with a recipe. Immich serves the original and it
 * is the phone that applies the edit — on the preview that is a few million pixels of work, and
 * until it finished there was nothing on screen but black. The same recipe on a
 * [PLACEHOLDER_PIXELS] px thumbnail comes out almost immediately, and that is what holds the view,
 * blurred, until the final one covers it. Both fit the same rectangle because they share the aspect
 * ratio and `ContentScale.Fit`, so the swap does not touch the framing.
 *
 * The zoom is the editor's — [PhotoTransform] and its functions — so that zooming here and zooming
 * there feel the same and the drag limits come from the same calculation.
 */
@Composable
private fun ZoomablePhoto(
    asset: DetailAsset,
    recipe: EditRecipe?,
    isCurrent: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onFavorite: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var transform by remember(asset.id) { mutableStateOf(PhotoTransform()) }
    var hearts by remember(asset.id) { mutableIntStateOf(0) }
    var settleJob by remember(asset.id) { mutableStateOf<Job?>(null) }

    // The requests are kept instead of being born again on every recomposition.
    // `EditRecipeTransformation` has no equality, so a new request is never equal to the previous one
    // in Coil's eyes — and the pinch, which recomposes, restarted the load and the fade on every frame
    // of the gesture.
    val previewRequest = remember(context, asset.previewUrl, asset.apiKey, recipe) {
        ImageRequest.Builder(context)
            .data(asset.previewUrl)
            .libraryAuth(asset.apiKey)
            .crossfade(true)
            // The local recipe is what tells what is saved apart from what Immich serves; Coil treats
            // it as part of the key, so the processed version is cached like any other image.
            .withRecipe(recipe)
            .build()
    }
    val placeholderRequest = remember(context, asset.thumbnailUrl, asset.apiKey, recipe) {
        ImageRequest.Builder(context)
            .data(asset.thumbnailUrl)
            .libraryAuth(asset.apiKey)
            .size(PLACEHOLDER_PIXELS)
            .crossfade(false)
            .withRecipe(recipe)
            .build()
    }
    val preview = rememberAsyncImagePainter(model = previewRequest)
    // The dimensions of the already processed photo — the recipe decides whether it rotates or
    // crops — and not the original's. The fit and the drag limit come from them.
    val intrinsic = preview.intrinsicSize
    val imageWidth = if (intrinsic.isSpecified && intrinsic.width >= 1f) intrinsic.width.toInt() else 0
    val imageHeight = if (intrinsic.isSpecified && intrinsic.height >= 1f) intrinsic.height.toInt() else 0

    // Swiping to another photo leaves this one fitted: coming back to it zoomed in, without the finger
    // having been there, would be a surprise.
    LaunchedEffect(isCurrent) { if (!isCurrent) transform = PhotoTransform() }
    // Derived, and not read directly: `transform.isFit` as the effect's key read `transform` during
    // composition and made the page recompose on every frame of the pinch. This way it only
    // recomposes when the answer changes, which is once per gesture.
    val isFit by remember { derivedStateOf { transform.isFit } }
    LaunchedEffect(isFit) { onZoomedChange(!isFit) }

    val animateTo: (PhotoTransform) -> Unit = { target ->
        settleJob?.cancel()
        settleJob = scope.launch {
            val start = transform
            animate(0f, 1f, animationSpec = tween(ImagoMotion.Fast)) { fraction, _ ->
                transform = PhotoTransform(
                    zoom = start.zoom + (target.zoom - start.zoom) * fraction,
                    panX = start.panX + (target.panX - start.panX) * fraction,
                    panY = start.panY + (target.panY - start.panY) * fraction,
                )
            }
        }
    }

    // The semantics block is not a composition: the texts are read here, before it.
    val fitDescription = stringResource(Res.string.detail_zoom_fit)
    val zoomDescription = stringResource(Res.string.detail_zoom_level, "%.1f".format(LocalAppLocale.current, transform.zoom))
    val favoriteAction = stringResource(Res.string.detail_favorite)
    val fitAction = stringResource(Res.string.detail_zoom_fit_action)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                stateDescription = if (transform.isFit) fitDescription else zoomDescription
                // Pinch and double tap do not reach TalkBack users.
                customActions = listOf(
                    CustomAccessibilityAction(favoriteAction) {
                        onFavorite()
                        true
                    },
                    CustomAccessibilityAction(fitAction) {
                        animateTo(PhotoTransform())
                        true
                    },
                )
            }
            // `transform` cannot go into the keys: changing it would relaunch the block mid-gesture
            // and the new block's `awaitFirstDown` would only resolve with a new touch. The image's
            // dimensions can: they change once, when it arrives.
            .pointerInput(asset.id, imageWidth, imageHeight) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    settleJob?.cancel()
                    // Without an image there is no aspect ratio to fit yet; until then the drag limit
                    // comes from the surface itself, which is square with it.
                    val photoWidth = imageWidth.takeIf { it > 0 } ?: size.width
                    val photoHeight = imageHeight.takeIf { it > 0 } ?: size.height
                    var current = transform
                    var pinching = false
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            pinching = true
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid != Offset.Unspecified) {
                                val pan = event.calculatePan()
                                current = current.pinch(
                                    // This frame's ratio, of the order of 1.005 — it accumulates.
                                    zoomChange = event.calculateZoom(),
                                    panChangeX = pan.x,
                                    panChangeY = pan.y,
                                    centroidX = centroid.x,
                                    centroidY = centroid.y,
                                    surfaceWidth = size.width,
                                    surfaceHeight = size.height,
                                    imageWidth = photoWidth,
                                    imageHeight = photoHeight,
                                )
                                transform = current
                                moved = true
                                // Consuming is what stops the pager from reading the pinch as a drag
                                // and moving to another photo in the middle of the gesture.
                                event.changes.forEach { it.consume() }
                            }
                        } else if (pressed.size == 1 && !pinching && !current.isFit) {
                            // Fitted, the finger belongs to the pager; zoomed, it becomes the photo's.
                            // Only in this second case is the event consumed.
                            val change = pressed.first()
                            val delta = change.position - change.previousPosition
                            current = current.drag(
                                deltaX = delta.x,
                                deltaY = delta.y,
                                surfaceWidth = size.width,
                                surfaceHeight = size.height,
                                imageWidth = photoWidth,
                                imageHeight = photoHeight,
                            )
                            transform = current
                            moved = true
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (moved) {
                        val settled = current.settle(
                            surfaceWidth = size.width,
                            surfaceHeight = size.height,
                            imageWidth = photoWidth,
                            imageHeight = photoHeight,
                        )
                        if (settled != current) animateTo(settled)
                    }
                }
            }
            .pointerInput(asset.id) {
                detectTapGestures(
                    onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (transform.isFit) {
                            // Marks, never unmarks: a gesture made by mistake cannot erase an earlier
                            // choice.
                            hearts++
                            onFavorite()
                        } else {
                            // Zoomed in, the same double tap is the way out of the zoom — it is the
                            // gesture the hand already expects to get back to the whole photo.
                            animateTo(PhotoTransform())
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transform.zoom
                    scaleY = transform.zoom
                    translationX = transform.panX
                    translationY = transform.panY
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = placeholderRequest,
                // The final one has the description; this is the same photo holding the place, and
                // announcing it twice only got in the way of whoever listens to the screen.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = preview,
                contentDescription = asset.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (preview.state.collectAsState().value is coil3.compose.AsyncImagePainter.State.Error) {
            Text(stringResource(Res.string.detail_unavailable),
                color = ImagoColors.TextSecondary, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        HeartBurst(trigger = hearts)
    }
}

/**
 * The heart that confirms the double tap.
 *
 * Without it the gesture marked the favourite without saying anything: the only sign was the top
 * bar's icon changing, far from where the finger was. It also appears when the photo was already a
 * favourite — it is the answer to the gesture, not to the state.
 */
@Composable
private fun HeartBurst(trigger: Int) {
    var scale by remember { mutableFloatStateOf(0f) }
    var opacity by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        opacity = 1f
        animate(0.4f, 1f, animationSpec = tween(ImagoMotion.Fast)) { value, _ -> scale = value }
        delay(HEART_HOLD_MS)
        // The exit grows while it fades: it is what makes the heart seem to rise from the photo
        // instead of simply switching off.
        animate(1f, 0f, animationSpec = tween(ImagoMotion.Default)) { value, _ ->
            opacity = value
            scale = 1f + (1f - value) * 0.25f
        }
    }
    if (opacity <= 0f) return
    Icon(
        Icons.Filled.Favorite,
        contentDescription = null,
        tint = ImagoColors.BrandWhite,
        modifier = Modifier
            .size(BURST_HEART)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = opacity
            },
    )
}

/**
 * The EXIF line: aperture, shutter speed, ISO, focal length and camera.
 *
 * Each field only appears if it exists — a photo without metadata shows a shorter line instead of
 * separators delimiting nothing.
 */
@Composable
private fun ExifStrip(exif: AssetExif, isLoading: Boolean, modifier: Modifier = Modifier) {
    val entries = buildList {
        exif.fNumber?.let { add("ƒ/%.1f".format(Locale.getDefault(), it)) }
        exif.exposureTime?.let { add(if (it.endsWith("s")) it else "${it}s") }
        exif.iso?.let { add("ISO $it") }
        exif.focalLength?.let { add("%.0fmm".format(Locale.getDefault(), it)) }
        listOfNotNull(exif.make, exif.model)
            .let { camera ->
                // "Apple iPhone 15 Pro" reads worse than "iPhone 15 Pro": the brand is already in the
                // model on most phones.
                when {
                    camera.isEmpty() -> null
                    camera.size == 2 && camera[1].startsWith(camera[0], ignoreCase = true) -> camera[1]
                    else -> camera.joinToString(" ")
                }
            }
            ?.let(::add)
    }
    if (isLoading || entries.isEmpty()) {
        Box(modifier.fillMaxWidth().height(ImagoSizes.IconLarge))
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ImagoSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Box(
                    Modifier
                        .size(width = 1.dp, height = 14.dp)
                        .background(ImagoColors.BorderVisible),
                )
            }
            Text(
                text = entry,
                style = MaterialTheme.typography.labelLarge,
                color = ImagoColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Filmstrip(
    assets: List<DetailAsset>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (Int) -> Unit,
) {
    val context = LocalPlatformContext.current
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(vertical = ImagoSpacing.Sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ImagoSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
    ) {
        itemsIndexed(assets, key = { _, item -> item.id }) { index, item ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(FILMSTRIP_THUMB)
                    .clip(RoundedCornerShape(ImagoRadii.Small))
                    // The white frame is what identifies the current one; without it the strip is just
                    // a row of thumbnails with no position.
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) ImagoColors.BrandWhite else Color.Transparent,
                        shape = RoundedCornerShape(ImagoRadii.Small),
                    )
                    .clickable(role = Role.Tab) { onSelect(index) },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.thumbnailUrl)
                        .libraryAuth(item.apiKey)
                        .crossfade(false)
                        .withRecipe(item.recipe)
                        .build(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isSelected) 2.dp else 0.dp)
                        .clip(RoundedCornerShape(ImagoRadii.Small)),
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = ImagoColors.BrandWhite,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(ImagoSizes.IconDefault),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailActionBar(
    canEdit: Boolean,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onCompose: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ImagoSpacing.Sm, bottom = ImagoSpacing.Md),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailAction(Icons.Outlined.Share, stringResource(Res.string.detail_share), onShare)
        if (canEdit) DetailAction(Icons.Outlined.Tune, stringResource(Res.string.detail_edit), onEdit)
        // For videos too: a composition accepts them, unlike the editor.
        DetailAction(Icons.Outlined.ViewCarousel, stringResource(Res.string.detail_compose), onCompose)
        DetailAction(Icons.Outlined.Info, stringResource(Res.string.detail_info), onInfo)
        DetailAction(Icons.Outlined.Delete, stringResource(Res.string.detail_delete), onDelete, tint = ImagoColors.Danger)
    }
}

@Composable
private fun DetailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = ImagoColors.TextPrimary,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = ImagoSizes.TouchTarget, minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(ImagoSizes.IconLarge))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.86f),
            modifier = Modifier.padding(top = ImagoSpacing.Xs),
        )
    }
}

@Composable
private fun InfoDialog(asset: DetailAsset, exif: AssetExif, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                InfoRow(stringResource(Res.string.detail_info_file), asset.fileName)
                InfoRow(stringResource(Res.string.detail_info_date), formatTakenAt(asset.date.ifBlank { asset.fileCreatedAt }))
                exif.lensModel?.let { InfoRow(stringResource(Res.string.detail_info_lens), it) }
                exif.fNumber?.let { InfoRow(stringResource(Res.string.detail_info_aperture), "ƒ/%.1f".format(Locale.getDefault(), it)) }
                exif.exposureTime?.let { InfoRow(stringResource(Res.string.detail_info_shutter), it) }
                exif.iso?.let { InfoRow("ISO", it.toString()) }
                exif.focalLength?.let { InfoRow(stringResource(Res.string.detail_info_focal_length), "%.0f mm".format(Locale.getDefault(), it)) }
                listOfNotNull(exif.make, exif.model).takeIf { it.isNotEmpty() }?.let {
                    InfoRow(stringResource(Res.string.detail_info_camera), it.joinToString(" "))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.detail_close)) } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.width(120.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
    }
}

/**
 * "16 de mai. de 2024 · 18:42" in Portuguese, "May 16, 2024 · 6:42 PM" in English: the day pattern
 * is a text of each language, and the time comes from its clock. If the date makes no sense, it
 * returns it raw.
 */
@Composable
private fun formatTakenAt(value: String): String {
    val locale = LocalAppLocale.current
    val day = DateTimeFormatter.ofPattern(stringResource(Res.string.detail_date_pattern), locale)
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val format: (java.time.temporal.TemporalAccessor) -> String = { "${day.format(it)} · ${clock.format(it)}" }
    return runCatching { format(OffsetDateTime.parse(value)) }.getOrElse {
        runCatching { format(java.time.LocalDateTime.parse(value)) }.getOrDefault(value)
    }
}
