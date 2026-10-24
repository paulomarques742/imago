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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RawCoilDecoderTest {
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
}
