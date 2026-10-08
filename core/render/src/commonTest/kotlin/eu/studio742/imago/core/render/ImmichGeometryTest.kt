package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.ImageSize
import eu.studio742.imago.core.model.ImmichEdit
import eu.studio742.imago.core.model.Perspective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichGeometryTest {
    private val size = ImageSize(4000, 3000)

    private fun recipe(geometry: Geometry, processVersion: Int = 10) = EditRecipe(
        assetId = "a1",
        originalChecksum = "",
        processVersion = processVersion,
        createdAt = "2026-10-07T00:00:00Z",
        updatedAt = "2026-10-07T00:00:00Z",
        geometry = geometry,
    )

    @Test
    fun aNeutralGeometryIsNoEdit() {
        assertEquals(emptyList<ImmichEdit>(), recipe(Geometry()).immichEdits(size))
    }

    @Test
    fun theEditsComeInTheOrderTheServerRequires() {
        val edits = recipe(
            Geometry(cropRect = CropRect(0.1f, 0.2f, 0.5f, 0.6f), rotation = 90, mirrorH = true, mirrorV = true),
        ).immichEdits(size)

        assertTrue(edits[0] is ImmichEdit.Crop)
        assertEquals(
            listOf(
                ImmichEdit.Rotate(90),
                ImmichEdit.Mirror(ImmichEdit.MirrorAxis.VERTICAL),
                ImmichEdit.Mirror(ImmichEdit.MirrorAxis.HORIZONTAL),
            ),
            edits.drop(1),
        )
    }

    /**
     * What the server shows is what the stage shows: every point of the frame goes through Immich's
     * own pipeline — crop, then rotation and mirrors in the list's order, as `transformPoints` does
     * on the server — and has to land where the stage puts it.
     */
    @Test
    fun theServerFramesThePhotoAsTheStageDoes() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (mirrorH in listOf(false, true)) {
                for (mirrorV in listOf(false, true)) {
                    val parameters = recipe(
                        Geometry(cropRect = CropRect(0.1f, 0.2f, 0.5f, 0.6f), rotation = rotation, mirrorH = mirrorH, mirrorV = mirrorV),
                    )
                    val edits = parameters.immichEdits(size)
                    val stage = parameters.toRenderParameters().frameGeometry(size.width, size.height)
                    val image = FloatArray(2)
                    for ((u, v) in listOf(0.25f to 0.25f, 0.8f to 0.3f, 0.4f to 0.9f, 0.5f to 0.5f)) {
                        stage.imageFromFramed(u, v, image)
                        val (x, y, outputWidth, outputHeight) =
                            serverPipeline(image[0] * size.width, image[1] * size.height, edits, size)
                        val case = "rotation $rotation, mirrorH $mirrorH, mirrorV $mirrorV, point ($u, $v)"
                        assertEquals(case, u, x / outputWidth, 0.002f)
                        assertEquals(case, v, y / outputHeight, 0.002f)
                    }
                }
            }
        }
    }

    /**
     * A straightened crop is a tilted rectangle Immich cannot express. What goes has to stay inside
     * it — the thumbnail never shows what the crop left out — and keep its centre.
     */
    @Test
    fun aStraightenedCropSendsAnUprightRectangleInsideIt() {
        for (rotation in listOf(0, 90)) {
            for (straighten in listOf(-30f, -4f, 12f)) {
                val parameters = recipe(
                    Geometry(cropRect = CropRect(0.2f, 0.1f, 0.6f, 0.7f), straighten = straighten, rotation = rotation),
                )
                val crop = parameters.immichEdits(size).first() as ImmichEdit.Crop
                val stage = parameters.toRenderParameters().frameGeometry(size.width, size.height)
                val framed = FloatArray(2)
                val corners = listOf(
                    crop.x to crop.y,
                    crop.x + crop.width to crop.y,
                    crop.x to crop.y + crop.height,
                    crop.x + crop.width to crop.y + crop.height,
                )
                for ((x, y) in corners) {
                    stage.framedFromImage(x.toFloat() / size.width, y.toFloat() / size.height, framed)
                    val case = "rotation $rotation, straighten $straighten, corner ($x, $y)"
                    assertTrue(case, framed[0] in -0.001f..1.001f && framed[1] in -0.001f..1.001f)
                }
                stage.framedFromImage(
                    (crop.x + crop.width / 2f) / size.width,
                    (crop.y + crop.height / 2f) / size.height,
                    framed,
                )
                assertEquals(0.5f, framed[0], 0.01f)
                assertEquals(0.5f, framed[1], 0.01f)
            }
        }
    }

    /** A straighten without a crop still trims: the tilted frame is what the stage shows. */
    @Test
    fun aStraightenAloneStillCrops() {
        val crop = recipe(Geometry(straighten = 10f)).immichEdits(size).single() as ImmichEdit.Crop

        assertTrue(crop.width < size.width && crop.height < size.height)
        assertTrue(crop.x + crop.width <= size.width && crop.y + crop.height <= size.height)
    }

    /** The server refuses a crop that leaves the image; rounding must never take it there. */
    @Test
    fun aCropToTheEdgeStaysInsideTheImage() {
        val crop = recipe(Geometry(cropRect = CropRect(0.5f, 0.5f, 0.5f, 0.5f), rotation = 270))
            .immichEdits(size).first() as ImmichEdit.Crop

        assertTrue(crop.x + crop.width <= size.width)
        assertTrue(crop.y + crop.height <= size.height)
    }

    /**
     * A tilted or stretched photo is no crop. What reaches Immich is the export, and the original
     * keeps no edits — the empty list removes any the server held.
     */
    @Test
    fun aPerspectiveSendsNoEdits() {
        for (perspective in listOf(
            Perspective(vertical = -20f),
            Perspective(horizontal = 5f),
            Perspective(aspect = 10f),
            Perspective(upright = eu.studio742.imago.core.model.UPRIGHT_GUIDED, uprightPitch = 8f),
        )) {
            val edits = recipe(Geometry(cropRect = CropRect(0.1f, 0.1f, 0.5f, 0.5f), rotation = 90, perspective = perspective))
                .immichEdits(size)
            assertEquals("$perspective", emptyList<ImmichEdit>(), edits)
        }
    }

    /** Scale and offsets only move and zoom the frame: that is still a crop Immich can apply. */
    @Test
    fun aZoomedFrameIsStillACrop() {
        val crop = recipe(Geometry(perspective = Perspective(scale = 50f, offsetX = 30f))).immichEdits(size).single() as ImmichEdit.Crop

        assertTrue(crop.width < size.width && crop.height < size.height)
    }

    /** A recipe from before the crop existed keeps it dormant on the server too. */
    @Test
    fun theProcessVersionGatesApplyToTheServerToo() {
        val edits = recipe(Geometry(cropRect = CropRect(0.1f, 0.1f, 0.5f, 0.5f), rotation = 90), processVersion = 5)
            .immichEdits(size)

        assertEquals(listOf<ImmichEdit>(ImmichEdit.Rotate(90)), edits)
    }

    private data class ServerPoint(val x: Float, val y: Float, val width: Float, val height: Float)

    /** A transcription of Immich's `transformPoints` (server/src/utils/transform.ts, v2.6.0). */
    private fun serverPipeline(x0: Float, y0: Float, edits: List<ImmichEdit>, start: ImageSize): ServerPoint {
        var x = x0
        var y = y0
        var width = start.width.toFloat()
        var height = start.height.toFloat()
        edits.filterIsInstance<ImmichEdit.Crop>().firstOrNull()?.let { crop ->
            x -= crop.x
            y -= crop.y
            width = crop.width.toFloat()
            height = crop.height.toFloat()
        }
        for (edit in edits) {
            when (edit) {
                is ImmichEdit.Rotate -> {
                    val swaps = edit.angle == 90 || edit.angle == 270
                    val newWidth = if (swaps) height else width
                    val newHeight = if (swaps) width else height
                    val radians = Math.toRadians(edit.angle.toDouble())
                    val cosine = kotlin.math.cos(radians).toFloat()
                    val sine = kotlin.math.sin(radians).toFloat()
                    val centeredX = x - width / 2f
                    val centeredY = y - height / 2f
                    x = cosine * centeredX - sine * centeredY + newWidth / 2f
                    y = sine * centeredX + cosine * centeredY + newHeight / 2f
                    width = newWidth
                    height = newHeight
                }
                is ImmichEdit.Mirror -> when (edit.axis) {
                    ImmichEdit.MirrorAxis.HORIZONTAL -> y = height - y
                    ImmichEdit.MirrorAxis.VERTICAL -> x = width - x
                }
                is ImmichEdit.Crop -> Unit
            }
        }
        return ServerPoint(x, y, width, height)
    }
}
