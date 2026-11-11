package eu.studio742.imago.core.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeLinksTest {
    private val id = "abcdefghijklmnopqrstuvwxyz0"
    private val site = "imago.example"

    @Test
    fun `the site address and the app's scheme both name the recipe`() {
        assertEquals(id, recipeLinkId("https", site, "/r/$id", site))
        assertEquals(id, recipeLinkId("https", site, "/r/$id/", site))
        assertEquals(id, recipeLinkId("imago", "r", "/$id", site))
    }

    @Test
    fun `an address on another host is not a recipe`() {
        assertNull(recipeLinkId("https", "evil.example", "/r/$id", site))
        assertNull(recipeLinkId("http", site, "/r/$id", site))
        assertNull(recipeLinkId("https", site, "/r/$id", null))
    }

    @Test
    fun `other paths and malformed identifiers are not recipes`() {
        assertNull(recipeLinkId("https", site, "/forum/", site))
        assertNull(recipeLinkId("https", site, "/r/", site))
        assertNull(recipeLinkId("https", site, "/r/short", site))
        assertNull(recipeLinkId("https", site, "/r/a/../../b/cdefghijklmnopqrstu", site))
        assertNull(recipeLinkId("imago", "auth", "/$id", site))
    }

    @Test
    fun `the address has one slash whatever the base ends with`() {
        assertEquals("https://imago.example/r/$id", recipeLinkUrl("https://imago.example/", id))
        assertEquals("https://imago.example/r/$id", recipeLinkUrl("https://imago.example", id))
    }

    @Test
    fun `a link that is gone and one that failed are told apart from a damaged file`() {
        val plan = planRecipeImport(
            listOf(
                ReadImagoFile("a", ImagoFileContent.LinkGone),
                ReadImagoFile("b", ImagoFileContent.LinkFailed),
                ReadImagoFile("c", ImagoFileContent.Unreadable),
            ),
            emptyList(),
            "now",
            { "id" },
        )
        assertEquals(1, plan.linksGone)
        assertEquals(1, plan.linksFailed)
        assertEquals(1, plan.unreadableFiles)
    }
}
