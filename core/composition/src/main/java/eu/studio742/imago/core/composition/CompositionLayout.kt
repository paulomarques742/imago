package eu.studio742.imago.core.composition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable enum class CompositionLayoutKind { ROW, COLUMN, GRID, FEATURE_LEFT, FEATURE_TOP }
@Serializable enum class LayoutAxis { HORIZONTAL, VERTICAL }

@Serializable
sealed class LayoutNode {
    @Serializable @SerialName("cell") data class Cell(val elementId: String) : LayoutNode()
    @Serializable @SerialName("split") data class Split(
        val id: String, val axis: LayoutAxis, val children: List<LayoutNode>,
        val weights: List<Float> = List(children.size) { 1f },
    ) : LayoutNode()
}

@Serializable data class LayoutStroke(val argb: Long = 0xFFFFFFFF, val opacity: Float = 1f, val width: Float = 0f)
@Serializable data class LayoutContent(val aspect: Float, val rotationDegrees: Float = 0f)

/** Geometry is separate from media: changing a cell never rewrites its original, recipe or crop. */
@Serializable
data class CompositionLayout(
    val groupId: String,
    val frame: ElementTransform,
    val root: LayoutNode,
    val kind: CompositionLayoutKind = CompositionLayoutKind.GRID,
    val columns: Int = 2,
    val rows: Int = 0,
    val margin: Float = .025f,
    val gap: Float = .015f,
    val outline: LayoutStroke = LayoutStroke(),
    val separators: LayoutStroke = LayoutStroke(width = .006f),
    val content: Map<String, LayoutContent> = emptyMap(),
)

fun LayoutNode.memberIds(): List<String> = when (this) {
    is LayoutNode.Cell -> listOf(elementId)
    is LayoutNode.Split -> children.flatMap { it.memberIds() }
}

/** Exchanges cell occupants without changing split proportions or the elements themselves. */
fun LayoutNode.swapMembers(first: String, second: String): LayoutNode {
    if (first == second || !memberIds().containsAll(listOf(first, second))) return this
    fun swap(node: LayoutNode): LayoutNode = when (node) {
        is LayoutNode.Cell -> node.copy(elementId = when (node.elementId) { first -> second; second -> first; else -> node.elementId })
        is LayoutNode.Split -> node.copy(children = node.children.map(::swap))
    }
    return swap(this)
}

fun layoutTree(ids: List<String>, kind: CompositionLayoutKind, columns: Int = 2, rows: Int = 0): LayoutNode {
    require(ids.size >= 2 && ids.distinct().size == ids.size)
    fun split(name: String, axis: LayoutAxis, nodes: List<LayoutNode>, weights: List<Float>? = null): LayoutNode =
        if (nodes.size == 1) nodes.single() else LayoutNode.Split(name, axis, nodes, weights ?: List(nodes.size) { 1f })
    val cells = ids.map(LayoutNode::Cell)
    return when (kind) {
        CompositionLayoutKind.ROW -> split("row", LayoutAxis.HORIZONTAL, cells)
        CompositionLayoutKind.COLUMN -> split("column", LayoutAxis.VERTICAL, cells)
        CompositionLayoutKind.GRID -> {
            val count = max(ceil(ids.size.toDouble() / columns.coerceIn(1, ids.size)).toInt(), rows).coerceIn(1, ids.size)
            var cursor = 0
            split("rows", LayoutAxis.VERTICAL, List(count) { index ->
                val size = ids.size / count + if (index < ids.size % count) 1 else 0
                split("row-$index", LayoutAxis.HORIZONTAL, cells.subList(cursor, cursor + size).also { cursor += size })
            })
        }
        CompositionLayoutKind.FEATURE_LEFT, CompositionLayoutKind.FEATURE_TOP -> {
            val axis = if (kind == CompositionLayoutKind.FEATURE_LEFT) LayoutAxis.HORIZONTAL else LayoutAxis.VERTICAL
            val other = if (axis == LayoutAxis.HORIZONTAL) LayoutAxis.VERTICAL else LayoutAxis.HORIZONTAL
            split("feature", axis, listOf(cells.first(), split("small", other, cells.drop(1))), listOf(2f, 1f))
        }
    }
}

data class LayoutDivider(val splitId: String, val index: Int, val axis: LayoutAxis,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float, val parent: NormalizedRect)
data class LayoutGeometry(val cells: Map<String, NormalizedRect>, val dividers: List<LayoutDivider>,
    val separators: List<LayoutDivider>, val area: NormalizedRect)

