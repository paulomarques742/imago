package eu.studio742.imago.core.composition

import eu.studio742.imago.core.model.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = "2026-09-08T10:00:00Z"

class MediaInsertionTest {
    private var counter = 0
    private val ids = { "id-${counter++}" }

    private fun project(
        pages: Int = 1,
        elements: List<CompositionElement> = emptyList(),
    ) = CompositionProject(
        id = "project",
        name = "Teste",
        format = PageFormatPreset.STORY_9_16.format,
        pages = List(pages) { CompositionPage(id = "page-$it", index = it) },
        elements = elements,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun placeholder(id: String, x: Float, y: Float, page: Int = 0, acceptsVideo: Boolean = true) =
        CompositionElement.MediaPlaceholder(
            id = id,
            transform = ElementTransform(NormalizedRect(page + x, y, .5f, .5f)),
            zIndex = 0,
            acceptsVideo = acceptsVideo,
        )

    private fun photo(name: String) = InsertableMedia(
        reference = MediaReference(assetId = name, checksum = "sum-$name", fileName = "$name.jpg"),
    )

    private fun video(name: String, durationMs: Long = 8_000) = InsertableMedia(
        reference = MediaReference(
            assetId = name,
            checksum = "sum-$name",
            fileName = "$name.mp4",
            durationMs = durationMs,
        ),
        isVideo = true,
    )

    @Test
    fun `fills the empty slots in reading order`() {
        val source = project(
            elements = listOf(
                placeholder("baixo", x = 0f, y = .5f),
                placeholder("cima", x = 0f, y = 0f),
            ),
        )

        val result = source.withMedia(listOf(photo("primeira")), NOW, newId = ids)

        assertEquals(1, result.filled)
        assertEquals(0, result.stacked)
        val photos = result.project.elements.filterIsInstance<CompositionElement.Photo>()
        assertEquals(listOf("primeira"), photos.map { it.media.assetId })
        // It took the place of the top slot, and the bottom one is still waiting.
        assertEquals(0f, photos.single().transform.bounds.y, 1e-4f)
        assertEquals(
            listOf("baixo"),
            result.project.elements.filterIsInstance<CompositionElement.MediaPlaceholder>().map { it.id },
        )
    }

    @Test
    fun `what is left is stacked on the last page without creating new pages`() {
        val source = project(pages = 2, elements = listOf(placeholder("unico", x = 0f, y = 0f)))

        val result = source.withMedia(listOf(photo("a"), photo("b"), photo("c")), NOW, newId = ids)

        assertEquals(1, result.filled)
        assertEquals(2, result.stacked)
        assertEquals(2, result.project.pages.size)
        val stacked = result.project.elements.filterIsInstance<CompositionElement.Photo>()
            .filter { it.media.assetId != "a" }
        // The last page is the one with index 1: the x axis counts one unit per page.
        assertTrue(stacked.all { it.transform.bounds.x >= 1f && it.transform.bounds.right <= 2f })
        // In a cascade, so they can be told apart.
        assertTrue(stacked[0].transform.bounds.x < stacked[1].transform.bounds.x)
    }

    @Test
    fun `a stacked video opens a new page when the one below fills up`() {
        val source = project(
            elements = List(MAX_VIDEOS_PER_PAGE) { index ->
                CompositionElement.Video(
                    id = "video-$index",
                    transform = ElementTransform(NormalizedRect(.1f, .1f, .3f, .3f)),
                    zIndex = index,
                    pageIndex = 0,
                    media = MediaReference("v$index", "sum", "v$index.mp4"),
                    timing = VideoTiming(trimEndMs = 4_000),
                )
            },
        )

        val result = source.withMedia(listOf(video("novo")), NOW, newId = ids)

        assertEquals(2, result.project.pages.size)
        val added = result.project.elements.filterIsInstance<CompositionElement.Video>()
            .single { it.media.assetId == "novo" }
        assertEquals(1, added.pageIndex)
        // The page stretches to fit the whole video.
        assertEquals(8_000L, result.project.pages[1].durationMs)
    }

    @Test
    fun `a video does not go into a slot that only accepts photos`() {
        val source = project(elements = listOf(placeholder("photos-only", x = 0f, y = 0f, acceptsVideo = false)))

        val result = source.withMedia(listOf(video("clipe")), NOW, newId = ids)

        assertEquals(0, result.filled)
        assertEquals(1, result.stacked)
        assertEquals(
            listOf("photos-only"),
            result.project.elements.filterIsInstance<CompositionElement.MediaPlaceholder>().map { it.id },
        )
    }

    @Test
    fun `a video inherits a panorama slot without leaving the page`() {
        val source = project(
            pages = 2,
            elements = listOf(
                CompositionElement.MediaPlaceholder(
                    id = "panorama",
                    transform = ElementTransform(NormalizedRect(0f, 0f, 2f, 1f)),
                    zIndex = 0,
                ),
            ),
        )

        val result = source.withMedia(listOf(video("clipe")), NOW, newId = ids)

        val added = result.project.elements.filterIsInstance<CompositionElement.Video>().single()
        assertEquals(0, added.pageIndex)
        assertEquals(1f, added.transform.bounds.width, 1e-4f)
        assertTrue(added.transform.bounds.right <= 1f)
    }

    @Test
    fun `the stack starts on the page it is given`() {
        val source = project(pages = 3)

        val result = source.withMedia(listOf(photo("a")), NOW, stackPage = 1, newId = ids)

        val added = result.project.elements.filterIsInstance<CompositionElement.Photo>().single()
        assertEquals(1, added.transform.bounds.x.toInt())
    }

    @Test
    fun `the slot pointed at jumps the queue`() {
        val source = project(
            elements = listOf(
                placeholder("primeiro", x = 0f, y = 0f),
                placeholder("apontado", x = .5f, y = .5f),
            ),
        )

        val result = source.withMedia(listOf(photo("a")), NOW, firstSlotId = "apontado", newId = ids)

        val added = result.project.elements.filterIsInstance<CompositionElement.Photo>().single()
        assertEquals(.5f, added.transform.bounds.y, 1e-4f)
        assertEquals(
            listOf("primeiro"),
            result.project.elements.filterIsInstance<CompositionElement.MediaPlaceholder>().map { it.id },
        )
    }

    @Test
    fun `pointing at a photo already placed swaps it for the new one`() {
        val source = project(
            elements = listOf(
                CompositionElement.Photo(
                    id = "velha",
                    transform = ElementTransform(NormalizedRect(.2f, .3f, .4f, .4f)),
                    zIndex = 7,
                    media = MediaReference("antiga", "sum", "antiga.jpg"),
                    recipe = PhotoRecipeSnapshot(
                        EditRecipe(assetId = "antiga", originalChecksum = "sum", createdAt = NOW, updatedAt = NOW),
                    ),
                    frame = FrameStyle(cornerRadius = 12f),
                ),
            ),
        )

        val result = source.withMedia(listOf(photo("nova")), NOW, firstSlotId = "velha", newId = ids)

        val photos = result.project.elements.filterIsInstance<CompositionElement.Photo>()
        assertEquals(listOf("nova"), photos.map { it.media.assetId })
        // It stays exactly where the other one was, with the frame it had.
        assertEquals(.3f, photos.single().transform.bounds.y, 1e-4f)
        assertEquals(7, photos.single().zIndex)
        assertEquals(12f, photos.single().frame.cornerRadius, 1e-4f)
        assertEquals(1, result.filled)
    }

    @Test
    fun `an empty list does not touch the project`() {
        val source = project()

        val result = source.withMedia(emptyList(), NOW, newId = ids)

        assertEquals(source, result.project)
        assertEquals(0, result.placed)
    }

    @Test
    fun `the revision goes up once per batch`() {
        val source = project()

        val result = source.withMedia(listOf(photo("a"), photo("b")), NOW, newId = ids)

        assertEquals(source.revision + 1, result.project.revision)
        assertEquals(NOW, result.project.updatedAt)
        assertEquals(2, result.placed)
    }
}
