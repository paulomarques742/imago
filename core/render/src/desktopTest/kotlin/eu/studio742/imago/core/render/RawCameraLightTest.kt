package eu.studio742.imago.core.render

import eu.studio742.imago.core.raw.RawDecoder
import eu.studio742.imago.core.raw.syntheticDng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RawCameraLightTest {
    @Test fun `the bridge gives the camera's colour, and As Shot follows the recorded neutral`() {
        val file = Files.createTempFile("imago-raw-light-", ".asset").toFile()
        try {
            // The synthetic camera's matrix is sRGB's own and its neutral is white: As Shot is D65.
            file.writeBytes(syntheticDng(1))
            val neutral = CameraLight.of(RawDecoder.decodePreview(file, 16)!!.xyzToDeveloped)!!
            assertEquals(6504f, neutral.asShot.kelvin, 150f)
            // A neutral with less red and blue than green was recorded under a different light.
            file.writeBytes(syntheticDng(1, warmWhiteBalance = true))
            val other = CameraLight.of(RawDecoder.decodePreview(file, 16)!!.xyzToDeveloped)!!
            assertTrue("${other.asShot}", kotlin.math.abs(other.asShot.kelvin - neutral.asShot.kelvin) > 300f)
        } finally { assertTrue(file.delete()) }
    }

    /** With -Dimago.raw.samples, prints each real camera's As Shot. */
    @Test fun `real samples read a plausible As Shot`() {
        val folder = File(System.getProperty("imago.raw.samples") ?: return)
        folder.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("dng", "nef", "cr2", "cr3", "arw", "raf", "orf", "rw2", "pef") }.sorted().forEach { file ->
            val raw = RawDecoder.decodePreview(file, 256) ?: return@forEach
            val light = CameraLight.of(raw.xyzToDeveloped)
            println("AS SHOT ${file.name}: ${light?.asShot?.let { "%.0f K, tint %+.0f".format(it.kelvin, it.tint) } ?: "no camera matrix"}")
            light?.let { assertTrue(it.asShot.kelvin in 2000f..25000f) }
        }
    }
}
