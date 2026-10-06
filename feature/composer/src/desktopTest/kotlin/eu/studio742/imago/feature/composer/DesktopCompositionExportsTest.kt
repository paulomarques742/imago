package eu.studio742.imago.feature.composer

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.model.EditRecipe
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Exporting compositions on the computer: the pages drawn in Skia with the same model as on the
 * phone, saved in a folder that never writes over a previous export.
 */
class DesktopCompositionExportsTest {
    private class FileMedia(private val original: File) : CompositionMediaRepository {
        override fun media(query: String?) = error("not used")
        override fun thumbnailUrl(assetId: String) = assetId
        override fun previewUrl(assetId: String) = assetId
        override fun videoPlaybackUrl(assetId: String) = assetId
        override fun apiKey(assetId: String) = ""
        override suspend fun downloadOriginal(assetId: String, destination: File) {
            original.copyTo(destination, overwrite = true)
        }
        override suspend fun uploadComposition(targetLibraryId: String, file: File, fileName: String, mimeType: String, createdAt: String) =
            error("not used")
    }

    @Test
    fun `the pages go to a new folder, with the photo inside`() = runBlocking {
        val photo = File.createTempFile("imago-photo-", ".jpg")
        ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB).also { image ->
            for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y, 0x3366CC)
        }, "jpg", photo)
        val now = Instant.now().toString()
        val project = CompositionProject(
            id = "projecto", name = "Tarde no rio", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("pagina", 0)), createdAt = now, updatedAt = now,
            elements = listOf(
                CompositionElement.Photo(
                    id = "foto",
                    transform = ElementTransform(NormalizedRect(0.1f, 0.1f, 0.8f, 0.8f)),
                    zIndex = 0,
                    media = MediaReference("imago:library:asset", "", photo.name, width = 64, height = 64),
                    recipe = PhotoRecipeSnapshot(EditRecipe(assetId = "imago:library:asset", originalChecksum = "", createdAt = now, updatedAt = now)),
                ),
            ),
        )
        val pictures = Files.createTempDirectory("imago-pictures")
        val exports = DesktopCompositionExports(FileMedia(photo), picturesFolder = pictures)
        val progress = mutableListOf<Pair<Int, Int>>()
        try {
            val first = exports.export(project, StaticExportFormat.JPEG, pageIndex = null, targetLibraryId = null) { done, total ->
                progress += done to total
            }
            assertEquals(listOf(1 to 1), progress)
            assertEquals(listOf("image/jpeg"), first.mimeTypes)
            val page = File(first.locations.single())
            assertTrue("the page ended up in ${page.absolutePath}", page.isFile)
            assertEquals(pictures.resolve("Tarde no rio").toFile(), page.parentFile)
            val rendered = Image.makeFromEncoded(page.readBytes())
            assertEquals(project.format.pixelWidth to project.format.pixelHeight, rendered.width to rendered.height)

            // A second export never writes over the first.
            val second = exports.export(project, StaticExportFormat.JPEG, pageIndex = null, targetLibraryId = null) { _, _ -> }
            assertEquals(pictures.resolve("Tarde no rio (2)").toFile(), File(second.locations.single()).parentFile)
        } finally {
            photo.delete()
        }
    }
}
