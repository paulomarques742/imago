package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

/**
 * A piece of a recipe that can travel on its own, as the boxes of Lightroom's Copy Settings dialog.
 *
 * The names are stored and synced: they never change, and a new piece is a new name.
 */
@Serializable
enum class RecipePart {
    EXPOSURE, CONTRAST, HIGHLIGHTS, SHADOWS, WHITES, BLACKS, TONE_CURVE,
    WHITE_BALANCE, VIBRANCE, SATURATION, COLOR_MIXER, COLOR_GRADING,
    TEXTURE, CLARITY, DEHAZE,
    VIGNETTE, GRAIN,
    MASKS,
    CROP, STRAIGHTEN, ROTATE_AND_FLIP, PERSPECTIVE,
    ;

    companion object {
        val GEOMETRY: Set<RecipePart> = setOf(CROP, STRAIGHTEN, ROTATE_AND_FLIP, PERSPECTIVE)

        /**
         * What does not depend on where things are in the frame. A crop, a straightened horizon or a
         * gradient placed over a sky belong to the photo they were made on.
         */
        val LOOK: Set<RecipePart> = entries.toSet() - GEOMETRY - MASKS

        /**
         * What a recipe saved before the choice existed carries. Its geometry came along by accident,
         * and applying it cropped and tilted every photo it touched; its masks were there on purpose.
         */
        val LEGACY: Set<RecipePart> = entries.toSet() - GEOMETRY

        /** A stored list back as a set. Names from a newer app are skipped, not fatal. */
        fun parse(names: Iterable<String>): Set<RecipePart> =
            names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.toSet()
    }
}

/**
 * This recipe with the [parts] of [source] written over it. Everything else — the photo it belongs to,
 * its process, what no part names — stays this recipe's.
 */
fun EditRecipe.withPartsFrom(source: EditRecipe, parts: Set<RecipePart>): EditRecipe {
    fun <T> pick(part: RecipePart, theirs: T, ours: T) = if (part in parts) theirs else ours
    val tone = Tone(
        exposure = pick(RecipePart.EXPOSURE, source.tone.exposure, tone.exposure),
        contrast = pick(RecipePart.CONTRAST, source.tone.contrast, tone.contrast),
        highlights = pick(RecipePart.HIGHLIGHTS, source.tone.highlights, tone.highlights),
        shadows = pick(RecipePart.SHADOWS, source.tone.shadows, tone.shadows),
        whites = pick(RecipePart.WHITES, source.tone.whites, tone.whites),
        blacks = pick(RecipePart.BLACKS, source.tone.blacks, tone.blacks),
    )
    val presence = Presence(
        texture = pick(RecipePart.TEXTURE, source.presence.texture, presence.texture),
        clarity = pick(RecipePart.CLARITY, source.presence.clarity, presence.clarity),
        dehaze = pick(RecipePart.DEHAZE, source.presence.dehaze, presence.dehaze),
        vibrance = pick(RecipePart.VIBRANCE, source.presence.vibrance, presence.vibrance),
        saturation = pick(RecipePart.SATURATION, source.presence.saturation, presence.saturation),
    )
    val effects = effects.copy(
        vignetteAmount = pick(RecipePart.VIGNETTE, source.effects.vignetteAmount, effects.vignetteAmount),
        vignetteMidpoint = pick(RecipePart.VIGNETTE, source.effects.vignetteMidpoint, effects.vignetteMidpoint),
        vignetteRoundness = pick(RecipePart.VIGNETTE, source.effects.vignetteRoundness, effects.vignetteRoundness),
        vignetteFeather = pick(RecipePart.VIGNETTE, source.effects.vignetteFeather, effects.vignetteFeather),
        grainAmount = pick(RecipePart.GRAIN, source.effects.grainAmount, effects.grainAmount),
        grainSize = pick(RecipePart.GRAIN, source.effects.grainSize, effects.grainSize),
        grainRoughness = pick(RecipePart.GRAIN, source.effects.grainRoughness, effects.grainRoughness),
    )
    // The crop keeps its aspect lock: the lock is how the frame was drawn, not a separate setting.
    val geometry = geometry.copy(
        cropRect = pick(RecipePart.CROP, source.geometry.cropRect, geometry.cropRect),
        aspectLock = pick(RecipePart.CROP, source.geometry.aspectLock, geometry.aspectLock),
        straighten = pick(RecipePart.STRAIGHTEN, source.geometry.straighten, geometry.straighten),
        rotation = pick(RecipePart.ROTATE_AND_FLIP, source.geometry.rotation, geometry.rotation),
        mirrorH = pick(RecipePart.ROTATE_AND_FLIP, source.geometry.mirrorH, geometry.mirrorH),
        mirrorV = pick(RecipePart.ROTATE_AND_FLIP, source.geometry.mirrorV, geometry.mirrorV),
        perspective = pick(RecipePart.PERSPECTIVE, source.geometry.perspective, geometry.perspective),
    )
    return copy(
        whiteBalance = pick(RecipePart.WHITE_BALANCE, source.whiteBalance, whiteBalance),
        tone = tone,
        presence = presence,
        toneCurve = pick(RecipePart.TONE_CURVE, source.toneCurve, toneCurve),
        hsl = pick(RecipePart.COLOR_MIXER, source.hsl, hsl),
        colorGrading = pick(RecipePart.COLOR_GRADING, source.colorGrading, colorGrading),
        effects = effects,
        geometry = geometry,
        masks = pick(RecipePart.MASKS, source.masks, masks),
    )
}
