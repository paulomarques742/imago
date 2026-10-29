package eu.studio742.imago.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedMotionTest {
    @Test fun aMovementInsideAPhotoGoesAndComesBack() {
        val motion = EmbeddedMotion("content://media/external/images/media/42", 4_533_174, 4_179_782)

        assertEquals(motion, EmbeddedMotion.parse(motion.encode()))
        // Through the library's qualification too: that is how it reaches the player.
        val qualified = AssetReference(DEVICE_LIBRARY_ID, motion.encode()).encode()
        assertEquals(motion, EmbeddedMotion.parse(AssetReference.parse(qualified).localId))
    }

    @Test fun anythingElseIsNotOne() {
        assertNull(EmbeddedMotion.parse("2c5f0d6e-5c43-4c9b-9a1e-0d5f3a7b8c90"))
        assertNull(EmbeddedMotion.parse("content://media/external/images/media/42"))
        assertNull(EmbeddedMotion.parse("content://media/external/images/media/42#imago-motion=12"))
        assertNull(EmbeddedMotion.parse("content://media/external/images/media/42#imago-motion=12-0"))
    }
}
