package eu.studio742.imago.feature.library

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.metadata.IIOMetadataNode

class CommunityImagesTest {
    @Test fun attachmentsAreResizedReencodedAndDoNotRetainSourceMetadata() {
        val file = Files.createTempFile("forum-source", ".png").toFile()
        try {
            val image = BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply { color = Color.ORANGE; fillRect(0, 0, image.width, image.height); dispose() }
            val writer = ImageIO.getImageWritersByFormatName("png").next()
            ImageIO.createImageOutputStream(file).use { output ->
                try {
                    writer.output = output
                    val metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier(image), writer.defaultWriteParam)
                    val root = metadata.getAsTree("javax_imageio_png_1.0")
                    val text = IIOMetadataNode("tEXt")
                    text.appendChild(IIOMetadataNode("tEXtEntry").apply {
                        setAttribute("keyword", "Location"); setAttribute("value", "private-location-metadata")
                    })
                    root.appendChild(text)
                    metadata.setFromTree("javax_imageio_png_1.0", root)
                    writer.write(null, IIOImage(image, null, metadata), writer.defaultWriteParam)
                } finally { writer.dispose() }
            }
            assertTrue(file.readBytes().toString(Charsets.ISO_8859_1).contains("private-location-metadata"))
            val prepared = prepareCommunityImage(file)
            val result = ImageIO.read(prepared.bytes.inputStream())
            assertEquals(2048, result.width)
            assertEquals(1024, result.height)
            assertTrue(prepared.bytes.size <= 1_000_000)
            assertEquals(0xff, prepared.bytes[0].toInt() and 0xff)
            assertEquals(0xd8, prepared.bytes[1].toInt() and 0xff)
            assertFalse(prepared.bytes.toString(Charsets.ISO_8859_1).contains("private-location-metadata"))
        } finally { file.delete() }
    }
}
