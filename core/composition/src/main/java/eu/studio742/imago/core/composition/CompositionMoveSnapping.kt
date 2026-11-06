package eu.studio742.imago.core.composition

import kotlin.math.abs
import kotlin.math.floor

enum class GuideAxis { X, Y }

data class CompositionTextBaseline(val y: Float, val from: Float, val to: Float)

/** Transient stage geometry; these guides are never part of a saved project. */
sealed interface CompositionGuide {
    data class Alignment(val axis: GuideAxis, val coordinate: Float, val from: Float, val to: Float) : CompositionGuide
    data class Spacing(val axis: GuideAxis, val from: Float, val to: Float, val cross: Float) : CompositionGuide
}

data class MovementSnapResult(
    val dx: Float,
    val dy: Float,
    val bounds: NormalizedRect,
    val guides: List<CompositionGuide>,
)

/**
 * Keeps the pointer's unsnapped movement separate from the artwork's snapped position. Otherwise
 * small deltas disappear against a guide and the user cannot push through it. Tolerances are supplied
 * in page coordinates, converted from a fixed screen distance by the stage on every event.
 */
class CompositionMoveSnapping(
    project: CompositionProject,
    val elementIds: Set<String>,
    instanceOffsetX: Float = 0f,
    textBaselines: Map<String, List<CompositionTextBaseline>> = emptyMap(),
) {
    private val initial = checkNotNull(project.elements.filter { it.id in elementIds }
        .map { it.visualBounds(aspect = project.format.aspectRatio) }.boundingBox())
        .let { it.copy(x = it.x + instanceOffsetX.takeIf(Float::isFinite).orZero()) }
    private val pageCount = project.pages.size
    private val movingBaselines = textBaselines.filterKeys { it in elementIds }.values.flatten()
        .map { it.copy(from = it.from + instanceOffsetX, to = it.to + instanceOffsetX) }
    private val referenceBaselines = textBaselines.filterKeys { it !in elementIds }.values.flatten()
    private val references = project.elements.filter { it.id !in elementIds && it.visible && it.transform.opacity > 0f }
        .flatMap { element ->
            if (element.repeatOnPages.isEmpty()) listOf(element.visualBounds(aspect = project.format.aspectRatio))
            else element.repeatOnPages.sorted().map { element.visualBounds(element.boundsOnPage(it), project.format.aspectRatio) }
        }
    private var requestedX = 0f
    private var requestedY = 0f
    private var appliedX = 0f
    private var appliedY = 0f
    private class AxisState(var held: Float? = null, var bypassed: Float? = null)
    private val xState = AxisState()
    private val yState = AxisState()

    fun moveBy(dx: Float, dy: Float, toleranceX: Float, toleranceY: Float): MovementSnapResult {
        requestedX = (requestedX + dx.orZero()).takeIf(Float::isFinite) ?: requestedX
        requestedY = (requestedY + dy.orZero()).takeIf(Float::isFinite) ?: requestedY
        val raw = initial.copy(x = initial.x + requestedX, y = initial.y + requestedY)
        val xCandidates = candidates(GuideAxis.X, raw)
        val yCandidates = candidates(GuideAxis.Y, raw)
        val x = choose(xCandidates, raw.x, toleranceX, xState)
        val y = choose(yCandidates, raw.y, toleranceY, yState)
        val snapped = raw.copy(x = x?.position ?: raw.x, y = y?.position ?: raw.y)
        val guides = buildList {
            if (x != null) addAll(xCandidates.filter { abs(it.position - x.position) < EPSILON }
                .flatMap { it.guides(snapped) }.distinct().take(MAX_GUIDES_PER_AXIS))
            if (y != null) addAll(yCandidates.filter { abs(it.position - y.position) < EPSILON }
                .flatMap { it.guides(snapped) }.distinct().take(MAX_GUIDES_PER_AXIS))
        }
        val offsetX = snapped.x - initial.x
        val offsetY = snapped.y - initial.y
        return MovementSnapResult(offsetX - appliedX, offsetY - appliedY, snapped, guides).also {
            appliedX = offsetX
            appliedY = offsetY
        }
    }

    private data class Candidate(
        val position: Float,
        val priority: Int,
        val guides: (NormalizedRect) -> List<CompositionGuide>,
    )

    private fun choose(candidates: List<Candidate>, raw: Float, tolerance: Float, state: AxisState): Candidate? {
        if (!tolerance.isFinite() || tolerance <= 0f) { state.held = null; return null }
        // A wider release distance avoids flickering when the hand rests at the capture boundary.
        val held = state.held
        if (held != null && abs(raw - held) <= tolerance * RELEASE_DISTANCE) {
            candidates.firstOrNull { abs(it.position - held) < EPSILON }?.let { return it }
        }
        if (held != null) state.bypassed = held
        state.held = null
        // After pushing through, allow a fine adjustment back towards that guide without recapturing it.
        if (state.bypassed?.let { abs(raw - it) > tolerance * RELEASE_DISTANCE * 2f } == true) state.bypassed = null
        return candidates.filter { abs(it.position - raw) <= tolerance &&
            state.bypassed?.let { bypassed -> abs(it.position - bypassed) > tolerance } != false }
            .minWithOrNull(compareBy<Candidate> { abs(it.position - raw) }.thenBy { it.priority })
            .also { state.held = it?.position }
    }

    private fun candidates(axis: GuideAxis, moving: NormalizedRect): List<Candidate> = buildList<Candidate> {
        if (axis == GuideAxis.Y) movingBaselines.forEach { baseline ->
            referenceBaselines.filter { reference -> floor(reference.from) <= floor(moving.right - EPSILON) &&
                floor(reference.to - EPSILON) >= floor(moving.x) }.forEach { reference ->
                add(Candidate(reference.y - (baseline.y - initial.y), -1) { placed ->
                    val shift = placed.x - initial.x
                    listOf(CompositionGuide.Alignment(GuideAxis.Y, reference.y,
                        minOf(reference.from, baseline.from + shift), maxOf(reference.to, baseline.to + shift)))
                })
            }
        }
        val firstPage = floor(moving.x).toInt().coerceIn(0, pageCount - 1)
        val lastPage = floor(moving.right - EPSILON).toInt().coerceIn(firstPage, pageCount - 1)
        for (page in firstPage..lastPage) {
            addAlignments(axis, moving, NormalizedRect(page.toFloat(), 0f, 1f, 1f), priority = 0)
        }
        val relevant = references.filter { axis == GuideAxis.X || sharesPage(moving, it) }
        relevant.forEach { addAlignments(axis, moving, it, priority = 1) }
        // Only consecutive neighbours in the same row/column define an unobstructed gap.
        val neighbours = relevant.filter { overlapAcross(axis, moving, it) > EPSILON }.sortedBy { it.start(axis) }
        neighbours.zipWithNext { a, b ->
            val gap = b.start(axis) - a.end(axis)
            if (gap < -EPSILON || overlapAcross(axis, a, b) <= EPSILON) return@zipWithNext
            val length = moving.length(axis)
            if (gap >= length) {
                val position = a.end(axis) + (gap - length) / 2f
                add(Candidate(position, 2) { placed ->
                    val cross = sharedCross(axis, placed, a, b)
                    listOf(CompositionGuide.Spacing(axis, a.end(axis), placed.start(axis), cross),
                        CompositionGuide.Spacing(axis, placed.end(axis), b.start(axis), cross))
                })
            }
            if (gap > EPSILON) {
                add(Candidate(b.end(axis) + gap, 2) { placed ->
                    val cross = sharedCross(axis, placed, a, b)
                    listOf(CompositionGuide.Spacing(axis, a.end(axis), b.start(axis), cross),
                        CompositionGuide.Spacing(axis, b.end(axis), placed.start(axis), cross))
                })
                add(Candidate(a.start(axis) - gap - length, 2) { placed ->
                    val cross = sharedCross(axis, placed, a, b)
                    listOf(CompositionGuide.Spacing(axis, placed.end(axis), a.start(axis), cross),
                        CompositionGuide.Spacing(axis, a.end(axis), b.start(axis), cross))
                })
            }
        }
    }.filter { candidate ->
        if (candidate.priority != 2) true else {
            val placed = if (axis == GuideAxis.X) moving.copy(x = candidate.position) else moving.copy(y = candidate.position)
            references.none { overlapAcross(axis, placed, it) > EPSILON &&
                minOf(placed.end(axis), it.end(axis)) - maxOf(placed.start(axis), it.start(axis)) > EPSILON }
        }
    }

    private fun MutableList<Candidate>.addAlignments(
        axis: GuideAxis, moving: NormalizedRect, reference: NormalizedRect, priority: Int,
    ) {
        val length = moving.length(axis)
        for (coordinate in listOf(reference.start(axis), reference.centre(axis), reference.end(axis))) {
            for (fraction in listOf(0f, .5f, 1f)) {
                add(Candidate(coordinate - length * fraction, priority) { placed ->
                    listOf(CompositionGuide.Alignment(axis, coordinate,
                        minOf(placed.acrossStart(axis), reference.acrossStart(axis)),
                        maxOf(placed.acrossEnd(axis), reference.acrossEnd(axis))))
                })
            }
        }
    }

    private fun sharesPage(a: NormalizedRect, b: NormalizedRect): Boolean =
        floor(a.x) <= floor(b.right - EPSILON) && floor(b.x) <= floor(a.right - EPSILON)

    private fun overlapAcross(axis: GuideAxis, a: NormalizedRect, b: NormalizedRect) =
        minOf(a.acrossEnd(axis), b.acrossEnd(axis)) - maxOf(a.acrossStart(axis), b.acrossStart(axis))

    private fun sharedCross(axis: GuideAxis, vararg rectangles: NormalizedRect) =
        (rectangles.maxOf { it.acrossStart(axis) } + rectangles.minOf { it.acrossEnd(axis) }) / 2f

    private fun NormalizedRect.start(axis: GuideAxis) = if (axis == GuideAxis.X) x else y
    private fun NormalizedRect.end(axis: GuideAxis) = if (axis == GuideAxis.X) right else bottom
    private fun NormalizedRect.length(axis: GuideAxis) = if (axis == GuideAxis.X) width else height
    private fun NormalizedRect.centre(axis: GuideAxis) = (start(axis) + end(axis)) / 2f
    private fun NormalizedRect.acrossStart(axis: GuideAxis) = if (axis == GuideAxis.X) y else x
    private fun NormalizedRect.acrossEnd(axis: GuideAxis) = if (axis == GuideAxis.X) bottom else right
    private fun Float?.orZero() = this?.takeIf(Float::isFinite) ?: 0f

    private companion object {
        const val EPSILON = .00001f
        const val RELEASE_DISTANCE = 1.6f
        const val MAX_GUIDES_PER_AXIS = 8
    }
}
