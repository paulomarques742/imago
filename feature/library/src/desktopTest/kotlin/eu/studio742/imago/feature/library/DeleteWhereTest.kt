package eu.studio742.imago.feature.library

import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteWhereTest {
    private val phone = AssetReference(DEVICE_LIBRARY_ID, "p1").encode()
    private val itsServerCopy = AssetReference("home", "s1").encode()
    private val phoneOnly = AssetReference(DEVICE_LIBRARY_ID, "p2").encode()
    private val serverOnly = AssetReference("home", "s2").encode()
    private val shown = listOf(phone, phoneOnly, serverOnly)
    private val counterparts = mapOf(phone to itsServerCopy)

    @Test
    fun aPhotoOnBothSidesGoesFromWhereWasChosen() {
        assertEquals(listOf(phone, phoneOnly, serverOnly), idsToDelete(shown, counterparts, DeleteWhere.DEVICE))
        assertEquals(listOf(itsServerCopy, phoneOnly, serverOnly), idsToDelete(shown, counterparts, DeleteWhere.SERVER))
        assertEquals(listOf(phone, itsServerCopy, phoneOnly, serverOnly), idsToDelete(shown, counterparts, DeleteWhere.BOTH))
    }

    @Test
    fun withoutCopiesOnTheOtherSideEachGoesFromItsOwn() {
        assertEquals(shown, idsToDelete(shown, emptyMap(), DeleteWhere.DEVICE))
    }

    @Test
    fun aSelectionFromBothSidesHasNoSingleAlbumToGoTo() {
        fun asset(id: String) = AssetUiModel(
            id = id, checksum = "", fileName = "a.jpg", date = "", fileCreatedAt = "", width = null, height = null,
            isFavorite = false, isEdited = false, hasLocalRecipe = false, thumbnailUrl = "", previewUrl = "", apiKey = "",
        )

        assertEquals(AlbumSlot.NONE, albumSlotFor(listOf(asset(phoneOnly), asset(serverOnly)), openAlbum = null, deviceFolders = true))
        assertEquals(AlbumSlot.ADD, albumSlotFor(listOf(asset(serverOnly)), openAlbum = null, deviceFolders = true))
    }
}
