package eu.studio742.imago.core.raw

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class RawDecoderTest {
    @Test fun `header identification reports oriented dimensions without developing the file`() {
        withDng(orientation = 6) { file ->
            val info = requireNotNull(RawDecoder.identify(file))
            assertEquals(48 to 64, info.width to info.height)
            assertEquals("IMAGO", info.cameraMake)
        }
    }
    @Test fun `native decoder exposes its actual version and camera support`() {
        assertEquals("0.22.2-Release", RawDecoder.version)
        val cameras = RawDecoder.supportedCameras
        assertTrue(cameras.any { it.startsWith("Canon ") })
        assertTrue(cameras.any { it.startsWith("Nikon ") })
        assertTrue(cameras.any { it.startsWith("Fujifilm ", ignoreCase = true) })
        assertTrue(cameras.any { it.contains("DNG") })
    }

    @Test fun `a real native DNG decode preserves more than eight bits without an extension`() {
        withDng { file ->
            val image = requireNotNull(RawDecoder.decode(file))
            assertEquals(64, image.width)
            assertEquals(48, image.height)
            assertEquals(image.width * image.height * 3, image.rgb.size)
            assertTrue(image.rgb.toSet().size > 256)
            assertTrue(image.rgb.indices.all { image.sample(it).isFinite() })
            assertEquals("IMAGO", image.cameraMake)
            assertEquals("Synthetic Bayer", image.cameraModel)
        }
    }

    @Test fun `half size decodes the sensor at a reduced resolution`() {
        withDng { file ->
            val image = requireNotNull(RawDecoder.decode(file, halfSize = true))
            assertEquals(32, image.width)
            assertEquals(24, image.height)
        }
    }

    @Test fun `bounded previews allocate only the requested oriented linear pixels`() {
        for (orientation in 1..8) withDng(orientation) { file ->
            file.writeBytes(syntheticDng(orientation, warmWhiteBalance = true))
            val preview = requireNotNull(RawDecoder.decodePreview(file, maxSide = 16))
            assertEquals(if (orientation >= 5) 12 to 16 else 16 to 12, preview.width to preview.height)
            assertEquals(16 * 12 * 3, preview.rgb.size)
            assertTrue(preview.rgb.indices.all { preview.sample(it).isFinite() })
            assertTrue("Preview reduction must retain camera highlights.", preview.rgb.indices.any { preview.sample(it) > 1.1f })
        }
    }

    @Test fun `a preview request never enlarges the decoded sensor`() {
        withDng { file ->
            val preview = requireNotNull(RawDecoder.decodePreview(file, maxSide = 2048))
            val half = requireNotNull(RawDecoder.decode(file, halfSize = true))
            assertEquals(half.width to half.height, preview.width to preview.height)
            assertArrayEquals(half.rgb, preview.rgb)
            val full = requireNotNull(RawDecoder.decode(file))
            assertEquals(64 to 48, full.width to full.height)
        }
    }

    @Test fun `preview limits handle a single pixel and reject invalid dimensions`() {
        withDng { file ->
            val preview = requireNotNull(RawDecoder.decodePreview(file, maxSide = 1))
            assertEquals(1 to 1, preview.width to preview.height)
            assertThrows(IllegalArgumentException::class.java) { RawDecoder.decodePreview(file, maxSide = 0) }
            assertThrows(IllegalArgumentException::class.java) { RawDecoder.decodePreview(file, maxSide = -1) }
        }
    }

    @Test fun `floating point deflate DNG preserves the sensor signal`() {
        withDng { file ->
            val original = requireNotNull(RawDecoder.decode(file))
            file.writeBytes(syntheticDng(orientation = 1, compressed = true))
            val compressed = requireNotNull(RawDecoder.decode(file))
            assertEquals(original.width, compressed.width)
            assertEquals(original.height, compressed.height)
            // LibRaw normalizes floating-point sensor data to integers before interpolation.
            // Allow its rounding within one 12-bit sensor step in 16-bit output.
            val errors = original.rgb.indices.map {
                kotlin.math.abs(original.sample(it) - compressed.sample(it))
            }
            assertTrue("Maximum linear error: ${errors.max()}", errors.max() <= 1f / 1024f)
        }
    }

    @Test fun `a truncated recognized RAW fails instead of returning a preview`() {
        withDng { file ->
            file.writeBytes(file.readBytes().dropLast(512).toByteArray())
            assertThrows(RawDecodeException::class.java) { RawDecoder.decode(file) }
        }
    }

    @Test fun `monochrome sensor data is exposed through the RGB contract`() {
        withDng { file ->
            file.writeBytes(syntheticDng(orientation = 1, monochrome = true))
            val image = requireNotNull(RawDecoder.decode(file))
            assertTrue(image.rgb.indices.any { image.sample(it) > 0f })
            for (pixel in 0 until image.width * image.height) {
                assertEquals(image.rgb[pixel * 3], image.rgb[pixel * 3 + 1])
                assertEquals(image.rgb[pixel * 3], image.rgb[pixel * 3 + 2])
            }
        }
    }

    @Test fun `camera white balance retains highlights above display white`() {
        withDng { file ->
            file.writeBytes(syntheticDng(orientation = 1, warmWhiteBalance = true))
            val image = requireNotNull(RawDecoder.decode(file))
            assertTrue("White balance must not clip scene data to display white.", image.rgb.indices.any { image.sample(it) > 1.1f })
            assertTrue(image.rgb.indices.all { image.sample(it).isFinite() })
        }
    }

    @Test fun `native decoding applies the recorded orientation exactly once`() {
        withDng(orientation = 6) { file ->
            val image = requireNotNull(RawDecoder.decode(file))
            assertEquals(48, image.width)
            assertEquals(64, image.height)
        }
    }

    @Test fun `unsupported input returns null and releases the file`() {
        val file = Files.createTempFile("imago-raw-invalid-", ".asset").toFile()
        try {
            file.writeBytes(ByteArray(2048))
            assertNull(RawDecoder.decode(file))
        } finally { assertTrue(file.delete()) }
    }

    @Test fun `an interrupted worker does not enter the native decoder`() {
        withDng { file ->
            Thread.currentThread().interrupt()
            try {
                assertThrows(CancellationException::class.java) { RawDecoder.decode(file) }
            } finally { Thread.interrupted() }
        }
    }

    private fun withDng(orientation: Int = 1, block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("imago-raw-")
        // Unicode and a non-image suffix exercise the path bridge and content identification.
        val file = directory.resolve("photo-\u00e9-\uD83D\uDCF7.asset").toFile()
        try {
            file.writeBytes(syntheticDng(orientation))
            block(file)
        } finally {
            assertTrue("The decoder must not retain an open file handle.", file.delete())
            Files.delete(directory)
        }
    }
}

