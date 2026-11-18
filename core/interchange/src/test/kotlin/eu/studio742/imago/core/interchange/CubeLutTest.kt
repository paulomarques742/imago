package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.MAX_LUT_SIZE
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CubeLutTest {
    private fun cube3d(size: Int, header: String = "", map: (Float, Float, Float) -> Triple<Float, Float, Float>): String =
        buildString {
            append(header)
            append("LUT_3D_SIZE $size\n")
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val (x, y, z) = map(r / (size - 1f), g / (size - 1f), b / (size - 1f))
                append("$x $y $z\n")
            }
        }

    private fun samplesOf(text: String, name: String = "file") = readCubeLut(text, name)!!.samples()!!

    @Test
    fun aThreeDimensionalTableKeepsItsGridAndItsOrder() {
        // The red input goes to the output's blue: wrong if the file's order is read blue-fastest.
        val lut = readCubeLut(cube3d(2) { r, _, _ -> Triple(0f, 0f, r) }, "swap")!!
        assertEquals(2, lut.size)
        val samples = lut.samples()!!
        // Point (r=1, g=0, b=0) is the second one in the grid.
        assertEquals(1f, samples[1 * 3 + 2], 1e-4f)
        assertEquals(0f, samples[2 * 3 + 2], 1e-4f)
    }

    @Test
    fun theTitleBecomesTheNameAndTheFileNameIsTheFallback() {
        assertEquals("Teal & Orange", readCubeLut("TITLE \"Teal & Orange\"\n" + cube3d(2) { r, g, b -> Triple(r, g, b) }, "file")!!.name)
        assertEquals("file", readCubeLut(cube3d(2) { r, g, b -> Triple(r, g, b) }, "file")!!.name)
    }

    @Test
    fun commentsBlankLinesAndWindowsLineEndingsAreFine() {
        val text = "# made by hand\r\n\r\nLUT_3D_SIZE 2 # eight points\r\n" + (0 until 8).joinToString("\r\n") { "0.1 0.2 0.3" } + "\r\n"
        assertEquals(24, samplesOf(text).size)
    }

    @Test
    fun aBiggerGridIsSampledDownToWhatARecipeKeeps() {
        // 65 -> 33 lands on every other point, so a linear ramp comes through exactly.
        val lut = readCubeLut(cube3d(65) { r, g, b -> Triple(r, g * 0.5f, 1 - b) }, "big")!!
        assertEquals(MAX_LUT_SIZE, lut.size)
        val samples = lut.samples()!!
        val last = MAX_LUT_SIZE - 1
        val corner = ((last * MAX_LUT_SIZE + last) * MAX_LUT_SIZE + last) * 3
        assertEquals(1f, samples[corner], 1e-3f)
        assertEquals(0.5f, samples[corner + 1], 1e-3f)
        assertEquals(0f, samples[corner + 2], 1e-3f)
    }

    @Test
    fun aDomainOtherThanZeroToOneIsFoldedIntoTheGrid() {
        // Domain 0..2 with a table that outputs its own input: the colour 1.0 sits in the middle of the
        // table, whose output there is 1.0 on the table's own scale of 0..2 clamped... the point is the
        // lookup position, so the table is a ramp from 0 to 1 and 1.0 must read 0.5.
        val text = cube3d(3, header = "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n") { r, _, _ -> Triple(r, r, r) }
        val lut = readCubeLut(text, "wide")!!
        val samples = lut.samples()!!
        val last = lut.size - 1
        val redEnd = last * 3 // red = 1.0, green = blue = 0
        assertEquals(0.5f, samples[redEnd], 1e-3f)
    }

    @Test
    fun aOneDimensionalCurveBecomesAThreeDimensionalTable() {
        val text = "LUT_1D_SIZE 3\n0 0 0\n0.8 0.5 0.2\n1 1 1\n"
        val lut = readCubeLut(text, "curve")!!
        val samples = lut.samples()!!
        val middle = lut.size / 2
        val index = ((middle * lut.size + middle) * lut.size + middle) * 3
        assertEquals(0.8f, samples[index], 0.02f)
        assertEquals(0.5f, samples[index + 1], 0.02f)
        assertEquals(0.2f, samples[index + 2], 0.02f)
    }

    @Test
    fun filesThatAreNotUsableAreRefused() {
        assertNull(readCubeLut("LUT_3D_SIZE 2\n0 0 0\n", "short"))
        assertNull(readCubeLut(cube3d(2) { r, g, b -> Triple(r, g, b) } + "0 0 0\n", "long"))
        assertNull(readCubeLut("LUT_3D_SIZE 2\n" + "0 0\n".repeat(8), "twoColumns"))
        assertNull(readCubeLut("LUT_3D_SIZE 2\n" + "0 0 abc\n".repeat(8), "text"))
        assertNull(readCubeLut("LUT_3D_SIZE 2\n" + "0 0 NaN\n".repeat(8), "nan"))
        assertNull(readCubeLut("0 0 0\nLUT_3D_SIZE 2\n", "dataFirst"))
        assertNull(readCubeLut("LUT_3D_SIZE 1\n0 0 0\n", "tiny"))
        assertNull(readCubeLut("LUT_3D_SIZE 4000\n0 0 0\n", "huge"))
        assertNull(readCubeLut("LUT_3D_SIZE 2\nLUT_1D_SIZE 2\n", "both"))
        assertNull(readCubeLut(cube3d(2, header = "DOMAIN_MIN 1 1 1\nDOMAIN_MAX 1 1 1\n") { r, g, b -> Triple(r, g, b) }, "flat"))
    }

    @Test
    fun anImportedFileIsARecipeWithTheLutAndNothingElse() {
        val content = decodeRecipeFile("Kodak 2383.cube", cube3d(2, header = "TITLE \"Kodak\"\n") { r, g, b -> Triple(r, g, b) }.encodeToByteArray())
        val entry = (content as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.Ready
        assertEquals("Kodak", entry.name)
        assertEquals(setOf(RecipePart.LUT), entry.parts)
        assertEquals(RecipeOrigin.CUBE, entry.origin)
        assertNotNull(entry.recipe.lut)
    }

    @Test
    fun aFileThatLooksLikeNothingWeReadIsStillUnreadable() {
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.cube", "hello".encodeToByteArray()))
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.cube", ByteArray(0)))
    }

    @Test
    fun aPackOfLutsEntersAsACollectionAndABadOneIsNamedWithoutSinkingTheRest() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("Pack/Warm.cube")); zip.write(cube3d(2) { r, g, b -> Triple(r, g, b) }.encodeToByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("Pack/Broken.cube")); zip.write("LUT_3D_SIZE 2\n0 0 0\n".encodeToByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val content = decodeRecipeFile("Pack.zip", zip) as ImagoFileContent.Recipes
        assertEquals(2, content.entries.size)
        assertEquals(0, content.skippedFiles)
        val ready = content.entries.filterIsInstance<ImagoFileEntry.Ready>().single()
        assertEquals("Warm", ready.name)
        assertEquals("Pack", ready.collection)
        assertTrue(content.entries.any { it === ImagoFileEntry.Invalid })
    }
}