/** Spacing uses the shorter physical side, so portrait pages do not produce wider vertical gaps. */
fun CompositionLayout.geometry(pageAspect: Float): LayoutGeometry {
    val physicalAspect = frame.bounds.width * pageAspect / frame.bounds.height
    val shortX = min(1f, 1f / physicalAspect)
    val shortY = min(1f, physicalAspect)
    val mx = margin.coerceIn(0f, .2f) * shortX; val my = margin.coerceIn(0f, .2f) * shortY
    val gx = gap.coerceIn(0f, .15f) * shortX; val gy = gap.coerceIn(0f, .15f) * shortY
    val cells = linkedMapOf<String, NormalizedRect>(); val dividers = mutableListOf<LayoutDivider>()
    val separators = mutableListOf<LayoutDivider>()
    fun visit(node: LayoutNode, box: NormalizedRect, partition: NormalizedRect) {
        when (node) {
            is LayoutNode.Cell -> cells[node.elementId] = box
            is LayoutNode.Split -> {
                if (node.children.isEmpty()) return
                val horizontal = node.axis == LayoutAxis.HORIZONTAL
                val length = if (horizontal) box.width else box.height
                val spacing = (if (horizontal) gx else gy).coerceAtMost(length / (node.children.size * 3f))
                val available = length - spacing * (node.children.size - 1)
                val weights = node.children.indices.map { node.weights.getOrElse(it) { 1f }.takeIf { w -> w.isFinite() && w > 0f } ?: 1f }
                val total = weights.sum()
                var cursor = if (horizontal) box.x else box.y
                node.children.forEachIndexed { index, child ->
                    val size = available * weights[index] / total
                    val start = if (index == 0) { if (horizontal) partition.x else partition.y } else cursor - spacing / 2f
                    val end = if (index == node.children.lastIndex) { if (horizontal) partition.right else partition.bottom }
                        else cursor + size + spacing / 2f
                    // Media stop at the gap; separator branches meet at its centre regardless of stroke width.
                    val childPartition = if (horizontal) NormalizedRect(start, partition.y, end - start, partition.height)
                        else NormalizedRect(partition.x, start, partition.width, end - start)
                    visit(child, if (horizontal) NormalizedRect(cursor, box.y, size, box.height) else NormalizedRect(box.x, cursor, box.width, size), childPartition)
                    cursor += size
                    if (index < node.children.lastIndex) {
                        val position = cursor + spacing / 2f
                        val divider = if (horizontal) LayoutDivider(node.id, index, node.axis, position, box.y, position, box.bottom, box)
                            else LayoutDivider(node.id, index, node.axis, box.x, position, box.right, position, box)
                        dividers += divider
                        separators += if (horizontal) divider.copy(y1 = partition.y, y2 = partition.bottom, parent = partition)
                            else divider.copy(x1 = partition.x, x2 = partition.right, parent = partition)
                        cursor += spacing
                    }
                }
            }
        }
    }
    val area = NormalizedRect(mx, my, 1f - 2 * mx, 1f - 2 * my)
    visit(root, area, area)
    return LayoutGeometry(cells, dividers, separators, area)
}

fun LayoutNode.adjustDivider(splitId: String, index: Int, delta: Float): LayoutNode = when (this) {
    is LayoutNode.Cell -> this
    is LayoutNode.Split -> if (id != splitId) copy(children = children.map { it.adjustDivider(splitId, index, delta) }) else {
        if (index !in 0 until children.lastIndex || !delta.isFinite()) this else {
            val values = children.indices.map { weights.getOrElse(it) { 1f }.coerceAtLeast(.001f) }.toMutableList()
            val sum = values.sum(); val pair = values[index] + values[index + 1]
            val minimum = pair * .1f
            val first = (values[index] + delta * sum).coerceIn(minimum, pair - minimum)
            values[index] = first; values[index + 1] = pair - first
            copy(weights = values)
        }
    }
}

