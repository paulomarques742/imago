package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import eu.studio742.imago.core.designsystem.ImagoChipRow
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.interchange.CommunityRecipe
import eu.studio742.imago.core.interchange.CommunitySort
import eu.studio742.imago.core.interchange.IMAGO_FILE_EXTENSION
import eu.studio742.imago.core.interchange.ReadImagoFile
import eu.studio742.imago.core.interchange.RecipeGalleryError
import eu.studio742.imago.core.interchange.RecipeGalleryException
import eu.studio742.imago.core.interchange.RecipeGalleryService
import eu.studio742.imago.core.interchange.RecipeLinkException
import eu.studio742.imago.core.interchange.RecipeLinkService
import eu.studio742.imago.core.interchange.decodeRecipeFile
import eu.studio742.imago.core.interchange.planRecipeImport
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.util.UUID

/**
 * The community gallery inside the app: anyone browses it and copies a recipe into their own, and
 * supporters like the ones they use. Opening a post downloads its recipe through the link, which is
 * what both the preview on the photo and the copy are made from.
 */
@Stable
internal class CommunityRecipesController(
    private val gallery: RecipeGalleryService,
    private val links: RecipeLinkService,
    private val scope: CoroutineScope,
    /** Where the downloaded recipe is read: off the main thread, except in a test that wants it at once. */
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
) {
    /** A build without the gallery has no tab at all. */
    val available: Boolean get() = gallery.galleryUrl(portuguese = false) != null

    var sort by mutableStateOf(CommunitySort.TOP)
        private set
    var recipes by mutableStateOf<List<CommunityRecipe>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<RecipeGalleryError?>(null)
        private set
    private var endReached = false
    private var pageJob: Job? = null

    var supporter by mutableStateOf(false)
        private set

    var opened by mutableStateOf<CommunityRecipe?>(null)
        private set
    /** [opened]'s recipe as it would enter the library; null while downloading or when it could not be. */
    var openedRecipe by mutableStateOf<SavedRecipe?>(null)
        private set
    var openFailed by mutableStateOf(false)
        private set
    private var openJob: Job? = null

    var copying by mutableStateOf(false)
        private set
    var copyFailed by mutableStateOf(false)
        private set
    /** The posts copied while the screen is open: the button says so instead of copying twice. */
    var copied by mutableStateOf<Set<String>>(emptySet())
        private set
    var likeError by mutableStateOf<RecipeGalleryError?>(null)
        private set

    /** The first time the tab shows; later visits keep what was read. */
    fun start() {
        if (loaded || loading) return
        reload()
        scope.launch { supporter = gallery.isSupporter() }
    }

    fun choose(order: CommunitySort) {
        if (order == sort) return
        sort = order
        reload()
    }

    fun reload() {
        recipes = emptyList()
        endReached = false
        loaded = false
        pageJob?.cancel()
        pageJob = null
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        val order = sort
        pageJob = scope.launch {
            try {
                val page = gallery.communityRecipes(order, recipes.size)
                val known = recipes.mapTo(mutableSetOf()) { it.id }
                recipes = recipes + page.filter { it.id !in known }
                // A page can come short and still not be the last: the server leaves out what it hides.
                endReached = page.isEmpty()
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RecipeGalleryException) {
                error = failure.error
            } finally {
                loading = false
            }
        }
    }

    fun open(recipe: CommunityRecipe) {
        opened = recipe
        openedRecipe = null
        openFailed = false
        copyFailed = false
        likeError = null
        openJob?.cancel()
        openJob = scope.launch {
            val saved = try {
                val bytes = links.open(recipe.linkId)
                withContext(decoding) { recipeOfLink(bytes) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RecipeLinkException) {
                null
            }
            if (saved == null) openFailed = true else openedRecipe = saved
        }
    }

    fun close() {
        openJob?.cancel()
        opened = null
        openedRecipe = null
    }

    fun toggleLike(recipe: CommunityRecipe) {
        if (!supporter) return
        val like = !recipe.liked
        likeError = null
        // At once on the screen; the server's count replaces the guess, and a refusal undoes it.
        replace(recipe.copy(liked = like, likes = (recipe.likes + if (like) 1 else -1).coerceAtLeast(0)))
        scope.launch {
            try {
                val likes = gallery.like(recipe.id, like)
                current(recipe.id)?.let { replace(it.copy(likes = likes)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RecipeGalleryException) {
                replace(recipe)
                likeError = failure.error
            }
        }
    }

    /** Puts [opened]'s recipe in the person's own, under [name] in [collection], through [save]. */
    fun copy(name: String, collection: String, library: List<SavedRecipe>, save: suspend (SavedRecipe) -> Unit) {
        val recipe = opened ?: return
        val downloaded = openedRecipe ?: return
        if (copying || recipe.id in copied) return
        copying = true
        copyFailed = false
        scope.launch {
            try {
                save(communityCopy(downloaded, name, collection, library, Instant.now().toString(), UUID.randomUUID().toString()))
                copied = copied + recipe.id
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                copyFailed = true
            } finally {
                copying = false
            }
        }
    }

    private fun current(id: String) = recipes.firstOrNull { it.id == id }

    private fun replace(recipe: CommunityRecipe) {
        recipes = recipes.map { if (it.id == recipe.id) recipe else it }
        if (opened?.id == recipe.id) opened = recipe
    }
}

private const val LINK_FILE_NAME = "IMAGO.$IMAGO_FILE_EXTENSION"

/** The recipe a link holds, as the import would read it; null when it holds none this app can apply. */
internal fun recipeOfLink(bytes: ByteArray): SavedRecipe? {
    val now = Instant.now().toString()
    return planRecipeImport(
        files = listOf(ReadImagoFile(LINK_FILE_NAME, decodeRecipeFile(LINK_FILE_NAME, bytes))),
        library = emptyList(),
        now = now,
        newId = { UUID.randomUUID().toString() },
    ).recipes.firstOrNull()
}

/**
 * [downloaded] as a recipe of the person's own: a new id, the name that credits its author, numbered
 * if the library has it already, and marked as the community's, so that sharing it asks first and the
 * gallery does not take it as the copier's work.
 */
internal fun communityCopy(
    downloaded: SavedRecipe,
    name: String,
    collection: String,
    library: List<SavedRecipe>,
    now: String,
    id: String,
): SavedRecipe {
    val taken = library.mapTo(mutableSetOf()) { it.name.lowercase() }
    val unique = if (name.lowercase() !in taken) name else generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { it.lowercase() !in taken }
    return downloaded.copy(
        id = id,
        name = unique,
        collection = collection,
        recipe = downloaded.recipe.copy(createdAt = now, updatedAt = now),
        createdAt = now,
        updatedAt = now,
        isFavorite = false,
        usedAt = null,
        origin = RecipeOrigin.COMMUNITY,
    )
}

@androidx.compose.runtime.Composable
internal fun rememberCommunityRecipesController(): CommunityRecipesController {
    val gallery = LocalRecipeGallery.current
    val links = LocalRecipeLinks.current
    val scope = rememberCoroutineScope()
    return remember(gallery, links, scope) { CommunityRecipesController(gallery, links, scope) }
}

/** The tab's content: the order, then the grid of the recipes' «after». */
@Composable
internal fun CommunityRecipesTab(controller: CommunityRecipesController, modifier: Modifier = Modifier) {
    LaunchedEffect(controller) { controller.start() }
    Column(modifier) {
        ImagoChipRow(
            options = CommunitySort.entries,
            selected = controller.sort,
            label = { stringResource(if (it == CommunitySort.TOP) Res.string.editor_community_top else Res.string.editor_community_new) },
            onSelect = controller::choose,
            modifier = Modifier.padding(bottom = ImagoSpacing.Md),
        )
        val recipes = controller.recipes
        val error = controller.error
        when {
            recipes.isEmpty() && controller.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ImagoUi.colors.emphasis)
            }
            recipes.isEmpty() && error != null -> Column(
                Modifier.fillMaxSize().padding(ImagoSpacing.Xxxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
            ) {
                Text(stringResource(communityErrorText(error)), style = MaterialTheme.typography.bodyMedium, color = ImagoUi.colors.secondaryText)
                OutlinedButton(onClick = controller::reload) { Text(stringResource(Res.string.editor_community_retry)) }
            }
            recipes.isEmpty() -> Box(Modifier.fillMaxSize().padding(ImagoSpacing.Xxxl), contentAlignment = Alignment.TopCenter) {
                Text(stringResource(Res.string.editor_community_empty), style = MaterialTheme.typography.bodyMedium, color = ImagoUi.colors.secondaryText)
            }
            else -> {
                val grid = rememberLazyGridState()
                val nearEnd by remember(grid) {
                    derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 4 }
                }
                LaunchedEffect(nearEnd, recipes.size) { if (nearEnd) controller.loadMore() }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    state = grid,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = ImagoSpacing.Md, end = ImagoSpacing.Md, bottom = ImagoSpacing.Lg),
                    horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                ) {
                    items(recipes, key = CommunityRecipe::id) { recipe ->
                        CommunityTile(recipe, onOpen = { controller.open(recipe) })
                    }
                    if (controller.loading || error != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(ImagoSpacing.Md), contentAlignment = Alignment.Center) {
                                if (controller.loading) {
                                    CircularProgressIndicator(color = ImagoUi.colors.emphasis)
                                } else {
                                    TextButton(onClick = controller::loadMore) { Text(stringResource(Res.string.editor_community_retry)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityTile(recipe: CommunityRecipe, onOpen: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoUi.colors.surface)
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        AsyncImage(
            model = recipe.afterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).background(ImagoUi.colors.elevated),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = ImagoSpacing.Md, end = ImagoSpacing.Sm, top = ImagoSpacing.Sm, bottom = ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(recipe.title, style = MaterialTheme.typography.titleSmall, color = ImagoUi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(recipe.authorName, style = MaterialTheme.typography.bodySmall, color = ImagoUi.colors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (recipe.likes > 0) {
                Icon(
                    if (recipe.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = ImagoUi.colors.tertiaryText,
                    modifier = Modifier.size(ImagoSizes.IconSmall),
                )
                Text(
                    recipe.likes.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = ImagoUi.colors.tertiaryText,
                    modifier = Modifier.padding(start = ImagoSpacing.Xs),
                )
            }
        }
    }
}

/**
 * One community recipe: the author's before and after, the recipe on [assetId] — the open photo, or a
 * sample when there is none — and the like and the copy.
 */
@Composable
internal fun CommunityRecipeDialog(
    controller: CommunityRecipesController,
    assetId: String?,
    onOwnPhoto: Boolean,
    library: List<SavedRecipe>,
    save: suspend (SavedRecipe) -> Unit,
) {
    val recipe = controller.opened ?: return
    val copyName = stringResource(Res.string.editor_community_copy_name, recipe.title, recipe.authorName)
    val collection = stringResource(Res.string.editor_community_collection)
    Dialog(
        onDismissRequest = controller::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = ImagoUi.colors.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = controller::close) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.editor_back), tint = ImagoUi.colors.text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(recipe.title, style = MaterialTheme.typography.titleLarge, color = ImagoUi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(Res.string.editor_community_by, recipe.authorName),
                            style = MaterialTheme.typography.bodySmall,
                            color = ImagoUi.colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LikeButton(recipe, controller.supporter) { controller.toggleLike(recipe) }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                            AuthorPhoto(recipe.beforeUrl, stringResource(Res.string.editor_gallery_before), Modifier.weight(1f))
                            AuthorPhoto(recipe.afterUrl, stringResource(Res.string.editor_gallery_after), Modifier.weight(1f))
                        }
                        Text(
                            stringResource(if (onOwnPhoto) Res.string.editor_community_on_your_photo else Res.string.editor_community_on_sample),
                            style = MaterialTheme.typography.titleSmall,
                            color = ImagoUi.colors.text,
                        )
                        OwnPreview(controller, recipe, assetId)
                        controller.likeError?.let { Text(stringResource(communityErrorText(it)), style = MaterialTheme.typography.bodyMedium, color = ImagoUi.colors.error) }
                        if (controller.copyFailed) {
                            Text(stringResource(Res.string.editor_community_copy_failed), style = MaterialTheme.typography.bodyMedium, color = ImagoUi.colors.error)
                        }
                        when {
                            recipe.id in controller.copied -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.editor_community_copied))
                            }
                            controller.copying -> Box(Modifier.fillMaxWidth().padding(ImagoSpacing.Sm), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = ImagoUi.colors.emphasis)
                            }
                            else -> Button(
                                onClick = { controller.copy(copyName, collection, library, save) },
                                enabled = controller.openedRecipe != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(Res.string.editor_community_copy)) }
                        }
                        Text(stringResource(Res.string.editor_community_copy_note), style = MaterialTheme.typography.bodySmall, color = ImagoUi.colors.secondaryText)
                        Spacer(Modifier.height(ImagoSpacing.Xxl))
                    }
                }
            }
        }
    }
}

