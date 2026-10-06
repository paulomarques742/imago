package eu.studio742.imago.core.composition

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun CompositionElement.crossedBoundaries(): Set<Int> {
    if (this is CompositionElement.Video) return emptySet()
    val start = transform.bounds.x
    val end = transform.bounds.right
    if (end <= start) return emptySet()
    return (1 until end.toInt() + 1).filterTo(mutableSetOf()) { boundary ->
        start < boundary && end > boundary
    }
}

fun CompositionProject.canReorderPage(pageIndex: Int): Boolean =
    pageIndex in pages.indices && elements.none { pageIndex in it.crossedBoundaries() || pageIndex + 1 in it.crossedBoundaries() }

data class PageSlice(val sourceLeft: Float, val sourceRight: Float)

fun pageSlice(pageIndex: Int): PageSlice = PageSlice(pageIndex.toFloat(), pageIndex + 1f)

fun NormalizedRect.intersectionWithPage(pageIndex: Int): NormalizedRect? {
    val left = max(x, pageIndex.toFloat())
    val right = min(this.right, pageIndex + 1f)
    val top = max(y, 0f)
    val bottom = min(this.bottom, 1f)
    if (right <= left || bottom <= top) return null
    return NormalizedRect(left - pageIndex, top, right - left, bottom - top)
}

fun snapCoordinate(value: Float, candidates: Iterable<Float>, threshold: Float = 0.012f): Float =
    candidates.minByOrNull { abs(value - it) }?.takeIf { abs(value - it) <= threshold } ?: value

/** No element can get smaller than this, as a fraction of the page. */
const val MIN_ELEMENT_SIZE = .05f

/** The maximum background blur. */
const val MAX_BACKGROUND_BLUR = 48f

/**
 * How much the image is reduced to simulate a blur of radius [radius].
 *
 * It lives here, and not in the interface, because it is the only thing that makes the preview and
 * the exported file agree. The exporter always reduced by 1/24 and ignored the radius, so changing
 * the blur had no effect at all on the output.
 */
fun backgroundBlurDivisor(radius: Float): Int {
    if (!radius.isFinite()) return 1
    return Math.round(radius.coerceIn(0f, MAX_BACKGROUND_BLUR) / 2f).coerceAtLeast(1)
}

/** A continuous element can span the whole project, and the maximum is [MAX_COMPOSITION_PAGES]. */
const val MAX_ELEMENT_SIZE = MAX_COMPOSITION_PAGES.toFloat()

/**
 * Fits the rectangle on the canvas, never throwing.
 *
 * The order matters and is the reason this function exists: **size first, only then position**.
 * The previous code limited the width against `pages.size - x`, which inverts the range as soon as
 * the element nears the edge — and `coerceIn` with the minimum above the maximum throws
 * `IllegalArgumentException`. A drawing made at the page's footer is born with `y ≈ .98`, so a
 * single tap on "+ size" was enough to kill the app. Limiting the size first always leaves
 * `pageCount - width` and `1 - height` non-negative, and the position fits by construction.
 */
fun NormalizedRect.clampedToCanvas(pageCount: Int, minSize: Float = MIN_ELEMENT_SIZE): NormalizedRect {
    val pages = pageCount.coerceAtLeast(1).toFloat()
    val safeMin = minSize.coerceIn(.001f, 1f)
    val clampedWidth = width.orFallback(safeMin).coerceIn(safeMin, pages)
    val clampedHeight = height.orFallback(safeMin).coerceIn(safeMin, 1f)
    return NormalizedRect(
        x = x.orFallback(0f).coerceIn(0f, pages - clampedWidth),
        y = y.orFallback(0f).coerceIn(0f, 1f - clampedHeight),
        width = clampedWidth,
        height = clampedHeight,
    )
}

