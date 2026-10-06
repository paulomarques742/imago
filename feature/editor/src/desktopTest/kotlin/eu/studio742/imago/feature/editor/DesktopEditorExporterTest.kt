package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.data.CatalogSyncState
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.model.*
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.toPixelBuffer
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO

/**
 * The editor's export on the computer: the original read from disk, the recipe applied by the CPU
 * engine and a JPEG saved — the same path as on the phone, with the Windows pieces.
 */
class DesktopEditorExporterTest {
    /** A library that only knows how to hand over the requested file; it is all the export uses. */
    private class FileLibrary(private val original: File) : LibraryRepository {
        override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?) = error("not used")
        override suspend fun albums(): List<ImmichAlbum> = error("not used")
        override suspend fun timeBuckets(): List<ImmichTimeBucket> = error("not used")
        override val catalogSync: StateFlow<CatalogSyncState> = MutableStateFlow(CatalogSyncState())
        override suspend fun syncCatalog() = error("not used")
        override suspend fun loadMonth(month: String) = error("not used")
        override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override suspend fun indexOfDate(date: LocalDate, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override fun thumbnailUrl(assetId: String) = assetId
        override fun previewUrl(assetId: String) = assetId
        override fun videoPlaybackUrl(assetId: String) = assetId
        override fun apiKey(assetId: String) = ""
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("not used")
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = error("not used")
        override suspend fun deleteAsset(assetId: String) = error("not used")
        override suspend fun downloadOriginal(assetId: String, destination: File) {
            original.copyTo(destination, overwrite = true)
        }
    }

    @Test
    fun `the recipe and the geometry reach the exported JPEG`() = runBlocking {
        val original = File.createTempFile("imago-original-", ".jpg")
        val source = PixelBuffer(120, 60).apply {
            for (y in 0 until 60) for (x in 0 until 120) {
                pixels[y * 120 + x] = (0xFF shl 24) or (40 shl 16) or (40 shl 8) or 40
            }
        }
        ImageIO.write(BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB).also {
            it.setRGB(0, 0, 120, 60, source.pixels, 0, 120)
        }, "jpg", original)

        val asset = EditorAsset("imago:library:asset", "", "praia.jpg", original.toURI().toString(), "", "2026-09-15T10:00:00Z")
        val recipe = EditRecipe(assetId = asset.id, originalChecksum = "", createdAt = "", updatedAt = "",
            tone = Tone(exposure = 2f), geometry = Geometry(rotation = 90))
        val phases = mutableListOf<UiText>()

        val jpeg = DesktopEditorExporter(FileLibrary(original)).renderJpeg(asset, recipe) { phases += it }
        try {
            val exported = Image.makeFromEncoded(jpeg.readBytes())
            // Rotated a quarter turn: what was 120×60 comes out 60×120.
            assertEquals(60 to 120, exported.width to exported.height)
            assertTrue("houve fases a relatar: $phases", phases.isNotEmpty())
            // Two stops of exposure are four times the light: 40/255 in sRGB gives 0.0208 in linear
            // light, which times four goes back to sRGB near 82. It is double the starting grey.
            val brightened = Image.makeFromEncoded(jpeg.readBytes()).use { image ->
                org.jetbrains.skia.Bitmap.makeFromImage(image).toPixelBuffer().pixels.first()
            }
            val red = brightened ushr 16 and 255
            assertTrue("it brightened from 40 to near 82, it gave $red", red in 76..88)
        } finally {
            jpeg.delete()
            original.delete()
        }
    }
}
