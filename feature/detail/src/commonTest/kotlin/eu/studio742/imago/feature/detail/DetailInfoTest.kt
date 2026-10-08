package eu.studio742.imago.feature.detail

import eu.studio742.imago.core.data.renamedFile
import eu.studio742.imago.core.model.AssetExif
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailInfoTest {
    private val portuguese = Locale.forLanguageTag("pt-PT")

    @Test
    fun theInformationReadsAsTheSystemGalleryWritesIt() {
        val exif = AssetExif(
            fNumber = 1.7f, exposureTime = "0.0031746", iso = 10, focalLength = 23f, exposureBias = 0f,
            make = "samsung", model = "Galaxy S23 Ultra", imageWidth = 3000, imageHeight = 4000, fileSizeBytes = 3_208_642,
        )

        assertEquals(listOf("3,06 MB", "3000x4000", "12MP"), fileFacts(exif, portuguese))
        assertEquals(listOf("ISO 10", "23mm", "0,0ev", "F1,7", "1/315 s"), shotFacts(exif, portuguese))
        assertEquals("Galaxy S23 Ultra", cameraName(exif))
        assertEquals("Apple iPhone 15 Pro", cameraName(AssetExif(make = "Apple", model = "iPhone 15 Pro")))
        assertEquals("Canon EOS R6", cameraName(AssetExif(make = "Canon", model = "EOS R6")))
    }

    @Test
    fun theShutterIsWrittenAsAFractionOrInSeconds() {
        assertEquals("1/125 s", shutter("1/125"))
        assertEquals("1/250 s", shutter("0.004"))
        assertEquals("2 s", shutter("2"))
        assertEquals("1.5 s", shutter("1.5s"))
        assertNull(shutter("0"))
    }

    @Test
    fun aNewNameKeepsTheExtensionAndRefusesSeparators() {
        assertEquals("Ines 2 anos.jpg", renamedFile("20261003_173126.jpg", "  Ines 2 anos "))
        assertEquals("a b.heic", renamedFile("IMG_1.HEIC".lowercase(), "a/b"))
        assertEquals("notes", renamedFile("README", "notes"))
        assertNull(renamedFile("a.jpg", " / "))
    }
}