/**
 * The same fit, but within a single page.
 *
 * `CompositionProject.init` requires a video to stay confined to its page, and that requirement was
 * checked *after* the gesture had already written the value: dragging a video over the separator
 * threw from inside `pointerInput`, with nobody to catch it. Going through here makes the invariant
 * impossible to break instead of validating it too late.
 */
fun NormalizedRect.confinedToPage(pageIndex: Int, minSize: Float = MIN_ELEMENT_SIZE): NormalizedRect {
    val page = pageIndex.coerceAtLeast(0).toFloat()
    val safeMin = minSize.coerceIn(.001f, 1f)
    val clampedWidth = width.orFallback(safeMin).coerceIn(safeMin, 1f)
    val clampedHeight = height.orFallback(safeMin).coerceIn(safeMin, 1f)
    return NormalizedRect(
        x = x.orFallback(page).coerceIn(page, page + 1f - clampedWidth),
        y = y.orFallback(0f).coerceIn(0f, 1f - clampedHeight),
        width = clampedWidth,
        height = clampedHeight,
    )
}

/** Fits the element by its type: a video lives on one page, everything else on the whole canvas. */
fun CompositionElement.clampedBounds(bounds: NormalizedRect, pageCount: Int): NormalizedRect =
    if (this is CompositionElement.Video) bounds.confinedToPage(pageIndex) else bounds.clampedToCanvas(pageCount)

enum class ResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Resizes from a corner, keeping the opposite corner still.
 *
 * [dx] and [dy] are offsets **already converted to the element's frame of reference** — the caller
 * counter-rotates the screen vector with [rotateVector] before dividing by the page size.
 */
fun NormalizedRect.resizedFrom(
    corner: ResizeCorner,
    dx: Float,
    dy: Float,
    minSize: Float = MIN_ELEMENT_SIZE,
): NormalizedRect {
    val movesLeftEdge = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT
    val movesTopEdge = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT
    val safeMin = minSize.coerceIn(.001f, 1f)
    // The delta is clamped before touching the rectangle. Without this, the dragged edge passes over
    // the anchor, the width goes negative and NormalizedRect's `require` throws.
    val stepX = dx.orFallback(0f).let { if (movesLeftEdge) it.coerceAtMost(width - safeMin) else it.coerceAtLeast(safeMin - width) }
    val stepY = dy.orFallback(0f).let { if (movesTopEdge) it.coerceAtMost(height - safeMin) else it.coerceAtLeast(safeMin - height) }
    return NormalizedRect(
        x = if (movesLeftEdge) x + stepX else x,
        y = if (movesTopEdge) y + stepY else y,
        width = if (movesLeftEdge) width - stepX else width + stepX,
        height = if (movesTopEdge) height - stepY else height + stepY,
    )
}

/** Scales around the centre, for the two-finger pinch. The element's centre does not move. */
fun NormalizedRect.scaledAboutCentre(
    factor: Float,
    minSize: Float = MIN_ELEMENT_SIZE,
    maxSize: Float = MAX_ELEMENT_SIZE,
): NormalizedRect {
    val safeFactor = if (factor.isFinite() && factor > 0f) factor else 1f
    val safeMin = minSize.coerceIn(.001f, 1f)
    val safeMax = maxSize.coerceAtLeast(safeMin)
    val scaledWidth = (width * safeFactor).orFallback(safeMin).coerceIn(safeMin, safeMax)
    val scaledHeight = (height * safeFactor).orFallback(safeMin).coerceIn(safeMin, safeMax)
    return NormalizedRect(
        x = x + (width - scaledWidth) / 2f,
        y = y + (height - scaledHeight) / 2f,
        width = scaledWidth,
        height = scaledHeight,
    )
}

/**
 * Rotates a vector.
 *
 * It only makes sense in **pixels**: the normalised space is anisotropic (width 1 is the page's
 * width, height 1 is its height), so rotating inside it would distort the gesture on a 9:16 format.
 * The caller rotates the finger's offset in pixels and only then divides by the page size.
 */
