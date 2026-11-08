package eu.studio742.imago.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagoThemeRevealTest {
    private val screen = Size(300f, 400f)

    @Test
    fun `from a corner the circle crosses the whole diagonal`() {
        assertEquals(500f, revealRadius(Offset.Zero, screen), 1e-3f)
        assertEquals(500f, revealRadius(Offset(300f, 400f), screen), 1e-3f)
    }

    @Test
    fun `from the middle it only has to reach a corner`() {
        assertEquals(250f, revealRadius(Offset(150f, 200f), screen), 1e-3f)
    }

    @Test
    fun `from anywhere it reaches the furthest corner`() {
        assertEquals(360.555f, revealRadius(Offset(100f, 100f), screen).let { it - 0f }, 1f)
    }

    /** Reduce Motion asks for no movement: the theme just changes, at once and only once. */
    @Test
    fun `with reduced motion the theme changes without a circle`() {
        var changes = 0
        val reveal = ThemeReveal(CoroutineScope(Dispatchers.Unconfined), reducedMotion = true)

        reveal.reveal(Offset(10f, 10f)) { changes++ }

        assertEquals(1, changes)
        assertEquals(null, reveal.picture)
    }
}
