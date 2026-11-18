package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.ml.MaskModel
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.GeneratedWeights
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedMaskEditingTest {
    private val subjectWeights = GeneratedWeights.of("u2netp", 1, 2, 1, byteArrayOf(0, -1))
    private val skyWeights = GeneratedWeights.of("ppmobileseg-base-ade20k-sky", 1, 2, 1, byteArrayOf(-1, 0))
    private val oldSky = GeneratedWeights.of("ppmobileseg-base-ade20k-sky", 1, 2, 1, byteArrayOf(10, 10))

    private fun recipe(vararg components: MaskComponent) = EditRecipe(
        assetId = "a",
        originalChecksum = "",
        createdAt = "",
        updatedAt = "",
        masks = listOf(LocalMask(id = "m", components = components.toList())),
    )

    @Test
    fun backgroundRunsTheSubjectsModel() {
        assertEquals(MaskModel.SUBJECT, MaskShape.SUBJECT.maskModel)
        assertEquals(MaskModel.SUBJECT, MaskShape.BACKGROUND.maskModel)
        assertEquals(MaskModel.SKY, MaskShape.SKY.maskModel)
        assertNull(MaskShape.BRUSH.maskModel)
    }

    @Test
    fun onlyShapesWithoutWeightsNeedTheirModel() {
        val edit = recipe(
            MaskComponent(shape = MaskShape.BACKGROUND),
            MaskComponent(shape = MaskShape.SKY, op = MaskOp.SUBTRACT, generated = oldSky),
            MaskComponent(shape = MaskShape.BRUSH),
        )
        assertEquals(setOf(MaskModel.SUBJECT), edit.missingMaskModels)
        assertTrue(recipe(MaskComponent(shape = MaskShape.SKY, generated = oldSky)).missingMaskModels.isEmpty())
    }

    @Test
    fun foundWeightsFillTheEmptyShapesAndLeaveTheOthers() {
        val edit = recipe(
            MaskComponent(shape = MaskShape.SUBJECT),
            MaskComponent(shape = MaskShape.BACKGROUND, op = MaskOp.INTERSECT),
            MaskComponent(shape = MaskShape.SKY, generated = oldSky),
        )
        val filled = edit.withGeneratedWeights(mapOf(MaskModel.SUBJECT to subjectWeights, MaskModel.SKY to skyWeights))
        val components = filled.masks.single().components
        assertEquals(subjectWeights, components[0].generated)
        assertEquals(subjectWeights, components[1].generated)
        assertEquals(MaskOp.INTERSECT, components[1].op)
        // A shape already generated on this photo keeps what it had: it is never recomputed.
        assertEquals(oldSky, components[2].generated)
        assertSame(filled, filled.withGeneratedWeights(mapOf(MaskModel.SKY to skyWeights)))
    }

    @Test
    fun aNewGeneratedShapeStartsEmpty() {
        for (shape in listOf(MaskShape.SUBJECT, MaskShape.SKY, MaskShape.BACKGROUND)) {
            val component = newMaskComponent(shape)
            assertEquals(shape, component.shape)
            assertNull(component.generated)
            assertTrue(shape.usesRasterLayer)
        }
    }
}