/** Cell centres orbit in pixels, because page-normalised x and y are not equal distances. */
fun CompositionLayout.applyTo(elements: List<CompositionElement>, pageAspect: Float): List<CompositionElement> {
    val cells = geometry(pageAspect).cells; val frameBox = frame.bounds
    return elements.map { element ->
        val cell = cells[element.id] ?: return@map element
        val box = NormalizedRect(frameBox.x + cell.x * frameBox.width, frameBox.y + cell.y * frameBox.height,
            cell.width * frameBox.width, cell.height * frameBox.height)
        val media = element is CompositionElement.Photo || element is CompositionElement.Video || element is CompositionElement.MediaPlaceholder
        val info = content[element.id] ?: LayoutContent(element.transform.bounds.width * pageAspect / element.transform.bounds.height)
        val angle = if (media) 0f else info.rotationDegrees
        val fitted = if (media) box else {
            val ratio = info.aspect.coerceAtLeast(.001f)
            val radians = Math.toRadians(angle.toDouble())
            val unitW = (abs(cos(radians)) * ratio + abs(sin(radians))).toFloat()
            val unitH = (abs(sin(radians)) * ratio + abs(cos(radians))).toFloat()
            val height = min(box.width * pageAspect / unitW, box.height / unitH)
            val width = height * ratio / pageAspect
            NormalizedRect(box.x + (box.width - width) / 2f, box.y + (box.height - height) / 2f, width, height)
        }
        val placed = fitted.orbited(frameBox.x + frameBox.width / 2f, frameBox.y + frameBox.height / 2f, frame.rotationDegrees, pageAspect)
        val transformed = element.withGroupId(groupId).withTransform(element.transform.copy(bounds = placed,
            rotationDegrees = normalizeDegrees(frame.rotationDegrees + angle)))
        val scale = fitted.width / element.transform.bounds.width
        when (transformed) {
            is CompositionElement.Text -> transformed.copy(fontSize = transformed.fontSize * scale,
                backgroundPadding = transformed.backgroundPadding * scale, backgroundCornerRadius = transformed.backgroundCornerRadius * scale,
                shadowRadius = transformed.shadowRadius * scale, shadowOffsetX = transformed.shadowOffsetX * scale, shadowOffsetY = transformed.shadowOffsetY * scale)
            is CompositionElement.Shape -> transformed.copy(strokeWidth = transformed.strokeWidth * scale)
            is CompositionElement.Drawing -> transformed.copy(strokeWidth = transformed.strokeWidth * scale)
            else -> transformed
        }
    }
}

/** Decorations are derived, never selectable layers, and use the ordinary image/video render paths. */
fun CompositionProject.renderElements(): List<CompositionElement> {
    val gridMembers = layouts.flatMap { it.root.memberIds() }.toSet()
    val artwork = elements.map { element ->
        if (element.id !in gridMembers) element else when (element) {
            is CompositionElement.Photo -> element.copy(frame = element.frame.copy(borderWidth = 0f))
            is CompositionElement.Video -> element.copy(frame = element.frame.copy(borderWidth = 0f))
            is CompositionElement.MediaPlaceholder -> element.copy(frame = element.frame.copy(borderWidth = 0f))
            else -> element
        }
    }
    return artwork + layouts.flatMap { layout ->
        val members = elements.filter { it.groupId == layout.groupId && it.id in layout.root.memberIds() }
        if (members.none { it.visible }) return@flatMap emptyList()
        val geometry = layout.geometry(format.aspectRatio)
        val z = members.maxOf { it.zIndex }
        val shortSide = min(layout.frame.bounds.width, layout.frame.bounds.height / format.aspectRatio)
        val repeat = members.first().repeatOnPages.takeIf { pages -> members.all { it.repeatOnPages == pages } }.orEmpty()
        val transform = layout.frame.copy(repeatOriginPage = if (repeat.isEmpty()) null else members.first().repeatOriginPage())
        fun stroke(suffix: String, style: LayoutStroke, points: List<StrokePoint>, area: NormalizedRect? = null): CompositionElement.Drawing {
            val bounds = transform.bounds
            val placed = if (area == null) transform else transform.copy(bounds = NormalizedRect(bounds.x + area.x * bounds.width,
                bounds.y + area.y * bounds.height, area.width * bounds.width, area.height * bounds.height))
            val local = if (area == null) points else points.map { it.copy(x = ((it.x - area.x) / area.width).coerceIn(0f, 1f),
                y = ((it.y - area.y) / area.height).coerceIn(0f, 1f)) }
            return CompositionElement.Drawing("layout-decoration:${layout.groupId}:$suffix", placed.copy(opacity = transform.opacity * style.opacity),
                z, StrokeKind.PEN, local, style.argb, style.width * shortSide * 1080f, locked = true, repeatOnPages = repeat)
        }
        buildList {
            if (layout.outline.width > 0f) add(stroke("outline", layout.outline, listOf(
                StrokePoint(0f, 0f), StrokePoint(1f, 0f), StrokePoint(1f, 1f), StrokePoint(0f, 1f), StrokePoint(0f, 0f))))
            if (layout.separators.width > 0f) add(stroke("separators", layout.separators, geometry.separators.flatMap {
                listOf(StrokePoint(it.x1, it.y1, startsStroke = true), StrokePoint(it.x2, it.y2))
            }, geometry.area))
        }
    }
}