fun rotateVector(dx: Float, dy: Float, degrees: Float): Pair<Float, Float> {
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    return dx * cosine - dy * sine to dx * sine + dy * cosine
}

/** Normalises to `0..360`, so rotation stops accumulating without limit. */
fun normalizeDegrees(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    return ((degrees % 360f) + 360f) % 360f
}

/**
 * Snaps the rotation: first to the four cardinal angles, then to multiples of 15°.
 *
 * The tolerance for the cardinals is larger because straightening an element is the most requested
 * gesture and the hardest to hit by hand.
 */
fun snapRotation(
    degrees: Float,
    fineStep: Float = 15f,
    fineTolerance: Float = 3f,
    cardinalTolerance: Float = 5f,
): Float {
    val normalized = normalizeDegrees(degrees)
    listOf(0f, 90f, 180f, 270f).firstOrNull { angleDistance(normalized, it) <= cardinalTolerance }
        ?.let { return it }
    val step = fineStep.takeIf { it > 0f } ?: return normalized
    val nearest = normalizeDegrees(Math.round(normalized / step) * step)
    return if (angleDistance(normalized, nearest) <= fineTolerance) nearest else normalized
}

/** Angular distance along the shortest path — 359° and 1° are 2° apart, not 358°. */
fun angleDistance(first: Float, second: Float): Float {
    val delta = abs(normalizeDegrees(first) - normalizeDegrees(second))
    return min(delta, 360f - delta)
}

private fun Float.orFallback(fallback: Float): Float = if (isFinite()) this else fallback

/**
 * The rectangle that covers all of these.
 *
 * It is the frame of a multiple selection: what the handles show and what block gestures measure.
 */
fun Iterable<NormalizedRect>.boundingBox(): NormalizedRect? {
    val all = toList().ifEmpty { return null }
    val left = all.minOf { it.x }
    val top = all.minOf { it.y }
    return NormalizedRect(
        x = left,
        y = top,
        width = (all.maxOf { it.right } - left).coerceAtLeast(MIN_ELEMENT_SIZE),
        height = (all.maxOf { it.bottom } - top).coerceAtLeast(MIN_ELEMENT_SIZE),
    )
}

/**
 * Takes this rectangle from [box] to [target], keeping the place it had inside it.
 *
 * It is what makes a multiple selection resize as a block: the relative position and the size scale
 * by the same ratio, so two cards side by side stay side by side.
 */
fun NormalizedRect.mappedBetween(
    box: NormalizedRect,
    target: NormalizedRect,
    minSize: Float = MIN_ELEMENT_SIZE,
): NormalizedRect {
    val scaleX = (target.width / box.width).orFallback(1f)
    val scaleY = (target.height / box.height).orFallback(1f)
    return NormalizedRect(
        x = target.x + (x - box.x) * scaleX,
        y = target.y + (y - box.y) * scaleY,
        width = (width * scaleX).orFallback(minSize).coerceAtLeast(minSize),
        height = (height * scaleY).orFallback(minSize).coerceAtLeast(minSize),
    )
}

/**
 * Makes this rectangle orbit a centre, without changing size.
 *
 * [aspect] is the page's width divided by its height. Without it the calculation ran in the
 * normalised space, which is not square: on a 9:16 format a quarter turn tilted the arrangement
 * instead of rotating it. The element's own rotation is up to the caller — this only handles the place.
 */
fun NormalizedRect.orbited(
    centreX: Float,
    centreY: Float,
    degrees: Float,
    aspect: Float,
): NormalizedRect {
    val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else 1f
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val offsetX = x + width / 2f - centreX
    val offsetY = y + height / 2f - centreY
    val turnedX = offsetX * cosine - offsetY * sine / safeAspect
    val turnedY = offsetX * sine * safeAspect + offsetY * cosine
    return NormalizedRect(
        x = centreX + turnedX - width / 2f,
        y = centreY + turnedY - height / 2f,
        width = width,
        height = height,
    )
}
