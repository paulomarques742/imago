package eu.studio742.imago.feature.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMediaLocationTest {
    private val gallery = SharedMediaLocation.MEDIA_STORE_AUTHORITY
    private val otherApp = "com.example.files.provider"

    @Test
    fun asksTheFirstTimeTheGalleryShares() {
        assertTrue(SharedMediaLocation.shouldAsk(listOf(otherApp, gallery), granted = false, askedBefore = false))
    }

    @Test
    fun neverAsksAgainOnceAsked() {
        assertFalse(SharedMediaLocation.shouldAsk(listOf(gallery), granted = false, askedBefore = true))
    }

    @Test
    fun doesNotAskWhenGrantedOrWhenNothingComesFromTheGallery() {
        assertFalse(SharedMediaLocation.shouldAsk(listOf(gallery), granted = true, askedBefore = false))
        assertFalse(SharedMediaLocation.shouldAsk(listOf(otherApp, null), granted = false, askedBefore = false))
    }

    @Test
    fun opensTheOriginalOnlyFromTheGalleryAndWithThePermission() {
        assertTrue(SharedMediaLocation.readsOriginal(gallery, granted = true))
        assertFalse(SharedMediaLocation.readsOriginal(gallery, granted = false))
        assertFalse(SharedMediaLocation.readsOriginal(otherApp, granted = true))
    }
}
