package eu.studio742.imago.core.composition

import kotlin.math.abs
import kotlin.math.floor

data class ResizeSnapResult(val elements: List<CompositionElement>, val guides: List<CompositionGuide>)

/**
 * Solves alignment constraints through the anchored resize itself. Translating a snapped bounding
 * box would move the opposite corner, and treating rotated page coordinates as square would distort
 * the handle's movement on portrait pages.
 */
class CompositionResizeSnapping(
    project: CompositionProject,
    val elementIds: Set<String>,
    private val corner: ResizeCorner,
    private val instanceOffsetX: Float = 0f,
) {
    private val original = project.elements.filter { it.id in elementIds }
    private val aspect = project.format.aspectRatio
    private val pageCount = project.pages.size
    private val references = project.elements.filter { it.id !in elementIds && it.visible && it.transform.opacity > 0f }
        .flatMap { element ->
            if (element.repeatOnPages.isEmpty()) listOf(element.visualBounds(aspect = aspect))
            else element.repeatOnPages.sorted().map { element.visualBounds(element.boundsOnPage(it), aspect) }
        }
    private var requestedX = 0f
    private var requestedY = 0f
    private data class Key(val axis: GuideAxis, val reference: Int, val anchor: Int, val other: Int = -1, val kind: Int = 0)
    private class AxisState(var held: Key? = null, var bypassed: Key? = null)
    private val xState = AxisState()
    private val yState = AxisState()
    private data class Geometry(val elements: List<CompositionElement>, val bounds: NormalizedRect)
    private data class Target(
        val key: Key, val priority: Int, val desiredStart: (NormalizedRect) -> Float,
        val guides: (NormalizedRect) -> List<CompositionGuide>,
    ) {
        fun error(bounds: NormalizedRect) = bounds.start(key.axis) - desiredStart(bounds)
    }
    private data class Constraint(val target: Target, val error: Float, val dx: Float, val dy: Float)

    fun resizeBy(dx: Float, dy: Float, toleranceX: Float, toleranceY: Float): ResizeSnapResult {
        requestedX = (requestedX + dx.finite()).takeIf(Float::isFinite) ?: requestedX
        requestedY = (requestedY + dy.finite()).takeIf(Float::isFinite) ?: requestedY
        val raw = geometry(requestedX, requestedY)
        val xPlus = geometry(requestedX + DERIVATIVE_STEP, requestedY).bounds
        val xMinus = geometry(requestedX - DERIVATIVE_STEP, requestedY).bounds
        val yPlus = geometry(requestedX, requestedY + DERIVATIVE_STEP).bounds
        val yMinus = geometry(requestedX, requestedY - DERIVATIVE_STEP).bounds
        val constraints = targets(raw.bounds).map { target ->
            Constraint(target, target.error(raw.bounds),
                (target.error(xPlus) - target.error(xMinus)) / (2f * DERIVATIVE_STEP),
                (target.error(yPlus) - target.error(yMinus)) / (2f * DERIVATIVE_STEP))
        }.filter { abs(it.dx) + abs(it.dy) > MIN_RESIZE_INFLUENCE }
        fun tolerance(axis: GuideAxis) = if (axis == GuideAxis.X) toleranceX else toleranceY
        val x = choose(constraints.filter { it.target.key.axis == GuideAxis.X }, toleranceX, xState)
        val y = choose(constraints.filter { it.target.key.axis == GuideAxis.Y }, toleranceY, yState)
        val choices = listOfNotNull(x, y).sortedBy { abs(it.error) / tolerance(it.target.key.axis) }

        fun solve(chosen: List<Constraint>): Geometry? {
            if (chosen.isEmpty()) return null
            val first = chosen.first()
            val second = chosen.getOrNull(1)
            val correctionX: Double
            val correctionY: Double
            if (second != null) {
                val determinant = first.dx.toDouble() * second.dy - first.dy.toDouble() * second.dx
                if (abs(determinant) < EPSILON) return null
                correctionX = (-first.error.toDouble() * second.dy + second.error.toDouble() * first.dy) / determinant
                correctionY = (-second.error.toDouble() * first.dx + first.error.toDouble() * second.dx) / determinant
            } else {
                // Minimise the correction in physical screen space, not in anisotropic page units.
                val weightedX = first.dx.toDouble() / (aspect * aspect)
                val divisor = first.dx * weightedX + first.dy.toDouble() * first.dy
                if (divisor < EPSILON) return null
                correctionX = -first.error * weightedX / divisor
                correctionY = -first.error.toDouble() * first.dy / divisor
            }
            if (!correctionX.isFinite() || !correctionY.isFinite() ||
                abs(correctionX) > toleranceX * MAX_CORRECTION || abs(correctionY) > toleranceY * MAX_CORRECTION) return null
            val result = geometry(requestedX + correctionX.toFloat(), requestedY + correctionY.toFloat())
            if (chosen.any { abs(it.target.error(result.bounds)) > tolerance(it.target.key.axis) * MATCH_FRACTION }) return null
            if (chosen.any { it.target.key.kind != 0 } && references.any {
                overlap(GuideAxis.X, result.bounds, it) > EPSILON && overlap(GuideAxis.Y, result.bounds, it) > EPSILON
            }) return null
            return result
        }

        var accepted = choices
        var snapped = solve(choices)
        if (snapped == null) {
            accepted = emptyList()
            for (choice in choices) {
                val result = solve(listOf(choice)) ?: continue
                snapped = result
                accepted = listOf(choice)
                break
            }
        }
        xState.held = accepted.firstOrNull { it.target.key.axis == GuideAxis.X }?.target?.key
        yState.held = accepted.firstOrNull { it.target.key.axis == GuideAxis.Y }?.target?.key
        val result = snapped ?: raw
        val guides = if (accepted.isEmpty()) emptyList() else constraints.filter {
            abs(it.target.error(result.bounds)) <= tolerance(it.target.key.axis) * MATCH_FRACTION
        }.flatMap { it.target.guides(result.bounds) }.distinct().take(16)
        return ResizeSnapResult(result.elements, guides)
    }

    private fun geometry(dx: Float, dy: Float): Geometry {
        val elements = original.resizedSelectionFromCorner(corner, dx, dy, pageCount, aspect)
        val coverage = elements.map { it.visualBounds(aspect = aspect) }
        val left = coverage.minOf { it.x }
        val top = coverage.minOf { it.y }
        // Visual coverage may be narrower than the model's minimum size after a physical rotation.
        val bounds = NormalizedRect(left, top, coverage.maxOf { it.right } - left, coverage.maxOf { it.bottom } - top)
        return Geometry(elements, bounds.copy(x = bounds.x + instanceOffsetX.finite()))
    }

    private fun choose(candidates: List<Constraint>, tolerance: Float, state: AxisState): Constraint? {
        if (!tolerance.isFinite() || tolerance <= 0f) { state.held = null; return null }
        state.held?.let { held ->
            val candidate = candidates.firstOrNull { it.target.key == held }
            if (candidate != null && abs(candidate.error) <= tolerance * RELEASE_DISTANCE) return candidate
            state.bypassed = held
        }
        state.held = null
        state.bypassed?.let { key ->
            if (candidates.firstOrNull { it.target.key == key }?.let { abs(it.error) > tolerance * RELEASE_DISTANCE * 2f } == true) {
                state.bypassed = null
            }
        }
        return candidates.filter { it.target.key != state.bypassed && abs(it.error) <= tolerance }
            .minWithOrNull(compareBy<Constraint> { abs(it.error) }.thenBy { it.target.priority })
    }

    private fun targets(moving: NormalizedRect): List<Target> = buildList {
        val first = floor(moving.x).toInt().coerceIn(0, pageCount - 1)
        val last = floor(moving.right - EPSILON).toInt().coerceIn(first, pageCount - 1)
        for (axis in GuideAxis.entries) {
            for (page in first..last) addAlignments(axis, -page - 1, NormalizedRect(page.toFloat(), 0f, 1f, 1f), 0)
            val relevant = references.withIndex().filter { axis == GuideAxis.X ||
                floor(moving.x) <= floor(it.value.right - EPSILON) && floor(it.value.x) <= floor(moving.right - EPSILON) }
            relevant.forEach { addAlignments(axis, it.index, it.value, 1) }
            val neighbours = relevant.filter { overlapAcross(axis, moving, it.value) > EPSILON }.sortedBy { it.value.start(axis) }
            neighbours.zipWithNext { a, b ->
                val gap = b.value.start(axis) - a.value.end(axis)
                if (gap < EPSILON || overlapAcross(axis, a.value, b.value) <= EPSILON) return@zipWithNext
                fun spacing(kind: Int, target: (NormalizedRect) -> Float, intervals: (NormalizedRect) -> List<Pair<Float, Float>>) {
                    add(Target(Key(axis, a.index, 0, b.index, kind), 2, target) { placed ->
                        val across = listOf(placed, a.value, b.value)
                        if (across.minOf { it.acrossEnd(axis) } <= across.maxOf { it.acrossStart(axis) }) emptyList()
                        else {
                            val cross = (across.minOf { it.acrossEnd(axis) } + across.maxOf { it.acrossStart(axis) }) / 2f
                            intervals(placed).filter { it.second >= it.first }.map { CompositionGuide.Spacing(axis, it.first, it.second, cross) }
                        }
                    })
                }
                if (gap >= moving.length(axis)) spacing(1,
                    { a.value.end(axis) + (gap - it.length(axis)) / 2f },
                    { listOf(a.value.end(axis) to it.start(axis), it.end(axis) to b.value.start(axis)) })
                spacing(2, { b.value.end(axis) + gap },
                    { listOf(a.value.end(axis) to b.value.start(axis), b.value.end(axis) to it.start(axis)) })
                spacing(3, { a.value.start(axis) - gap - it.length(axis) },
                    { listOf(it.end(axis) to a.value.start(axis), a.value.end(axis) to b.value.start(axis)) })
            }
        }
    }

    private fun MutableList<Target>.addAlignments(axis: GuideAxis, referenceKey: Int, reference: NormalizedRect, priority: Int) {
        val anchors = listOf(reference.start(axis), (reference.start(axis) + reference.end(axis)) / 2f, reference.end(axis))
        anchors.forEachIndexed { referenceAnchor, coordinate ->
            listOf(0f, .5f, 1f).forEachIndexed { movingAnchor, fraction ->
                add(Target(Key(axis, referenceKey, referenceAnchor * 3 + movingAnchor), priority,
                    { coordinate - it.length(axis) * fraction }) { placed ->
                    listOf(CompositionGuide.Alignment(axis, coordinate,
                        minOf(placed.acrossStart(axis), reference.acrossStart(axis)),
                        maxOf(placed.acrossEnd(axis), reference.acrossEnd(axis))))
                })
            }
        }
    }

    private fun overlapAcross(axis: GuideAxis, a: NormalizedRect, b: NormalizedRect) =
        minOf(a.acrossEnd(axis), b.acrossEnd(axis)) - maxOf(a.acrossStart(axis), b.acrossStart(axis))

    private companion object {
        const val DERIVATIVE_STEP = .001f
        const val EPSILON = .00001f
        const val MIN_RESIZE_INFLUENCE = .001f
        const val RELEASE_DISTANCE = 1.6f
        const val MATCH_FRACTION = .06f
        const val MAX_CORRECTION = 3.2f
        fun Float.finite() = takeIf(Float::isFinite) ?: 0f
        fun NormalizedRect.start(axis: GuideAxis) = if (axis == GuideAxis.X) x else y
        fun NormalizedRect.end(axis: GuideAxis) = if (axis == GuideAxis.X) right else bottom
        fun NormalizedRect.length(axis: GuideAxis) = if (axis == GuideAxis.X) width else height
        fun NormalizedRect.acrossStart(axis: GuideAxis) = if (axis == GuideAxis.X) y else x
        fun NormalizedRect.acrossEnd(axis: GuideAxis) = if (axis == GuideAxis.X) bottom else right
        fun overlap(axis: GuideAxis, a: NormalizedRect, b: NormalizedRect) = minOf(a.end(axis), b.end(axis)) - maxOf(a.start(axis), b.start(axis))
    }
}
