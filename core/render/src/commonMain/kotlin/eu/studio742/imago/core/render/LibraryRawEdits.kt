package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * The edits of server library RAWs, kept developed for the grid and the viewer ([developedLibraryRaw]).
 *
 * The editor has the development in hand and leaves it behind: when it is left, the edit is rendered over
 * it and kept at both sides, so that the grid and the viewer show the edit straight away instead of an
 * approximation, without downloading the original again. A later save — here or from another device —
 * names the edit differently, and until it is kept again they show the approximation.
 */
object LibraryRawEdits {
    // One at a time and off the main thread: a render at the viewer's side takes a moment on a phone.
    private val renders = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val generation = MutableStateFlow(0)

    /**
     * Goes up each time an edit has been kept. The screens that show [developedLibraryRaw] images ask for
     * them again when it does: an image already on screen does not look for a develop kept after it loaded.
     */
    val kept: StateFlow<Int> = generation.asStateFlow()

    /**
     * Keeps [recipe] over [development], the RAW of [raw] developed for it at up to [LIBRARY_RAW_FULL_SIDE].
     * [rawExposure] is the exposure that matched the camera, for a recipe that has not recorded its own.
     * Nothing is done when both sides are kept already, or when the recipe cannot be rendered here.
     */
    fun keep(raw: LibraryRaw, recipe: EditRecipe, development: FloatPixelBuffer, rawExposure: Float): Job = renders.launch {
        if (SIDES.all { RecipeRenderCache.contains(libraryRawKey(raw, recipe, it)) }) return@launch
        runCatching {
            requireRawEngine(recipe)
            val parameters = recipe.toRenderParameters(rawExposure = recipe.rawDevelopment?.exposure ?: rawExposure)
            keepRendered(raw, recipe, RecipePixels.renderFloat(development, parameters))
        }
    }

    /**
     * Writes [rendered], [recipe] over the RAW of [raw] at its full side, reduced to each of [sides], and
     * tells [kept] once they are on disk.
     */
    internal fun keepRendered(
        raw: LibraryRaw,
        recipe: EditRecipe,
        rendered: FloatPixelBuffer,
        sides: List<Int> = SIDES,
    ): Job = renders.launch {
        sides.mapNotNull { side ->
            RecipeRenderCache.write(libraryRawKey(raw, recipe, side)) {
                RecipePixels.reduce(rendered, side).toPixelBuffer().toPlatformBitmap().encodeForRenderCache()
            }
        }.joinAll()
        generation.update { it + 1 }
    }

    private val SIDES = listOf(LIBRARY_RAW_TILE_SIDE, LIBRARY_RAW_FULL_SIDE)
}
