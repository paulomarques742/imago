package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.ColorLut
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.MAX_LUT_SIZE
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart

/** The biggest 3D grid read: 129³ is 2 million lines, which is already more than any photographic LUT has. */
private const val CUBE_MAX_3D_SIZE = 129
private const val CUBE_MAX_1D_SIZE = 65_536
private const val CUBE_HEAD_BYTES = 4096

/**
 * The text of a `.cube` file, or null when the bytes are not one. By content, like every other format: a
 * `.cube` has a size line near the top, and nothing else this app reads has one.
 */
internal fun cubeText(bytes: ByteArray): String? {
    if (bytes.isEmpty() || bytes.size > RECIPE_FILE_MAX_BYTES) return null
    val head = bytes.copyOf(minOf(bytes.size, CUBE_HEAD_BYTES)).decodeToString()
    if (!CUBE_SIZE_LINE.containsMatchIn(head)) return null
    return bytes.decodeToString().removePrefix("﻿")
}

private val CUBE_SIZE_LINE = Regex("""(?m)^\s*LUT_[13]D_SIZE\s+\d+""")

/**
 * A look from a `.cube` file as a recipe that holds only the LUT, or null if the file is not a usable one.
 *
 * A 3D table keeps its grid up to [MAX_LUT_SIZE] and is sampled down to it beyond; a 1D table (one curve
 * per channel) becomes the 3D table that does the same, since the render has one kind of LUT. A domain
 * other than 0..1 is folded into the grid, so that the table is always read over the colour's own range.
 */
internal fun cubeEntryOf(bytes: ByteArray?, fallbackName: String, collection: String): ImagoFileEntry? {
    val text = bytes?.let(::cubeText) ?: return null
    val lut = readCubeLut(text, fallbackName) ?: return null
    val blank = EditRecipe(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "")
    return ImagoFileEntry.Ready(
        name = lut.name.ifEmpty { cleanText(fallbackName, NAME_LIMIT_CUBE) },
        collection = collection,
        parts = setOf(RecipePart.LUT),
        recipe = blank.copy(lut = lut),
        origin = RecipeOrigin.CUBE,
    )
}

private const val NAME_LIMIT_CUBE = 120

internal fun readCubeLut(text: String, fallbackName: String): ColorLut? {
    var title = ""
    var size3 = 0
    var size1 = 0
    val domainMin = floatArrayOf(0f, 0f, 0f)
    val domainMax = floatArrayOf(1f, 1f, 1f)
    var values = FloatArray(0)
    var count = 0
    var expected = 0

    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val first = line[0]
        if (first.isLetter()) {
            val parts = line.split(WHITESPACE)
            when (parts[0].uppercase()) {
                "TITLE" -> title = cleanText(line.substringAfter(' ', "").trim().trim('"'), 80)
                "LUT_3D_SIZE" -> {
                    size3 = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    if (size3 !in 2..CUBE_MAX_3D_SIZE) return null
                }
                "LUT_1D_SIZE" -> {
                    size1 = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    if (size1 !in 2..CUBE_MAX_1D_SIZE) return null
                }
                "DOMAIN_MIN" -> readTriple(parts, domainMin) || return null
                "DOMAIN_MAX" -> readTriple(parts, domainMax) || return null
                // Resolve's way of saying the same as the domain, for all three channels at once.
                "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE" -> {
                    val low = parts.getOrNull(1)?.toFloatOrNull() ?: return null
                    val high = parts.getOrNull(2)?.toFloatOrNull() ?: return null
                    for (channel in 0..2) { domainMin[channel] = low; domainMax[channel] = high }
                }
                // Anything else a tool adds before the data says nothing this app uses.
                else -> Unit
            }
            if (expected == 0 && (size3 > 0 || size1 > 0)) {
                if (size3 > 0 && size1 > 0) return null
                expected = if (size3 > 0) size3 * size3 * size3 else size1
                values = FloatArray(expected * 3)
            }
            continue
        }
        // A data line, which can only come once the size is known.
        if (expected == 0 || count >= expected) return null
        val numbers = line.split(WHITESPACE)
        if (numbers.size != 3) return null
        for (channel in 0..2) {
            val number = numbers[channel].toFloatOrNull() ?: return null
            if (!number.isFinite()) return null
            values[count * 3 + channel] = number
        }
        count++
    }
    if (expected == 0 || count != expected) return null
    for (channel in 0..2) if (!(domainMax[channel] > domainMin[channel])) return null

    val name = title.ifEmpty { cleanText(fallbackName, NAME_LIMIT_CUBE) }
    val grid = if (size3 > 0) size3 else MAX_LUT_SIZE
    val target = minOf(grid, MAX_LUT_SIZE)
    val plain = domainMin.all { it == 0f } && domainMax.all { it == 1f }
    val samples = when {
        size3 > 0 && plain && size3 == target -> values.copyOf()
        size3 > 0 -> resample3d(values, size3, target, domainMin, domainMax)
        else -> from1d(values, size1, target, domainMin, domainMax)
    }
    return ColorLut.fromSamples(name, target, samples)
}

