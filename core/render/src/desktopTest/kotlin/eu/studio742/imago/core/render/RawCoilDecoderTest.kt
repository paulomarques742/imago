package eu.studio742.imago.core.render

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RawDevelopment
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.raw.syntheticDng
import eu.studio742.imago.core.raw.RawDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RawCoilDecoderTest {
    @Test fun `native preview reduction matches linear sampling before display conversion`() = runBlocking {
        val file = Files.createTempFile("imago-raw-preview-", ".asset").toFile()
        try {
            file.writeBytes(syntheticDng(6, warmWhiteBalance = true))
            val half = requireNotNull(RawDecoder.decode(file, halfSize = true))
            val reference = RecipePixels.reduce(FloatPixelBuffer(half.width, half.height, half.rgb), 17)
            val preview = requireNotNull(decodeRawPreview(file, maxSide = 17, baselineExposure = false))
            assertEquals(reference.width to reference.height, preview.width to preview.height)
            assertTrue(preview.linear)
            for (i in preview.rgb.indices) {
                assertEquals("Linear component $i", reference.rgb[i], preview.rgb[i], .002f)
            }
        } finally { assertTrue(file.delete()) }
    }

    @Test fun `a small Coil request receives a bounded RAW thumbnail`() = runBlocking {
        val file = Files.createTempFile("imago-raw-coil-size-", ".asset").toFile()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).components { add(RawPhotoDecoder.Factory()) }.build()
        try {
            file.writeBytes(syntheticDng(6))
            val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(file).size(16)
                .softwareBitmap().build())
            assertTrue("$result", result is SuccessResult)
            val bitmap = (result as SuccessResult).image.toBitmap()
            assertEquals(12 to 16, bitmap.width to bitmap.height)
        } finally { loader.shutdown(); assertTrue(file.delete()) }
    }

    @Test fun `RAW thumbnail exposure is applied once before eight bit conversion`() = runBlocking {
        val file = Files.createTempFile("imago-raw-coil-", ".asset").toFile()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).components { add(RawPhotoDecoder.Factory()) }.build()
        try {
            file.writeBytes(syntheticDng(1, warmWhiteBalance = true))
            val recipe = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "1",
                tone = Tone(exposure = -2f), rawDevelopment = RawDevelopment())
            val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(file).size(64)
                .withRecipe(recipe).softwareBitmap().build())
            assertTrue("$result", result is SuccessResult)
            val pixels = (result as SuccessResult).image.toBitmap().toPixelBuffer()
            val red = pixels.pixels[(pixels.height / 2) * pixels.width + pixels.width / 2] ushr 16 and 255
            assertTrue("The original highlight must reach the thumbnail once ($red).", red in 168..190)
        } finally { loader.shutdown(); assertTrue(file.delete()) }
    }

    @Test fun `an unedited RAW thumbnail has the current rendering, a process 19 recipe keeps its own`() = runBlocking {
        val file = Files.createTempFile("imago-raw-coil-rendering-", ".asset").toFile()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).components { add(RawPhotoDecoder.Factory()) }.build()
        suspend fun centreGreen(recipe: EditRecipe?): Int {
            val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(file).size(64).withRecipe(recipe)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).softwareBitmap().build())
            assertTrue("$result", result is SuccessResult)
            val pixels = (result as SuccessResult).image.toBitmap().toPixelBuffer()
            return pixels.pixels[(pixels.height / 2) * pixels.width + pixels.width / 2] ushr 8 and 255
        }
        try {
            file.writeBytes(syntheticDng(1, baselineExposure = -2))
            val written19 = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "1",
                processVersion = 19, rawDevelopment = RawDevelopment())
            val developed = centreGreen(null)
            val asShot = centreGreen(written19)
            assertTrue("Two stops down must darken the unedited thumbnail ($developed against $asShot).", developed < asShot - 20)
            assertEquals(developed, centreGreen(written19.copy(processVersion = 20, rawDevelopment = RawDevelopment(rendering = 2))))
        } finally { loader.shutdown(); assertTrue(file.delete()) }
    }
}
