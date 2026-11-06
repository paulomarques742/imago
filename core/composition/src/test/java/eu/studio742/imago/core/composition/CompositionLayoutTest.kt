package eu.studio742.imago.core.composition

import eu.studio742.imago.core.model.EditRecipe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompositionLayoutTest {
    private val ids = listOf("a", "b", "c", "d", "e")
    private val box = NormalizedRect(.1f, .15f, .8f, .7f)
    private fun layout(kind: CompositionLayoutKind = CompositionLayoutKind.GRID, members: List<String> = ids) =
        CompositionLayout("layout", ElementTransform(box), layoutTree(members, kind), kind = kind, margin = 0f, gap = 0f)
    private fun elements() = ids.mapIndexed { i, id -> CompositionElement.MediaPlaceholder(id,
        ElementTransform(NormalizedRect(.1f + .1f * i, .2f, .15f, .2f)), i) }
    private fun project(layout: CompositionLayout): CompositionProject = CompositionProject(id = "p", name = "Grid",
        format = PageFormatPreset.STORY_9_16.format, pages = listOf(CompositionPage("page", 0)),
        elements = layout.applyTo(elements(), PageFormatPreset.STORY_9_16.format.aspectRatio), layouts = listOf(layout), createdAt = "", updatedAt = "")

    @Test fun `all variants place every selected element exactly once without overlapping cells`() {
        CompositionLayoutKind.entries.forEach { kind ->
            val cells = layout(kind).geometry(.5625f).cells
            assertEquals(ids.toSet(), cells.keys)
            assertEquals(1f, cells.values.sumOf { (it.width * it.height).toDouble() }.toFloat(), .0001f)
            cells.values.forEach { a -> cells.values.filter { it !== a }.forEach { b ->
                val overlap = minOf(a.right, b.right) - maxOf(a.x, b.x)
                assertTrue(overlap <= .0001f || minOf(a.bottom, b.bottom) - maxOf(a.y, b.y) <= .0001f)
            } }
        }
    }

    @Test fun `row and column counts can create uneven final rows without dropping content`() {
        val tree = layoutTree(ids.take(4), CompositionLayoutKind.GRID, columns = 2, rows = 3)
        val root = tree as LayoutNode.Split
        assertEquals(3, root.children.size)
        assertEquals(listOf(2, 1, 1), root.children.map { it.memberIds().size })
        assertEquals(ids.take(4), root.memberIds())
    }

    @Test fun `portrait spacing has equal physical thickness in both directions`() {
        val l = layout(members = ids.take(4)).copy(gap = .04f, margin = .05f)
        val cells = l.geometry(.5f).cells.values.toList()
        val xGap = (cells[1].x - cells[0].right) * box.width * .5f
        val yGap = (cells[2].y - cells[0].bottom) * box.height
        assertEquals(xGap, yGap, .00001f)
    }

    @Test fun `moving a divider changes adjacent weights and retains their total and the other splits`() {
        val root = layout(CompositionLayoutKind.ROW).root as LayoutNode.Split
        val changed = root.adjustDivider(root.id, 1, .1f) as LayoutNode.Split
        assertEquals(root.weights.sum(), changed.weights.sum(), .0001f)
        assertEquals(root.weights[0], changed.weights[0])
        assertTrue(changed.weights[1] > root.weights[1]); assertTrue(changed.weights[2] < root.weights[2])
        val limited = root.adjustDivider(root.id, 1, 100f) as LayoutNode.Split
        assertTrue(limited.weights.all { it > 0f })
        assertEquals(root, root.adjustDivider(root.id, 1, Float.NaN))
    }

    @Test fun `a mixed layout preserves media originals recipes crops and nonmedia aspect ratios`() {
        val recipe = PhotoRecipeSnapshot(EditRecipe(assetId = "asset", originalChecksum = "hash", createdAt = "", updatedAt = ""))
        val photo = CompositionElement.Photo("a", ElementTransform(box), 0, MediaReference("asset", "hash", "photo.jpg"), recipe,
            crop = PlacementCrop(.3f, -.2f, 1.8f))
        val shape = CompositionElement.Shape("b", ElementTransform(box.copy(width = .2f, height = .1f)), 1, ShapeKind.ELLIPSE)
        val l = layout(CompositionLayoutKind.ROW, listOf("a", "b")).copy(content = mapOf("b" to LayoutContent(2f)))
        val placed = l.applyTo(listOf(photo, shape), 1f)
        val result = placed[0] as CompositionElement.Photo
        assertEquals(photo.media, result.media); assertEquals(photo.recipe, result.recipe); assertEquals(photo.crop, result.crop)
        assertEquals(2f, placed[1].transform.bounds.width / placed[1].transform.bounds.height, .0001f)
        assertEquals(ShapeKind.ELLIPSE, (placed[1] as CompositionElement.Shape).kind)
    }

    @Test fun `shared boundaries are emitted once and decorations remain outside saved members`() {
        val p = project(layout(members = ids.take(4)).copy(outline = LayoutStroke(width = .02f)))
        val rendered = p.renderElements()
        val decorations = rendered.filterIsInstance<CompositionElement.Drawing>()
        assertEquals(2, decorations.size)
        assertEquals(3 * 2, decorations.single { it.id.endsWith(":separators") }.points.size)
        assertTrue(decorations.all { it.locked })
        assertFalse(p.elements.any { it.id.startsWith("layout-decoration:") })
    }

    @Test fun `layout membership styles ratios and geometry survive serialization and old projects default to no layouts`() {
        val l = layout().copy(outline = LayoutStroke(0xAABBCCDD, .4f, .02f),
            root = layout().root.adjustDivider("rows", 0, .12f))
        val p = project(l)
        val json = Json { encodeDefaults = true; classDiscriminator = "elementType" }
        assertEquals(p, json.decodeFromString<CompositionProject>(json.encodeToString(p)))
        val old = Json.parseToJsonElement(json.encodeToString(p)).toString().replace(Regex(",\"layouts\":\\[.*\\]"), "")
        assertTrue(json.decodeFromString<CompositionProject>(old).layouts.isEmpty())
    }

    @Test fun `collective translation scale and rotation carry the persistent layout frame`() {
        val p = project(layout())
        val translated = p.copy(elements = p.elements.map { e -> e.withTransform(e.transform.copy(bounds = e.transform.bounds.copy(x = e.transform.bounds.x + .2f))) }).reconcileLayouts(p)
        assertEquals(box.x + .2f, translated.layouts.single().frame.bounds.x, .0001f)
        val rotated = p.copy(elements = p.elements.map { e -> e.withTransform(e.transform.copy(
            bounds = e.transform.bounds.orbited(.5f, .5f, 35f, p.format.aspectRatio), rotationDegrees = 35f)) }).reconcileLayouts(p)
        assertEquals(35f, rotated.layouts.single().frame.rotationDegrees, .0001f)
        val expected = box.orbited(.5f, .5f, 35f, p.format.aspectRatio)
        assertEquals(expected.x, rotated.layouts.single().frame.bounds.x, .0001f)
        val scaled = p.copy(elements = p.elements.map { e -> e.withTransform(e.transform.copy(bounds = e.transform.bounds.mappedBetween(box, box.copy(width = .6f, height = .525f)))) }).reconcileLayouts(p)
        assertEquals(.6f, scaled.layouts.single().frame.bounds.width, .0001f)
        assertEquals(.525f, scaled.layouts.single().frame.bounds.height, .0001f)
    }

    @Test fun `member edits do not move other cells and ungroup removes the corresponding decoration`() {
        val p = project(layout())
        val edited = p.copy(elements = p.elements.mapIndexed { i, e -> if (i == 0) e.withTransform(e.transform.copy(bounds = e.transform.bounds.copy(x = -.2f))) else e }).reconcileLayouts(p)
        assertEquals(p.layouts, edited.layouts)
        val free = p.copy(elements = p.elements.map { it.withGroupId(null) }).reconcileLayouts(p)
        assertTrue(free.layouts.isEmpty()); assertEquals(free.elements, free.renderElements())
    }

    @Test fun `duplicating a layout remaps all members content and decoration identities`() {
        val l = layout().copy(content = ids.associateWith { LayoutContent(1f) })
        val copied = l.remap("copy", ids.associateWith { "copy-$it" }, .04f, .03f)
        assertEquals(ids.map { "copy-$it" }, copied.root.memberIds())
        assertEquals(ids.map { "copy-$it" }.toSet(), copied.content.keys)
        assertEquals(box.x + .04f, copied.frame.bounds.x, .00001f)
    }

    @Test fun `media borders remain saved but do not double the grid shared boundaries`() {
        val p = project(layout()).let { source -> source.copy(elements = source.elements.map { (it as CompositionElement.MediaPlaceholder).copy(frame = FrameStyle(borderWidth = 18f)) }) }
        assertTrue(p.elements.filterIsInstance<CompositionElement.MediaPlaceholder>().all { it.frame.borderWidth == 18f })
        assertTrue(p.renderElements().filterIsInstance<CompositionElement.MediaPlaceholder>().all { it.frame.borderWidth == 0f })
        assertTrue(p.copy(layouts = emptyList()).renderElements().filterIsInstance<CompositionElement.MediaPlaceholder>().all { it.frame.borderWidth == 18f })
    }

    @Test fun `a layout spanning a page boundary prevents separating its cells by reordering pages`() {
        val l = layout(CompositionLayoutKind.ROW, ids.take(2)).copy(frame = ElementTransform(NormalizedRect(.5f, .1f, 1f, .6f)), gap = .1f)
        val p = project(l).copy(pages = listOf(CompositionPage("first", 0), CompositionPage("second", 1)))
        assertTrue(l.crossesBoundary(1, p.format.aspectRatio))
        assertFalse(p.canReorderPage(0))
    }

    @Test fun `fitted text and drawings scale their typography and strokes with the content`() {
        val text = CompositionElement.Text("a", ElementTransform(NormalizedRect(.1f, .1f, .2f, .1f)), 0, "Sample", fontSize = 40f, shadowOffsetX = 4f)
        val drawing = CompositionElement.Drawing("b", text.transform, 1, StrokeKind.PEN, listOf(StrokePoint(0f, 0f), StrokePoint(1f, 1f)), 0xFF557799, 8f)
        val l = layout(CompositionLayoutKind.COLUMN, listOf("a", "b")).copy(content = mapOf("a" to LayoutContent(2f), "b" to LayoutContent(2f)))
        val result = l.applyTo(listOf(text, drawing), 1f)
        val label = result[0] as CompositionElement.Text; val stroke = result[1] as CompositionElement.Drawing
        val factor = label.transform.bounds.width / text.transform.bounds.width
        assertEquals(text.fontSize * factor, label.fontSize, .0001f)
        assertEquals(text.shadowOffsetX * factor, label.shadowOffsetX, .0001f)
        assertEquals(drawing.strokeWidth * factor, stroke.strokeWidth, .0001f)
        assertEquals(text.text, label.text); assertEquals(drawing.points, stroke.points)
    }
}
