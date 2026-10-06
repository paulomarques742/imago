package eu.studio742.imago.core.composition

import eu.studio742.imago.core.model.EditRecipe
import java.util.UUID
import kotlin.math.floor

/**
 * A photo or video waiting to go into a composition.
 *
 * The recipe comes from outside because the data layer is what knows how to read it; here it is
 * only kept with the photo so the stage draws it as the editor left it.
 */
data class InsertableMedia(
    val reference: MediaReference,
    val isVideo: Boolean = false,
    val recipe: EditRecipe? = null,
)

/** What happened to a batch of media thrown into a composition. */
data class MediaInsertion(
    val project: CompositionProject,
    /** How many went into empty slots of the layout. */
    val filled: Int,
    /** How many were left stacked waiting to be arranged. */
    val stacked: Int,
    /** How many did not fit at all — only happens to videos, and only with the composition full. */
    val skipped: Int,
) {
    val placed: Int get() = filled + stacked
}

/** The side of a stacked media item, as a fraction of the page. */
private const val STACK_SIZE = .62f

/** Where the stack starts, and how far each one moves off the previous one. */
private const val STACK_START = .06f
private const val STACK_STEP = .05f

/** After this many, the cascade goes back to the start — or the last one would leave the page. */
private const val STACK_WRAP = 6

private const val DEFAULT_VIDEO_MS = 5_000L

/**
 * Puts these media into the composition.
 *
 * The rule is the one the layout already wrote: first the empty media slots are filled, in reading
 * order — page by page, top to bottom — because an empty slot is a place someone already decided was
 * for a photo. Whatever is left is stacked in a cascade on [stackPage], waiting to be arranged by
 * hand: inventing new pages on its own would change the shape of the composition without anyone
 * asking.
 *
 * [firstSlotId] is the slot that jumps the queue. It serves whoever chose media from a specific
 * element — the placeholder that was tapped, the photo to be swapped — and so it can point to media
 * already placed, not only to an empty slot.
 *
 * Videos are the exception. A page only holds [MAX_VIDEOS_PER_PAGE], so the video stack opens a new
 * page when the one below fills up — up to the [MAX_COMPOSITION_PAGES] ceiling, beyond which what is
 * left is counted in [MediaInsertion.skipped] instead of being silently dropped.
 */
fun CompositionProject.withMedia(
    media: List<InsertableMedia>,
    now: String,
    /** Where the stack starts: the last page, or the one on the caller's stage. */
    stackPage: Int = pages.lastIndex,
    firstSlotId: String? = null,
    newId: () -> String = { UUID.randomUUID().toString() },
): MediaInsertion {
    if (media.isEmpty()) return MediaInsertion(this, filled = 0, stacked = 0, skipped = 0)

    val elements = elements.toMutableList()
    val pages = pages.toMutableList()
    val videosOnPage = elements.filterIsInstance<CompositionElement.Video>()
        .groupingBy(CompositionElement.Video::pageIndex)
        .eachCount()
        .toMutableMap()
    val free = buildList {
        firstSlotId?.let { id -> elements.firstOrNull { it.id == id } }?.let(::add)
        elements.filterIsInstance<CompositionElement.MediaPlaceholder>()
            .filterNot { it.id == firstSlotId }
            .sortedWith(
                compareBy(
                    { it.transform.bounds.pageIndex() },
                    { it.transform.bounds.y },
                    { it.transform.bounds.x },
                ),
            )
            .forEach(::add)
    }.toMutableList()

    var nextZ = (elements.maxOfOrNull(CompositionElement::zIndex) ?: -1) + 1
    var stackPage = stackPage.coerceIn(pages.indices)
    var stackSlot = 0
    var filled = 0
    var stacked = 0
    var skipped = 0

    for (item in media) {
        val slot = free.firstOrNull { candidate ->
            !item.isVideo ||
                (candidate.acceptsVideo() && videosOnPage.hasRoom(candidate.transform.bounds.pageIndex()))
        }
        val transform: ElementTransform
        val zIndex: Int
        val frame: FrameStyle
        val repeat: Set<Int>
        if (slot != null) {
            free -= slot
            elements -= slot
            // Swapping a video for another gives the page back the place it took; without this, the
            // fifth swap on the same page found it full because of the ones no longer there.
            if (slot is CompositionElement.Video) {
                videosOnPage[slot.pageIndex] = (videosOnPage[slot.pageIndex] ?: 1) - 1
            }
            transform = slot.transform
            zIndex = slot.zIndex
            frame = slot.frameStyle()
            repeat = slot.repeatOnPages
            filled++
        } else {
            if (item.isVideo && !videosOnPage.hasRoom(stackPage)) {
                // The page filled up with videos. Another one opens below, and the cascade restarts there.
                if (pages.size >= MAX_COMPOSITION_PAGES) {
                    skipped++
                    continue
                }
                pages += CompositionPage(id = newId(), index = pages.size)
                stackPage = pages.lastIndex
                stackSlot = 0
            }
            val step = (stackSlot % STACK_WRAP) * STACK_STEP
            transform = ElementTransform(
                NormalizedRect(stackPage + STACK_START + step, STACK_START + step, STACK_SIZE, STACK_SIZE),
            )
            zIndex = nextZ++
            frame = FrameStyle()
            repeat = emptySet()
            stackSlot++
            stacked++
        }
        elements += item.toElement(newId(), transform, zIndex, frame, repeat, now)
        if (item.isVideo) {
            val page = transform.bounds.pageIndex()
            videosOnPage[page] = (videosOnPage[page] ?: 0) + 1
            val duration = item.videoDurationMs()
            pages[page] = pages[page].copy(
                durationMs = maxOf(pages[page].durationMs, duration).coerceAtMost(MAX_PAGE_DURATION_MS),
            )
        }
    }

    val project = if (filled + stacked == 0) {
        this
    } else {
        copy(elements = elements, pages = pages, revision = revision + 1, updatedAt = now)
    }
    return MediaInsertion(project, filled = filled, stacked = stacked, skipped = skipped)
}

