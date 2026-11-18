package eu.studio742.imago

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play billing only for a copy Google Play installed; every other copy gets the programme's page. */
class SupportChannelTest {
    @Test
    fun `a copy installed by Google Play sells through Play`() {
        assertTrue(sellsSupportThroughPlay(programmeOpen = true, productId = "support", installer = PLAY_STORE_INSTALLER))
    }

    @Test
    fun `the GitHub APK installed by hand does not sell through Play`() {
        assertFalse(sellsSupportThroughPlay(true, "support", installer = "com.google.android.packageinstaller"))
        assertFalse(sellsSupportThroughPlay(true, "support", installer = "com.android.packageinstaller"))
    }

    @Test
    fun `a copy installed by another store does not sell through Play`() {
        assertFalse(sellsSupportThroughPlay(true, "support", installer = "com.aurora.store"))
    }

    @Test
    fun `a copy with no recorded installer does not sell through Play`() {
        assertFalse(sellsSupportThroughPlay(true, "support", installer = null))
    }

    @Test
    fun `nothing is sold with the programme closed or without the product`() {
        assertFalse(sellsSupportThroughPlay(programmeOpen = false, productId = "support", installer = PLAY_STORE_INSTALLER))
        assertFalse(sellsSupportThroughPlay(programmeOpen = true, productId = " ", installer = PLAY_STORE_INSTALLER))
    }
}