private val WHITESPACE = Regex("\\s+")

private fun readTriple(parts: List<String>, into: FloatArray): Boolean {
    if (parts.size != 4) return false
    for (channel in 0..2) {
        val number = parts[channel + 1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return false
        into[channel] = number
    }
    return true
}

/**
 * The table read at [target]³ points of the colour's own 0..1 range: each point is looked up, trilinearly,
 * in the file's grid at the position its domain puts it. Reading a 65 grid at 33 lands on every other
 * point, so the usual resize loses nothing but the points in between.
 */
private fun resample3d(values: FloatArray, size: Int, target: Int, domainMin: FloatArray, domainMax: FloatArray): FloatArray {
    val out = FloatArray(target * target * target * 3)
    val last = (size - 1).toFloat()
    val position = FloatArray(3)
    var at = 0
    for (b in 0 until target) for (g in 0 until target) for (r in 0 until target) {
        position[0] = r / (target - 1f)
        position[1] = g / (target - 1f)
        position[2] = b / (target - 1f)
        val cell = FloatArray(3) { channel ->
            ((position[channel] - domainMin[channel]) / (domainMax[channel] - domainMin[channel])).coerceIn(0f, 1f) * last
        }
        val r0 = minOf(cell[0].toInt(), size - 2)
        val g0 = minOf(cell[1].toInt(), size - 2)
        val b0 = minOf(cell[2].toInt(), size - 2)
        val fr = cell[0] - r0
        val fg = cell[1] - g0
        val fb = cell[2] - b0
        for (channel in 0..2) {
            fun v(dr: Int, dg: Int, db: Int) = values[(((b0 + db) * size + (g0 + dg)) * size + (r0 + dr)) * 3 + channel]
            val c00 = v(0, 0, 0) + (v(1, 0, 0) - v(0, 0, 0)) * fr
            val c10 = v(0, 1, 0) + (v(1, 1, 0) - v(0, 1, 0)) * fr
            val c01 = v(0, 0, 1) + (v(1, 0, 1) - v(0, 0, 1)) * fr
            val c11 = v(0, 1, 1) + (v(1, 1, 1) - v(0, 1, 1)) * fr
            val c0 = c00 + (c10 - c00) * fg
            val c1 = c01 + (c11 - c01) * fg
            out[at++] = c0 + (c1 - c0) * fb
        }
    }
    return out
}

/** One curve per channel, as the 3D table that applies each to its own channel. */
private fun from1d(values: FloatArray, size: Int, target: Int, domainMin: FloatArray, domainMax: FloatArray): FloatArray {
    val out = FloatArray(target * target * target * 3)
    val last = (size - 1).toFloat()
    fun curve(channel: Int, input: Float): Float {
        val cell = ((input - domainMin[channel]) / (domainMax[channel] - domainMin[channel])).coerceIn(0f, 1f) * last
        val low = minOf(cell.toInt(), size - 2)
        val fraction = cell - low
        return values[low * 3 + channel] + (values[(low + 1) * 3 + channel] - values[low * 3 + channel]) * fraction
    }
    var at = 0
    for (b in 0 until target) for (g in 0 until target) for (r in 0 until target) {
        out[at++] = curve(0, r / (target - 1f))
        out[at++] = curve(1, g / (target - 1f))
        out[at++] = curve(2, b / (target - 1f))
    }
    return out
}