/** The heart: a supporter's to press; to anyone else, the count alone. */
@Composable
private fun LikeButton(recipe: CommunityRecipe, supporter: Boolean, onToggle: () -> Unit) {
    val count = pluralStringResource(Res.plurals.editor_community_likes, recipe.likes, recipe.likes)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = ImagoSpacing.Sm)) {
        if (supporter) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (recipe.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(if (recipe.liked) Res.string.editor_community_unlike else Res.string.editor_community_like),
                    tint = if (recipe.liked) ImagoUi.colors.emphasis else ImagoUi.colors.text,
                )
            }
            Text(recipe.likes.toString(), style = MaterialTheme.typography.labelLarge, color = ImagoUi.colors.secondaryText)
        } else if (recipe.likes > 0) {
            Text(count, style = MaterialTheme.typography.labelLarge, color = ImagoUi.colors.secondaryText)
        }
    }
}

@Composable
private fun AuthorPhoto(url: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
        AsyncImage(
            model = url,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(12.dp)).background(ImagoUi.colors.workspace),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = ImagoUi.colors.secondaryText)
    }
}

@Composable
private fun OwnPreview(controller: CommunityRecipesController, recipe: CommunityRecipe, assetId: String?) {
    val box = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)).background(ImagoUi.colors.workspace)
    val downloaded = controller.openedRecipe
    when {
        controller.openFailed -> Box(box.padding(ImagoSpacing.Lg), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.editor_community_open_failed), style = MaterialTheme.typography.bodyMedium, color = ImagoUi.colors.secondaryText)
        }
        downloaded == null -> Box(box, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ImagoUi.colors.secondaryText) }
        else -> {
            val card = remember(downloaded) { listOf(downloaded).toCards().single() }
            val preview by rememberRecipePreview(assetId, "community:${recipe.id}", card.recipe)
            Box(box, contentAlignment = Alignment.Center) {
                val image = preview
                if (image == null) {
                    CircularProgressIndicator(color = ImagoUi.colors.secondaryText)
                } else {
                    Image(bitmap = image, contentDescription = stringResource(Res.string.editor_community_preview), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun communityErrorText(error: RecipeGalleryError) = when (error) {
    RecipeGalleryError.OFFLINE -> Res.string.editor_recipe_link_offline
    RecipeGalleryError.NOT_SUPPORTER -> Res.string.editor_gallery_error_not_supporter
    RecipeGalleryError.LINK_GONE -> Res.string.editor_community_gone
    else -> Res.string.editor_community_failed
}