fun CompositionLayout.remap(group: String, ids: Map<String, String>, dx: Float = 0f, dy: Float = 0f): CompositionLayout {
    fun map(node: LayoutNode): LayoutNode = when (node) {
        is LayoutNode.Cell -> node.copy(elementId = ids[node.elementId] ?: node.elementId)
        is LayoutNode.Split -> node.copy(children = node.children.map(::map))
    }
    return copy(groupId = group, root = map(root), frame = frame.copy(bounds = frame.bounds.copy(x = frame.bounds.x + dx, y = frame.bounds.y + dy)),
        content = content.mapKeys { ids[it.key] ?: it.key })
}

fun CompositionLayout.crossesBoundary(boundary: Int, pageAspect: Float): Boolean {
    if (boundary <= 0) return false
    val bounds = CompositionElement.MediaPlaceholder("layout-bounds", frame, 0).visualBounds(aspect = pageAspect)
    return bounds.x < boundary && bounds.right > boundary
}

/** Follow collective transforms; member content edits must not move the layout's other cells. */
fun CompositionProject.reconcileLayouts(previous: CompositionProject): CompositionProject {
    val byId = elements.associateBy { it.id }; val oldById = previous.elements.associateBy { it.id }
    val next = layouts.mapNotNull { layout ->
        val ids = layout.root.memberIds()
        if (ids.any { byId[it]?.groupId != layout.groupId }) return@mapNotNull null
        val old = previous.layouts.firstOrNull { it.groupId == layout.groupId } ?: return@mapNotNull layout
        if (old != layout) return@mapNotNull layout
        val before = ids.map { oldById[it] ?: return@mapNotNull layout }
        val after = ids.map { byId.getValue(it) }
        if (before.zip(after).all { (from, to) -> from.transform == to.transform }) return@mapNotNull layout
        val a = before.first().transform; val b = after.first().transform
        val sx = b.bounds.width / a.bounds.width; val sy = b.bounds.height / a.bounds.height
        val angle = normalizeDegrees(b.rotationDegrees - a.rotationDegrees)
        val aspect = format.aspectRatio
        fun mapped(box: NormalizedRect): NormalizedRect {
            val orbited = box.orbited(a.bounds.x + a.bounds.width / 2f, a.bounds.y + a.bounds.height / 2f, angle, aspect)
            val cx = b.bounds.x + b.bounds.width / 2f + (orbited.x + orbited.width / 2f - a.bounds.x - a.bounds.width / 2f) * sx
            val cy = b.bounds.y + b.bounds.height / 2f + (orbited.y + orbited.height / 2f - a.bounds.y - a.bounds.height / 2f) * sy
            return NormalizedRect(cx - box.width * sx / 2f, cy - box.height * sy / 2f, box.width * sx, box.height * sy)
        }
        fun close(x: Float, y: Float) = abs(x - y) < .0001f
        val collective = before.zip(after).all { (from, to) ->
            val expected = mapped(from.transform.bounds); val actual = to.transform.bounds
            val difference = normalizeDegrees(to.transform.rotationDegrees - from.transform.rotationDegrees - angle)
            close(expected.x, actual.x) && close(expected.y, actual.y) && close(expected.width, actual.width) && close(expected.height, actual.height) &&
                min(difference, 360f - difference) < .001f
        }
        if (!collective) layout else {
            val opacityRatio = b.opacity / a.opacity.coerceAtLeast(.001f)
            val sharedOpacity = before.zip(after).all { (from, to) -> close(to.transform.opacity, from.transform.opacity * opacityRatio) }
            layout.copy(frame = layout.frame.copy(bounds = mapped(layout.frame.bounds),
                rotationDegrees = normalizeDegrees(layout.frame.rotationDegrees + angle),
                opacity = if (sharedOpacity) (layout.frame.opacity * opacityRatio).coerceIn(0f, 1f) else layout.frame.opacity))
        }
    }
    return if (next == layouts) this else copy(layouts = next)
}
