package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.render.decodePixels
import eu.studio742.imago.feature.editor.resources.Res
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recipe library's sample photos go through compose resources, the only path that reaches both
 * the APK's assets and the desktop classpath. Every index the screen can draw must resolve to a photo.
 */
class RecipeSamplesTest {
    @Test
    fun everySampleIsAComposeResourceThatDecodes() = runBlocking {
        for (index in 0 until SAMPLE_COUNT) {
            val bytes = Res.readBytes(recipeSamplePath(index))
            val pixels = decodePixels(bytes)
            assertNotNull("sample $index does not decode", pixels)
            assertTrue("sample $index is empty", pixels!!.width > 0 && pixels.height > 0)
        }
    }
}