private fun MutableMap<Int, Int>.hasRoom(page: Int) = (this[page] ?: 0) < MAX_VIDEOS_PER_PAGE

/**
 * Whether a video can take this slot.
 *
 * Only a placeholder refuses: the others only get here because they were pointed at by hand, and
 * whoever chose a specific photo to swap already said what they wanted there.
 */
private fun CompositionElement.acceptsVideo(): Boolean =
    this !is CompositionElement.MediaPlaceholder || acceptsVideo

/** The frame the slot had, for the incoming media to inherit. */
private fun CompositionElement.frameStyle(): FrameStyle = when (this) {
    is CompositionElement.MediaPlaceholder -> frame
    is CompositionElement.Photo -> frame
    is CompositionElement.Video -> frame
    else -> FrameStyle()
}

/** The page a rectangle belongs to: each page takes one unit on the x axis. */
private fun NormalizedRect.pageIndex(): Int = floor(x).toInt()

/**
 * The same rectangle, shortened until it fits on one page.
 *
 * Only videos need this — the model requires them to stay on one page — and a panorama slot, which
 * spans two or three, would force them off it.
 */
private fun NormalizedRect.confinedTo(page: Int): NormalizedRect {
    val width = width.coerceAtMost(1f)
    return copy(x = x.coerceIn(page.toFloat(), page + 1f - width), width = width)
}

private fun InsertableMedia.videoDurationMs(): Long =
    (reference.durationMs ?: DEFAULT_VIDEO_MS).coerceIn(1_000, MAX_PAGE_DURATION_MS)

private fun InsertableMedia.toElement(
    id: String,
    transform: ElementTransform,
    zIndex: Int,
    frame: FrameStyle,
    repeatOnPages: Set<Int>,
    now: String,
): CompositionElement = if (isVideo) {
    val page = transform.bounds.pageIndex()
    CompositionElement.Video(
        id = id,
        transform = transform.copy(bounds = transform.bounds.confinedTo(page)),
        zIndex = zIndex,
        pageIndex = page,
        media = reference,
        timing = VideoTiming(trimEndMs = videoDurationMs()),
        frame = frame,
    )
} else {
    CompositionElement.Photo(
        id = id,
        transform = transform,
        zIndex = zIndex,
        media = reference,
        recipe = PhotoRecipeSnapshot(
            recipe ?: EditRecipe(
                assetId = reference.assetId,
                originalChecksum = reference.checksum,
                createdAt = now,
                updatedAt = now,
            ),
        ),
        frame = frame,
        repeatOnPages = repeatOnPages,
    )
}
