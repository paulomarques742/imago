package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.CURRENT_RAW_ENGINE
import eu.studio742.imago.core.model.CURRENT_RAW_RENDERING
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RAW_PROFILE_COLOR
import eu.studio742.imago.core.model.RAW_RENDERING_LINEAR
import eu.studio742.imago.core.model.RawDevelopment
import eu.studio742.imago.core.model.needsNewerRawRendering
import eu.studio742.imago.core.model.rawEngine
import eu.studio742.imago.core.model.rawRendering
import kotlin.math.ln
import kotlin.math.pow

/**
 * Moves a RAW edited under an older development to the current one without the photo changing its middle
 * grey.
 *
 * A recipe keeps the development it was made under (`RawDevelopment`), so an update never changes what
 * somebody finished. This is the way forward when they want the better one: rendering 1 had no base
 * exposure and no profile, so its mid-grey sits where the sRGB transfer puts it; the current development
 * puts it where [RawProfile]'s curve does, and the Exposure slider is moved by the difference. Rendering 2
 * only changes the engine, which develops highlights differently and leaves the mid-tones where they were.
 */
object RawDevelopmentUpgrade {
    private const val MID_GREY = 0.18f
    private const val LN2 = 0.6931472f

    /** The Exposure slider's range. */
    private const val EXPOSURE_LIMIT = 5f

    /** Whether the current development is not the one this recipe was made under. */
    fun isAvailable(recipe: EditRecipe): Boolean =
        recipe.rawDevelopment != null && !recipe.needsNewerRawRendering() &&
            (recipe.rawRendering() < CURRENT_RAW_RENDERING || recipe.rawEngine() != CURRENT_RAW_ENGINE)

    /**
     * The Exposure, in stops, that shows middle grey where the old development did.
     *
     * [oldExposure] is the slider under the old rendering; [baseline] the DNG's BaselineExposure that the
     * new decode applies and the old one did not, and [measured] the exposure that matches the camera's JPEG.
     */
    fun exposureFor(oldExposure: Float, baseline: Float, measured: Float): Float {
        val shown = encodeSrgb(MID_GREY * 2f.pow(oldExposure))
        val light = RawProfile.inverse(shown)
        val stops = ln(light / MID_GREY) / LN2 - baseline - measured
        return stops.coerceIn(-EXPOSURE_LIMIT, EXPOSURE_LIMIT)
    }

    /**
     * [recipe] under the current development, or null when it already is. [measured] and [baseline] come from
     * decoding the original for it; they only matter to rendering 1.
     */
    fun upgrade(recipe: EditRecipe, measured: Float, baseline: Float): EditRecipe? {
        if (!isAvailable(recipe)) return null
        val old = recipe.rawDevelopment ?: return null
        if (recipe.rawRendering() == RAW_RENDERING_LINEAR) {
            return recipe.copy(
                tone = recipe.tone.copy(exposure = exposureFor(recipe.tone.exposure, baseline, measured)),
                profile = RAW_PROFILE_COLOR,
                rawDevelopment = RawDevelopment(CURRENT_RAW_ENGINE, CURRENT_RAW_RENDERING, measured),
            )
        }
        return recipe.copy(
            rawDevelopment = old.copy(engine = CURRENT_RAW_ENGINE, rendering = CURRENT_RAW_RENDERING),
        )
    }
}
