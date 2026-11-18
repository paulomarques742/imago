package eu.studio742.imago.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeOriginTest {
    @Test fun `a known format is read back`() {
        RecipeOrigin.entries.forEach { assertEquals(it, RecipeOrigin.parse(it.name)) }
    }

    @Test fun `no value means a recipe made in IMAGO`() {
        assertNull(RecipeOrigin.parse(null))
        assertNull(RecipeOrigin.parse(""))
    }

    @Test fun `a format only a newer app knows still counts as imported`() {
        assertEquals(RecipeOrigin.OTHER, RecipeOrigin.parse("COSTYLE"))
    }
}
