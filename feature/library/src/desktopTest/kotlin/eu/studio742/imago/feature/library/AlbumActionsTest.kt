package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.model.AlbumAddition
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.FolderTransfer
import eu.studio742.imago.feature.library.resources.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumActionsTest {
    private fun asset(libraryId: String) = AssetUiModel(
        id = AssetReference(libraryId, "1").encode(),
        checksum = "",
        fileName = "IMG_1.JPG",
        date = "",
        fileCreatedAt = "",
        width = null,
        height = null,
        isFavorite = false,
        isEdited = false,
        hasLocalRecipe = false,
        thumbnailUrl = "",
        previewUrl = "",
        apiKey = "",
    )

    private fun album(canEditContent: Boolean, isFolder: Boolean = false) = AlbumUiModel(
        id = "imago:server:al",
        name = "Trip",
        description = "",
        thumbnailUrl = null,
        assetCount = 3,
        startDate = null,
        endDate = null,
        shared = false,
        apiKey = "",
        canEditContent = canEditContent,
        isFolder = isFolder,
    )

    @Test
    fun theAlbumPlaceTakesOutInsideAnAlbumThatCanChange() {
        val server = listOf(asset("server"))

        assertEquals(AlbumSlot.ADD, albumSlotFor(server, openAlbum = null))
        assertEquals(AlbumSlot.REMOVE, albumSlotFor(server, album(canEditContent = true)))
        // An album shared to look at only: the photos can still go to one of this person's own.
        assertEquals(AlbumSlot.ADD, albumSlotFor(server, album(canEditContent = false)))
    }

    @Test
    fun aFolderIsNeverTakenOutOfOnlyMovedOrCopiedFrom() {
        val device = listOf(asset(DEVICE_LIBRARY_ID))

        assertEquals(AlbumSlot.ADD, albumSlotFor(device, album(canEditContent = true, isFolder = true)))
        assertEquals(AlbumSlot.ADD, albumSlotFor(device, openAlbum = null))
    }

    @Test
    fun nothingChosenHasNoAlbum() {
        assertEquals(AlbumSlot.NONE, albumSlotFor(emptyList(), openAlbum = null))
    }

    @Test
    fun aFolderSaysWhetherThePhotosWereMovedOrCopied() {
        val result = AlbumAddition(added = 3, alreadyThere = 0, failed = 0)

        assertEquals(
            UiText.Plural(Res.plurals.library_album_moved, 3, listOf(3, "Trip")),
            albumAdditionOutcome(result, "Trip", FolderTransfer.MOVE).message,
        )
        assertEquals(
            UiText.Plural(Res.plurals.library_album_copied, 3, listOf(3, "Trip")),
            albumAdditionOutcome(result, "Trip", FolderTransfer.COPY).message,
        )
    }

    @Test
    fun addingSaysWhatWasAlreadyThereAndWhatFailed() {
        assertEquals(
            AlbumOutcome(UiText.Plural(Res.plurals.library_album_added, 2, listOf(2, "Trip")), failed = false),
            albumAdditionOutcome(AlbumAddition(added = 2, alreadyThere = 0, failed = 0), "Trip"),
        )
        assertEquals(
            AlbumOutcome(UiText.Plural(Res.plurals.library_album_added_some_there, 2, listOf(2, "Trip")), failed = false),
            albumAdditionOutcome(AlbumAddition(added = 2, alreadyThere = 1, failed = 0), "Trip"),
        )
        assertEquals(
            AlbumOutcome(UiText.Resource(Res.string.library_album_all_there, listOf("Trip")), failed = false),
            albumAdditionOutcome(AlbumAddition(added = 0, alreadyThere = 3, failed = 0), "Trip"),
        )
        assertTrue(albumAdditionOutcome(AlbumAddition(added = 2, alreadyThere = 0, failed = 1), "Trip").failed)
    }

    @Test
    fun aNameIsTrimmedAndNeverBlank() {
        assertEquals("Trip", albumNameOrNull("  Trip "))
        assertNull(albumNameOrNull("   "))
        assertFalse(albumNameOrNull("x").isNullOrEmpty())
    }

    @Test
    fun searchingAlbumsMatchesEveryWordOfTheNameWithoutCaseOrAccents() {
        val albums = listOf("North Beach 2025", "Café Zürich", "Christmas").map { album(canEditContent = false).copy(id = it, name = it) }

        assertEquals(listOf("North Beach 2025"), albumsMatching(albums, "beach 2025").map { it.name })
        assertEquals(listOf("Café Zürich"), albumsMatching(albums, "cafe zurich").map { it.name })
        assertEquals(emptyList<String>(), albumsMatching(albums, "beach christmas").map { it.name })
        assertEquals(albums, albumsMatching(albums, "  "))
    }
}
