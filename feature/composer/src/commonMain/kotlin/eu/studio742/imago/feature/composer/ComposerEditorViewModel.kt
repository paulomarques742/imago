package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.CompositionArrangementReference
import eu.studio742.imago.core.composition.alignedSelection
import eu.studio742.imago.core.composition.distributedSelection
import eu.studio742.imago.core.composition.selectionUnits
import eu.studio742.imago.core.composition.visualBounds
import eu.studio742.imago.core.composition.*

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.UUID
import kotlin.math.floor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.CompositionTemplate
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.FrameStyle
import eu.studio742.imago.core.composition.MAX_COMPOSITION_PAGES
import eu.studio742.imago.core.composition.MAX_VIDEOS_PER_PAGE
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.boundingBox
import eu.studio742.imago.core.composition.mappedBetween
import eu.studio742.imago.core.composition.orbited
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.PhotoRecipeSnapshot
import eu.studio742.imago.core.composition.CompositionGuide
import eu.studio742.imago.core.composition.CompositionMoveSnapping
import eu.studio742.imago.core.composition.CompositionResizeSnapping
import eu.studio742.imago.core.composition.resizedSelectionFromCorner
import eu.studio742.imago.core.composition.withColorAlpha
import eu.studio742.imago.core.composition.PlacementCrop
import eu.studio742.imago.core.composition.ShapeKind
import eu.studio742.imago.core.composition.TextAlignment
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.composition.StrokePoint
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.ResizeCorner
import eu.studio742.imago.core.composition.MAX_BACKGROUND_BLUR
import eu.studio742.imago.core.composition.editableBounds
import eu.studio742.imago.core.composition.MIN_ELEMENT_SIZE
import eu.studio742.imago.core.composition.repeatOriginPage
import eu.studio742.imago.core.composition.normalizeDegrees
import eu.studio742.imago.core.composition.scaledAboutCentre
import eu.studio742.imago.core.composition.rotateVector
import eu.studio742.imago.core.composition.snapRotation
import eu.studio742.imago.core.composition.VideoTiming
import eu.studio742.imago.core.composition.VideoTimelineEdits
import eu.studio742.imago.core.composition.withMedia
import eu.studio742.imago.core.composition.canReorderPage
import eu.studio742.imago.core.composition.crossedBoundaries
import eu.studio742.imago.core.composition.withId
import eu.studio742.imago.core.composition.withLocked
import eu.studio742.imago.core.composition.withGroupId
import eu.studio742.imago.core.composition.withRepeatOnPages
import eu.studio742.imago.core.composition.withTransform
import eu.studio742.imago.core.composition.withVisible
import eu.studio742.imago.core.composition.withZIndex
import eu.studio742.imago.core.composition.withBackgroundCorner
import eu.studio742.imago.core.composition.withBackgroundPadding
import eu.studio742.imago.core.composition.withFont
import eu.studio742.imago.core.composition.withLetterSpacing
import eu.studio742.imago.core.composition.withLineSpacing
import eu.studio742.imago.core.composition.withShadowOffset
import eu.studio742.imago.core.composition.withShadowRadius
import eu.studio742.imago.core.composition.withStrokeWidth
import eu.studio742.imago.core.composition.withWeight
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.BrandKitRepository
import eu.studio742.imago.core.model.EditRecipe

/** The inspector sliders' limits, shared with the interface. */
const val MAX_FRAME_CORNER = .5f
const val MAX_FRAME_BORDER = 120f
const val MAX_FRAME_SHADOW = 48f
const val MIN_TEXT_SIZE = 8f
const val MAX_TEXT_SIZE = 240f
const val DEFAULT_TEXT_SIZE = 48f
/** A rectangle's or ellipse's outline can be switched off by taking it to zero. */
const val MIN_SHAPE_STROKE = 0f

/** A line or arrow *is* its stroke: at zero it would vanish. */
const val MIN_LINE_STROKE = .5f

fun ShapeKind.minStroke(): Float = if (this == ShapeKind.LINE || this == ShapeKind.ARROW) MIN_LINE_STROKE else MIN_SHAPE_STROKE

/** How far the eraser reaches around the finger, as a fraction of the page's width. */
const val ERASER_REACH = .03f
const val MAX_SHAPE_STROKE = 120f
const val MAX_DRAWING_STROKE = 40f
/**
 * The zoom of an element's framing. It starts at 1× — below that the image stopped covering the box
 * and background was left inside the frame, which is not what "framing" means.
 */
const val MIN_PLACEMENT_SCALE = 1f
const val MAX_PLACEMENT_SCALE = 3f

/**
 * The framing pushed by a drag.
 *
 * [dx]/[dy] come as a fraction of the slack: 1 is going from one end to the other. The inverted sign
 * is what makes the image follow the finger — see `nudgeBackgroundCrop`.
 */
internal fun PlacementCrop.nudged(dx: Float, dy: Float) = copy(
    offsetX = (offsetX - dx).coerceIn(-1f, 1f),
    offsetY = (offsetY - dy).coerceIn(-1f, 1f),
)

/** How far the copy moves away from the original, as a fraction of the page. Enough to see there are two. */
private const val DUPLICATE_OFFSET = .03f

/**
 * The page width the model's pixel measurements refer to.
 *
 * `fontSize` was already interpreted this way by the exporter — `element.fontSize * canvas.width / 1080f`
 * — but `strokeWidth` was not: the stage and the file used it raw, and as the page on the stage is
 * some four hundred pixels wide against the file's 1080, the same outline came out about two and a
 * half times thinner in the export than what had just been drawn.
 */
const val REFERENCE_PAGE_WIDTH = 1080f

data class ComposerEditorUiState(
    val project: CompositionProject? = null,
    /**
     * The element that rules the inspector.
     *
     * In a multiple selection it is the last one tapped: the type-dependent controls come from it —
     * a shape's colour, a label's text — because those only know how to act on one.
     */
    val selectedElementId: String? = null,
    /**
     * Everything selected, the primary included.
     *
     * It exists so that aligning two elements no longer requires grouping them first. With a single
     * element inside it behaves as it always did.
     */
    val selectedElementIds: Set<String> = emptySet(),
    val multiSelect: Boolean = false,
    val editingGroupId: String? = null,
    val arrangementReference: CompositionArrangementReference = CompositionArrangementReference.SELECTION,
    val groupMergeRequest: ComposerGroupMergeRequest? = null,
    val gridEditor: ComposerGridEditor? = null,
    val revealSelectionRequest: Int = 0,
    /** The background is a layer like the others: either it or an element, never both. */
    val backgroundSelected: Boolean = false,
    /** True while an element is being moved, rotated or resized on the stage. */
    val stageGesture: Boolean = false,
    val movementGuides: List<CompositionGuide> = emptyList(),
    val currentPage: Int = 0,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val notice: UiText? = null,
    val error: UiText? = null,
    val isExporting: Boolean = false,
    val exportCompleted: Int = 0,
    val exportTotal: Int = 0,
    val exportedUris: List<String> = emptyList(),
    val exportedMimeTypes: List<String> = emptyList(),
    val brandKit: BrandKit? = null,
    val isGeneratingProxy: Boolean = false,
    val previewProxyUri: String? = null,
)

open class ComposerEditorViewModel(
    private val projects: CompositionRepository,
    private val templates: CompositionTemplateRepository,
    private val mediaRepository: CompositionMediaRepository,
    private val recipes: RecipeRepository,
    private val exports: CompositionExports,
    private val brandKits: BrandKitRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ComposerEditorUiState())
    val state = mutableState.asStateFlow()
    private val history = mutableListOf<CompositionProject>()
    private var historyIndex = -1
    private var saveJob: Job? = null
    /**
     * The last version of the project known to be in the repository: the one read or the one saved.
     *
     * It is the measure of there being unsaved changes. Without it, leaving the editor always saved the
     * in-memory copy, and that copy overwrote everything that had changed in the project in the
     * meantime — a name given in the hub, a revision brought by sync.
     */
    private var persisted: CompositionProject? = null
    private val saveMutex = Mutex()
    private var exportJob: Job? = null
    private var proxyJob: Job? = null
    private var proxyFile: java.io.File? = null
    private var gestureActive = false
    private var gestureCommitted = false
    private var moveSnapping: CompositionMoveSnapping? = null
    private var resizeSnapping: CompositionResizeSnapping? = null
    /** The absolute angle the handle has already asked of the block, and the selection it belongs to. */
    private var groupRotationPivot: Pair<Float, Float>? = null

    init {
        viewModelScope.launch {
            brandKits.observe().collect { kit -> mutableState.update { it.copy(brandKit = kit) } }
        }
    }

    /**
     * Reads the project from the repository, even if it is the one already in memory.
     *
     * The same id does not mean the same content: the ViewModel survives the trip to the hub, and there
     * the project may have been renamed or synced. Whatever was left unsaved last time is saved first,
     * so that re-reading does not throw it away.
     */
    fun open(projectId: String) {
        viewModelScope.launch {
            state.value.project?.takeIf { it != persisted }?.let { unsaved ->
                saveJob?.cancel()
                // If it does not save, the in-memory copy stays: re-reading now would lose the change.
                runCatching { persist(unsaved) }.onFailure { error ->
                    mutableState.update { it.copy(error = error.toUiText(Res.string.composer_save_failed)) }
                    return@launch
                }
            }
            runCatching { checkNotNull(projects.get(projectId)) }
                .onSuccess { project ->
                    history.clear(); history += project; historyIndex = 0
                    persisted = project
                    // The brand comes from a separate flow, which does not emit again just because another project was opened.
                    mutableState.update { ComposerEditorUiState(project = project, isLoading = false, brandKit = it.brandKit) }
                }
                .onFailure { error -> mutableState.update { it.copy(isLoading = false, error = error.toUiText(Res.string.composer_open_failed)) } }
        }
    }

    fun selectElement(id: String?) {
        val target = state.value.project?.elements?.firstOrNull { it.id == id }
        val editing = state.value.editingGroupId?.takeIf { it == target?.groupId }
        val next = state.value.copy(editingGroupId = editing)
        if (id != null && !next.canEditElement(id)) return
        clearMovementGuides()
        val ids = if (target?.groupId != null && editing == null) state.value.project!!.elements
            .filter { it.groupId == target.groupId }.mapTo(mutableSetOf()) { it.id } else setOfNotNull(id)
        mutableState.update { it.copy(selectedElementId = id, selectedElementIds = ids, multiSelect = false,
            backgroundSelected = false, editingGroupId = editing, groupMergeRequest = null) }
    }

    /** Layers can recover an element outside the viewport, including one already selected. */
    fun focusElement(id: String) {
        if (!canEdit(id)) return
        selectElement(id)
        mutableState.update { it.copy(revealSelectionRequest = it.revealSelectionRequest + 1) }
    }

    fun beginMultiSelection(id: String) {
        val target = state.value.project?.elements?.firstOrNull { it.id == id } ?: return
        if (state.value.editingGroupId != null && target.groupId != state.value.editingGroupId) selectElement(id)
        if (!canEdit(id)) return
        clearMovementGuides()
        mutableState.update {
            val unit = if (target.groupId != null && it.editingGroupId != target.groupId) it.project!!.elements
                .filter { member -> member.groupId == target.groupId }.mapTo(mutableSetOf()) { member -> member.id } else setOf(id)
            val ids = if (it.multiSelect) it.selectedElementIds + unit else unit
            it.copy(selectedElementId = id, selectedElementIds = ids, multiSelect = true, backgroundSelected = false)
        }
    }

    fun toggleMultiSelect() = mutableState.update { it.copy(multiSelect = !it.multiSelect) }

    fun toggleElementInSelection(id: String) {
        val target = state.value.project?.elements?.firstOrNull { it.id == id } ?: return
        if (state.value.editingGroupId != null && target.groupId != state.value.editingGroupId) {
            selectElement(id); beginMultiSelection(id); return
        }
        if (!canEdit(id)) return
        clearMovementGuides()
        mutableState.update {
            val unit = if (target.groupId != null && it.editingGroupId != target.groupId) it.project!!.elements
                .filter { member -> member.groupId == target.groupId }.mapTo(mutableSetOf()) { member -> member.id } else setOf(id)
            val ids = if (id in it.selectedElementIds) it.selectedElementIds - unit else it.selectedElementIds + unit
            it.copy(selectedElementId = if (id in ids) id else ids.firstOrNull(), selectedElementIds = ids,
                multiSelect = ids.isNotEmpty(), backgroundSelected = false)
        }
    }

    fun selectBackground() {
        clearMovementGuides()
        mutableState.update {
            it.copy(selectedElementId = null, selectedElementIds = emptySet(), multiSelect = false, backgroundSelected = true,
                editingGroupId = null, groupMergeRequest = null)
        }
    }

    fun clearSelection() {
        clearMovementGuides()
        mutableState.update {
            it.copy(selectedElementId = null, selectedElementIds = emptySet(), multiSelect = false, backgroundSelected = false,
                editingGroupId = null, groupMergeRequest = null)
        }
    }
    fun selectPage(index: Int) = mutableState.update { state ->
        state.copy(currentPage = index.coerceIn(0, (state.project?.pages?.lastIndex ?: 0)))
    }

    /**
     * Marks the start and end of a continuous gesture.
     *
     * Without this, every frame of a drag pushed a new entry into the history: a single gesture filled
     * the hundred slots and "undo" stepped back one pixel at a time. During the gesture `commit`
     * replaces the top entry instead, so a drag is worth one undo.
     *
     * It is a switch and not a counter on purpose. An `onValueChangeFinished` that never fires —
     * because the slider left the composition halfway — would leave a counter stuck above zero and
     * every following edit would merge into one. This way, the worst that happens is a gesture ending
     * too early.
     */
    fun beginGesture(stage: Boolean = true) {
        clearMovementGuides()
        val members = state.value.editableSelection()
        groupRotationPivot = members.takeIf { it.size > 1 }?.map { it.transform.bounds }?.boundingBox()
            ?.let { it.x + it.width / 2f to it.y + it.height / 2f }
        gestureActive = true
        gestureCommitted = false
        // The screen clears while the hand works: whoever drags an element is looking at the page,
        // not at the tools. [stage] separates the two places a gesture comes from: moving an element
        // fades the whole panel, but dragging a slider cannot fade it — the finger is resting on it.
        // There only the surrounding chrome leaves.
        if (stage) mutableState.update { it.copy(stageGesture = true) }
    }

    fun endGesture(stage: Boolean = true) {
        gestureActive = false
        groupRotationPivot = null
        clearMovementGuides()
        if (stage) mutableState.update { it.copy(stageGesture = false) }
    }

    /** The grabbed repeat supplies its page offset; the saved placement remains relative to its origin. */
    fun beginMoveGesture(instanceOffsetX: Float = 0f) {
        beginGesture()
        val project = state.value.manipulationProject()
        val ids = state.value.manipulationIds()
        if (ids.isNotEmpty()) moveSnapping = CompositionMoveSnapping(project, ids, instanceOffsetX)
    }

    fun moveSelectedWithGuides(dx: Float, dy: Float, toleranceX: Float, toleranceY: Float) {
        val snapping = moveSnapping ?: return
        val ids = state.value.manipulationIds()
        if (ids != snapping.elementIds) { clearMovementGuides(); return }
        val result = snapping.moveBy(dx, dy, toleranceX, toleranceY)
        if (result.dx != 0f || result.dy != 0f) moveSelected(result.dx, result.dy)
        mutableState.update { it.copy(movementGuides = result.guides) }
    }

    private fun clearMovementGuides() {
        moveSnapping = null
        resizeSnapping = null
        mutableState.update { if (it.movementGuides.isEmpty()) it else it.copy(movementGuides = emptyList()) }
    }

    fun beginResizeGesture(corner: ResizeCorner) {
        beginGesture()
        val project = state.value.manipulationProject()
        val ids = state.value.manipulationIds()
        if (ids.isNotEmpty()) resizeSnapping = CompositionResizeSnapping(project, ids, corner)
    }

    fun resizeSelectedWithGuides(dx: Float, dy: Float, toleranceX: Float, toleranceY: Float) {
        val snapping = resizeSnapping ?: return
        val ids = state.value.manipulationIds()
        if (ids != snapping.elementIds) { clearMovementGuides(); return }
        val result = snapping.resizeBy(dx, dy, toleranceX, toleranceY)
        applyResizedSelection(result.elements)
        mutableState.update { it.copy(movementGuides = result.guides) }
    }

    fun addPage() {
        val project = requireProject()
        if (project.pages.size >= MAX_COMPOSITION_PAGES) return notice(uiText(Res.string.composer_max_pages, MAX_COMPOSITION_PAGES))
        val insertAt = state.value.currentPage + 1
        if (project.elements.any { insertAt in it.crossedBoundaries() } || project.layouts.any { it.crossesBoundary(insertAt, project.format.aspectRatio) }) {
            return notice(uiText(Res.string.composer_insert_page_blocked))
        }
        val pages = project.pages.toMutableList().apply {
            add(insertAt, CompositionPage(UUID.randomUUID().toString(), insertAt))
        }.mapIndexed { index, page -> page.copy(index = index) }
        val elements = project.elements.map { element ->
            val origin = element.pageOrigin()
            val delta = if (origin >= insertAt) 1 else 0
            val shifted = element.withTransform(element.transform.copy(
                bounds = element.transform.bounds.copy(x = element.transform.bounds.x + delta),
                repeatOriginPage = if (element.repeatOnPages.isEmpty()) null else origin + delta,
            ))
            val withPage = if (shifted is CompositionElement.Video) shifted.copy(pageIndex = shifted.pageIndex + delta) else shifted
            withPage.withRepeatOnPages(withPage.repeatOnPages.mapTo(mutableSetOf()) { if (it >= insertAt) it + 1 else it })
        }
        commit { project.copy(pages = pages, elements = elements) }
        selectPage(insertAt)
    }

    fun deleteCurrentPage() {
        val project = requireProject()
        val index = state.value.currentPage
        if (project.pages.size == 1) return notice(uiText(Res.string.composer_needs_one_page))
        if (!project.canReorderPage(index)) return notice(uiText(Res.string.composer_delete_page_blocked))
        val remaining = project.pages.filterNot { it.index == index }.mapIndexed { newIndex, page -> page.copy(index = newIndex) }
        val adjusted = project.elements.mapNotNull { element ->
            val origin = element.pageOrigin()
            val repeats = element.repeatOnPages.mapNotNullTo(mutableSetOf()) { page ->
                when { page == index -> null; page > index -> page - 1; else -> page }
            }
            if (element.repeatOnPages.isNotEmpty() && repeats.isEmpty()) return@mapNotNull null
            if (element.repeatOnPages.isEmpty() && origin == index) return@mapNotNull null
            val newOrigin = when {
                origin > index -> origin - 1
                origin == index -> repeats.first()
                else -> origin
            }
            val moved = element.withTransform(element.transform.copy(
                bounds = element.transform.bounds.copy(x = element.transform.bounds.x + newOrigin - origin),
                repeatOriginPage = if (repeats.isEmpty()) null else newOrigin,
            ))
            val withPage = if (moved is CompositionElement.Video) moved.copy(pageIndex = newOrigin) else moved
            withPage.withRepeatOnPages(repeats)
        }
        commit { project.copy(pages = remaining, elements = adjusted) }
        selectPage(index.coerceAtMost(remaining.lastIndex))
    }

    fun movePage(direction: Int) {
        val project = requireProject()
        val from = state.value.currentPage
        val to = from + direction
        if (to !in project.pages.indices) return
        if (!project.canReorderPage(minOf(from, to))) return notice(uiText(Res.string.composer_reorder_blocked))
        val pages = project.pages.toMutableList().apply { add(to, removeAt(from)) }
            .mapIndexed { index, page -> page.copy(index = index) }
        fun remapPage(page: Int): Int = when (page) { from -> to; to -> from; else -> page }
        val elements = project.elements.map { element ->
            val origin = element.pageOrigin()
            val target = remapPage(origin)
            val moved = element.withTransform(element.transform.copy(
                bounds = element.transform.bounds.copy(x = element.transform.bounds.x + target - origin),
                repeatOriginPage = if (element.repeatOnPages.isEmpty()) null else target,
            ))
            val withPage = if (moved is CompositionElement.Video) moved.copy(pageIndex = target) else moved
            withPage.withRepeatOnPages(withPage.repeatOnPages.mapTo(mutableSetOf(), ::remapPage))
        }
        commit { project.copy(pages = pages, elements = elements) }; selectPage(to)
    }

    private fun CompositionElement.pageOrigin(): Int = when {
        this is CompositionElement.Video -> pageIndex
        repeatOnPages.isNotEmpty() -> repeatOriginPage()
        else -> kotlin.math.floor(transform.bounds.x).toInt()
    }

    /** [placeholder] comes from the screen, already in the app language: it is saved in the project as its text. */
    fun addText(placeholder: String) {
        val project = requireProject()
        val id = UUID.randomUUID().toString()
        val page = state.value.currentPage.toFloat()
        commit { project.copy(elements = project.elements + CompositionElement.Text(
            id, ElementTransform(NormalizedRect(page + .12f, .38f, .76f, .22f)), nextZ(project), placeholder,
        )) }
        selectElement(id)
    }

    /**
     * Adds a shape where the finger put it. Without [bounds] it lands in the middle of the current
     * page, which is what the old menu items did; with them, the shape is exactly the box that was
     * dragged out on the stage. Colours are optional so the model's defaults stay in one place.
     */
    fun addShape(
        kind: ShapeKind,
        bounds: NormalizedRect? = null,
        rotationDegrees: Float = 0f,
        fillArgb: Long? = null,
        strokeArgb: Long? = null,
        strokeWidth: Float? = null,
    ) {
        val project = requireProject()
        val id = UUID.randomUUID().toString()
        val page = state.value.currentPage.toFloat()
        val placed = bounds ?: NormalizedRect(page + .25f, .3f, .5f, .35f)
        val base = CompositionElement.Shape(
            id, ElementTransform(placed, rotationDegrees), nextZ(project), kind,
        )
        val shape = base.copy(
            fillArgb = fillArgb ?: base.fillArgb,
            strokeArgb = strokeArgb ?: base.strokeArgb,
            strokeWidth = strokeWidth?.coerceIn(kind.minStroke(), MAX_SHAPE_STROKE) ?: base.strokeWidth,
        )
        commit { project.copy(elements = project.elements + shape) }
        selectElement(id)
    }

    private var pendingDrawingId: String? = null
    private var drawingTimeout: Job? = null

    fun finishDrawingSession() {
        drawingTimeout?.cancel()
        drawingTimeout = null
        pendingDrawingId = null
    }

    fun beginDrawingStroke() {
        // A pause is measured from lifting the pen, never while a long stroke is still being drawn.
        drawingTimeout?.cancel()
        drawingTimeout = null
    }

    fun addDrawing(
        points: List<StrokePoint>,
        kind: StrokeKind = StrokeKind.PEN,
        colorArgb: Long = 0xFFFFFFFF,
        strokeWidth: Float = if (kind == StrokeKind.MARKER) 16f else 6f,
    ) {
        if (points.size < 2) return
        val project = requireProject()
        val previous = project.elements.filterIsInstance<CompositionElement.Drawing>().find {
            it.id == pendingDrawingId && it.kind == kind && it.colorArgb == colorArgb && it.strokeWidth == strokeWidth
        }
        val oldPoints = previous?.let { drawing ->
            val b = drawing.transform.bounds
            drawing.points.map { it.copy(x = b.x + it.x * b.width, y = b.y + it.y * b.height) }
        }.orEmpty()
        val combined = oldPoints + points.mapIndexed { index, point ->
            point.copy(startsStroke = index == 0)
        }
        val minX = combined.minOf { it.x }.coerceIn(0f, project.pages.size.toFloat())
        val maxX = combined.maxOf { it.x }.coerceIn(minX, project.pages.size.toFloat())
        val minY = combined.minOf { it.y }.coerceIn(0f, 1f)
        val maxY = combined.maxOf { it.y }.coerceIn(minY, 1f)
        val width = (maxX - minX).coerceAtLeast(.001f)
        val height = (maxY - minY).coerceAtLeast(.001f)
        val localPoints = combined.map { point ->
            point.copy(
                x = ((point.x - minX) / width).coerceIn(0f, 1f),
                y = ((point.y - minY) / height).coerceIn(0f, 1f),
                // Without it every stored stroke was back at full pressure: the stylus width showed
                // while drawing and was lost the moment the pen lifted.
                pressure = point.pressure,
            )
        }
        val id = previous?.id ?: UUID.randomUUID().toString()
        commit { project.copy(elements = project.elements.filterNot { it.id == id } + CompositionElement.Drawing(
            id = id,
            transform = ElementTransform(NormalizedRect(minX, minY, width, height)),
            zIndex = previous?.zIndex ?: nextZ(project),
            kind = kind,
            points = localPoints,
            colorArgb = colorArgb,
            strokeWidth = strokeWidth,
        )) }
        selectElement(id)
        pendingDrawingId = id
        drawingTimeout?.cancel()
        drawingTimeout = viewModelScope.launch {
            delay(2_000)
            pendingDrawingId = null
            drawingTimeout = null
        }
    }

    fun eraseDrawing(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val project = requireProject()
        val hitRadiusSquared = ERASER_REACH * ERASER_REACH
        val remaining = project.elements.filterNot { element ->
            element is CompositionElement.Drawing && element.points.any { point ->
                val globalX = element.transform.bounds.x + point.x * element.transform.bounds.width
                val globalY = element.transform.bounds.y + point.y * element.transform.bounds.height
                points.any { eraser ->
                    val dx = globalX - eraser.x
                    val dy = globalY - eraser.y
                    dx * dx + dy * dy <= hitRadiusSquared
                }
            }
        }
        if (remaining.size != project.elements.size) commit { project.copy(elements = remaining) }
    }

    fun setBackground(background: CompositionBackground) = commit { requireProject().copy(background = background) }
    fun toggleSafeZones() = commit { requireProject().let { it.copy(safeZonesVisible = !it.safeZonesVisible) } }

    /** The background shown on the current page: its override, or the project's continuous one. */
    fun currentBackground(): CompositionBackground? = state.value.project?.let { project ->
        project.pages.getOrNull(state.value.currentPage)?.backgroundOverride ?: project.background
    }

    private fun applyBackground(background: CompositionBackground, currentPageOnly: Boolean) {
        val project = requireProject()
        if (!currentPageOnly) return setBackground(background)
        val index = state.value.currentPage
        commit { project.copy(pages = project.pages.map { if (it.index == index) it.copy(backgroundOverride = background) else it }) }
    }

    /** Applies to the background in view, be it the page's or the continuous one. */
    private fun mutateCurrentBackground(block: (CompositionBackground) -> CompositionBackground) {
        val project = requireProject()
        val index = state.value.currentPage
        val override = project.pages.getOrNull(index)?.backgroundOverride
        if (override != null) applyBackground(block(override), currentPageOnly = true)
        else setBackground(block(project.background))
    }

    /**
     * Sets a library photo as the background without placing it as an element first.
     *
     * It used to be the only path there was: import, select and only then promote.
     */
    fun setPhotoBackground(media: ComposerMedia, currentPageOnly: Boolean) {
        if (media.isVideo) return notice(uiText(Res.string.composer_background_must_be_photo))
        viewModelScope.launch {
            // The photo comes with the look the library shows it with, as a photo element does.
            val recipe = listOf(media).toInsertable(recipes).single().recipe
            val previousBlur = (currentBackground() as? CompositionBackground.Photo)?.blurRadius ?: 0f
            applyBackground(
                CompositionBackground.Photo(
                    media.toReference(),
                    blurRadius = previousBlur,
                    recipe = recipe?.let(::PhotoRecipeSnapshot),
                ),
                currentPageOnly,
            )
            selectBackground()
        }
    }

    fun setBackgroundBlur(radius: Float) = mutateCurrentBackground { background ->
        if (background is CompositionBackground.Photo) background.copy(blurRadius = radius.coerceIn(0f, MAX_BACKGROUND_BLUR)) else background
    }

    fun setBackgroundGradientAngle(degrees: Float) = mutateCurrentBackground { background ->
        if (background is CompositionBackground.Gradient) background.copy(angleDegrees = normalizeDegrees(degrees)) else background
    }

    /**
     * The two ends of the gradient.
     *
     * They go through [mutateCurrentBackground] and not through `applyBackground`: changing a colour is
     * editing the background in view, be it the page's or the project's continuous one. Routing them
     * through the apply path deleted the page's override every time a colour was touched.
     */
    fun setBackgroundGradientStart(argb: Long) = mutateCurrentBackground { background ->
        if (background is CompositionBackground.Gradient) background.copy(startArgb = argb) else background
    }

    fun setBackgroundGradientEnd(argb: Long) = mutateCurrentBackground { background ->
        if (background is CompositionBackground.Gradient) background.copy(endArgb = argb) else background
    }

    /** The colour of a plain background, keeping the scope — page or project — already in force. */
    fun setBackgroundSolidColor(argb: Long) = mutateCurrentBackground { background ->
        if (background is CompositionBackground.Solid) background.copy(argb = argb) else background
    }

    fun setBackgroundCropScale(scale: Float) = mutateCurrentBackground { background ->
        if (background is CompositionBackground.Photo) {
            background.copy(crop = background.crop.copy(scale = scale.coerceIn(.5f, 3f)))
        } else background
    }

    /**
     * Background position mode: dragging over the image instead of two X/Y sliders.
     *
     * [dx]/[dy] already arrive as a fraction of the framing's slack — whoever drags knows the box's size
     * and the image's aspect ratio, and the conversion lives next to the drawing, in `placementSlack`.
     * Here it is only accumulated and clamped to the range.
     *
     * The sign is that of `drawCover`, in the exporter: a larger `offsetX` looks further to the right of
     * the image, which makes it slide to the left — so dragging to the right **lowers** the value. It
     * is the inversion that makes the image follow the finger.
     */
    fun nudgeBackgroundCrop(dx: Float, dy: Float) = mutateCurrentBackground { background ->
        if (background !is CompositionBackground.Photo) return@mutateCurrentBackground background
        background.copy(crop = background.crop.nudged(dx, dy))
    }

    /**
     * The same framing, for the chosen photo or video.
     *
     * Resizing an element changes the mask and not the image: it keeps covering the box and what is
     * left over stays out, in the middle. This is what allows choosing which part stays out.
     */
    fun nudgeSelectedCrop(dx: Float, dy: Float) = mutateSelected { element ->
        when (element) {
            is CompositionElement.Photo -> element.copy(crop = element.crop.nudged(dx, dy))
            is CompositionElement.Video -> element.copy(crop = element.crop.nudged(dx, dy))
            else -> element
        }
    }

    /**
     * The pinch inside the mask.
     *
     * [factor] is **this** frame's ratio, of the order of 1.005, as in `scaleSelected`: it accumulates on
     * the value that is there, never compared with a threshold.
     */
    fun scaleSelectedCropBy(factor: Float) = mutateSelected { element ->
        val crop = when (element) {
            is CompositionElement.Photo -> element.crop
            is CompositionElement.Video -> element.crop
            else -> return@mutateSelected element
        }
        val scaled = (crop.scale * factor).coerceIn(MIN_PLACEMENT_SCALE, MAX_PLACEMENT_SCALE)
        when (element) {
            is CompositionElement.Photo -> element.copy(crop = crop.copy(scale = scaled))
            is CompositionElement.Video -> element.copy(crop = crop.copy(scale = scaled))
            else -> element
        }
    }

    /** The zoom inside the mask — it is what gives slack when the aspect ratio already fits. */
    fun setSelectedCropScale(scale: Float) = mutateSelected { element ->
        val clamped = scale.coerceIn(MIN_PLACEMENT_SCALE, MAX_PLACEMENT_SCALE)
        when (element) {
            is CompositionElement.Photo -> element.copy(crop = element.crop.copy(scale = clamped))
            is CompositionElement.Video -> element.copy(crop = element.crop.copy(scale = clamped))
            else -> element
        }
    }

    fun setSolidBackground(argb: Long, currentPageOnly: Boolean) =
        applyBackground(CompositionBackground.Solid(argb), currentPageOnly)

    fun setGradientBackground(startArgb: Long, endArgb: Long, currentPageOnly: Boolean) =
        applyBackground(CompositionBackground.Gradient(startArgb, endArgb, 25f), currentPageOnly)

    fun useSelectedPhotoAsBackground(forCurrentPage: Boolean, blurred: Boolean = false) {
        val project = requireProject()
        val photo = project.elements.firstOrNull { it.id == state.value.selectedElementId } as? CompositionElement.Photo
            ?: return notice(uiText(Res.string.composer_select_photo_for_background))
        val background = CompositionBackground.Photo(photo.media, photo.crop, blurRadius = if (blurred) 24f else 0f, recipe = photo.recipe)
        if (forCurrentPage) {
            val index = state.value.currentPage
            commit { project.copy(pages = project.pages.map { if (it.index == index) it.copy(backgroundOverride = background) else it }) }
        } else setBackground(background)
    }

    fun clearCurrentPageBackground() {
        val project = requireProject()
        val index = state.value.currentPage
        commit { project.copy(pages = project.pages.map { if (it.index == index) it.copy(backgroundOverride = null) else it }) }
    }

    fun updateSelectedText(value: String) = mutateSelected { element ->
        if (element is CompositionElement.Text) element.copy(text = value) else element
    }

    fun updateSelectedPhotoRecipe(recipe: EditRecipe) = mutateSelected { element ->
        if (element is CompositionElement.Photo) element.copy(recipe = PhotoRecipeSnapshot(recipe)) else element
    }

    fun applyBrandColor(argb: Long) {
        if (state.value.backgroundSelected || state.value.selectedElementId == null) {
            // The scope comes from what is in view, and is no longer always the whole project: on a
            // page with its own background, choosing a palette colour deleted that background and
            // painted every page — the opposite of what "This page only" had just asked for.
            val onPage = state.value.project?.pages?.getOrNull(state.value.currentPage)?.backgroundOverride != null
            return applyBackground(CompositionBackground.Solid(argb), currentPageOnly = onPage)
        }
        mutateSelected { element ->
            when (element) {
                is CompositionElement.Text -> element.copy(colorArgb = argb)
                is CompositionElement.Shape -> element.copy(fillArgb = argb)
                is CompositionElement.Drawing -> element.copy(colorArgb = argb)
                else -> element
            }
        }
    }

    /** A brand font on the selected text. The weight stays what the text had. */
    fun applyBrandFont(fontId: String) = setTextFont(fontId)

    /**
     * The selected photo becomes a brand logo, at the end of the list. It starts from the saved kit and
     * not from what the state shows, like the brand editor, so as not to undo a recent edit.
     */
    fun addSelectedPhotoAsLogo() = viewModelScope.launch {
        val photo = requireProject().elements.firstOrNull { it.id == state.value.selectedElementId } as? CompositionElement.Photo
            ?: return@launch
        val previous = brandKits.observe().first()
        val kit = BrandKitEdits.addLogos(previous ?: BrandKit(updatedAt = ""), listOf(photo.media))
        if (kit != previous) brandKits.save(kit.copy(updatedAt = Instant.now().toString()))
        notice(uiText(Res.string.composer_photo_added_to_logos))
    }

    fun addBrandLogo(reference: MediaReference) {
        val project = requireProject()
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        commit { project.copy(elements = project.elements + CompositionElement.Photo(
            id = id,
            transform = ElementTransform(NormalizedRect(state.value.currentPage + .68f, .05f, .25f, .15f)),
            zIndex = nextZ(project),
            media = reference,
            recipe = PhotoRecipeSnapshot(EditRecipe(
                assetId = reference.assetId,
                originalChecksum = reference.checksum,
                createdAt = now,
                updatedAt = now,
            )),
            repeatOnPages = project.pages.indices.toSet(),
        )) }
        selectElement(id)
    }

    /**
     * Puts these media on the page.
     *
     * The placement is the same the library uses when it sends photos to a composition — first the
     * layout's empty slots, then a cascading stack — with two differences that are the composer's: the
     * stack starts on the page on stage, and not on the last one, and the element the picker was opened
     * from jumps the queue to be served first.
     */
    fun addMedia(media: List<ComposerMedia>, replaceElementId: String? = null) = viewModelScope.launch {
        if (media.isEmpty() || replaceElementId != null && !canEdit(replaceElementId)) return@launch
        val project = requireProject()
        val existing = project.elements.mapTo(mutableSetOf(), CompositionElement::id)
        val insertion = project.withMedia(
            media = media.toInsertable(recipes),
            now = Instant.now().toString(),
            stackPage = state.value.currentPage,
            firstSlotId = replaceElementId,
        )
        if (insertion.skipped > 0) {
            notice(uiText(Res.string.composer_videos_per_page_limit, MAX_VIDEOS_PER_PAGE))
        }
        if (insertion.placed == 0) return@launch
        commit { insertion.project }
        // What came in stays chosen, so it can be arranged right away: the stack is born stacked, and
        // without this each one had to be fetched from the stage before touching it.
        val added = insertion.project.elements.filterNot { it.id in existing }.map(CompositionElement::id)
        mutableState.update {
            it.copy(
                selectedElementId = added.lastOrNull(),
                selectedElementIds = added.toSet(),
                backgroundSelected = false,
                editingGroupId = null,
            )
        }
    }

    fun moveSelected(dx: Float, dy: Float) {
        if (resizableBlock() == null) return moveSingle(dx, dy)
        val project = requireProject()
        val ids = project.selectionMembers().filterNot(CompositionElement::locked).mapTo(mutableSetOf()) { it.id }
        // Translate the complete set without applying a different edge constraint to each member.
        val stepX = dx.takeIf { it.isFinite() } ?: 0f
        val stepY = dy.takeIf { it.isFinite() } ?: 0f
        if (stepX == 0f && stepY == 0f) return
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id !in ids) return@map element
            val b = element.transform.bounds
            val moved = element.editableBounds(b.copy(x = b.x + stepX, y = b.y + stepY), project.pages.size)
            element.withTransform(element.transform.copy(bounds = moved))
        }) }
    }

    private fun moveSingle(dx: Float, dy: Float) = transformSelectedElement { element, transform ->
        val project = requireProject()
        val b = transform.bounds
        val x = b.x + (dx.takeIf { it.isFinite() } ?: 0f)
        val y = b.y + (dy.takeIf { it.isFinite() } ?: 0f)
        transform.copy(bounds = element.editableBounds(b.copy(x = x, y = y), project.pages.size))
    }

    /**
     * Resizes from a corner. [dx] and [dy] already come in the element's frame of reference — the
     * counter-rotation happens in the overlay, which is what knows the stage's pixels.
     */
    fun resizeSelectedFromCorner(corner: ResizeCorner, dx: Float, dy: Float) {
        val project = requireProject()
        state.value.manipulatedLayout()?.let { layout ->
            val resized = listOf(layout.proxy()).resizedSelectionFromCorner(corner, dx, dy, project.pages.size, project.format.aspectRatio).single()
            return setSelectedLayoutFrame(resized.transform)
        }
        applyResizedSelection(project.selectionMembers().resizedSelectionFromCorner(
            corner, dx, dy, project.pages.size, project.format.aspectRatio))
    }

    private fun applyResizedSelection(resized: List<CompositionElement>) {
        val project = requireProject()
        if (state.value.manipulatedLayout() != null && resized.size == 1) return setSelectedLayoutFrame(resized.single().transform)
        val transforms = resized.associate { it.id to it.transform }
        val elements = project.elements.map { element ->
            transforms[element.id]?.let { element.withTransform(it) } ?: element
        }
        if (elements != project.elements) commit { project.copy(elements = elements) }
    }

    /** Scales the artwork around its centre, respecting each member's resource limits. */
    fun scaleSelected(factor: Float) {
        state.value.manipulatedLayout()?.let { layout ->
            return setSelectedLayoutFrame(layout.frame.copy(bounds = layout.frame.bounds.scaledAboutCentre(factor,
                maxWidth = requireProject().pages.size.toFloat(), maxHeight = 1f)))
        }
        val block = resizableBlock() ?: return transformSelectedElement { element, transform ->
            val scaled = transform.bounds.scaledAboutCentre(factor,
                maxWidth = if (element is CompositionElement.Video) 1f else requireProject().pages.size.toFloat(), maxHeight = 1f)
            transform.copy(bounds = element.editableBounds(scaled, requireProject().pages.size))
        }
        val members = requireProject().selectionMembers()
        val minimum = members.maxOf { maxOf(MIN_ELEMENT_SIZE / it.transform.bounds.width, MIN_ELEMENT_SIZE / it.transform.bounds.height) }
        val maximum = members.minOf { minOf((if (it is CompositionElement.Video) 1f else requireProject().pages.size.toFloat()) /
            it.transform.bounds.width, 1f / it.transform.bounds.height) }
        val ratio = (factor.takeIf { it.isFinite() && it > 0f } ?: 1f).coerceIn(minimum, maximum)
        reshapeSelection(block, block.scaledAboutCentre(ratio, maxSize = Float.MAX_VALUE))
    }

    /**
     * The rectangle that covers the selection, when it is more than one element.
     *
     * It returns `null` for a single element — then there is no block at all, and the caller follows
     * the usual simple path.
     */
    private fun resizableBlock(): NormalizedRect? {
        val members = state.value.project?.selectionMembers()?.filterNot(CompositionElement::locked).orEmpty()
        if (members.size < 2) return null
        return members.map { it.transform.bounds }.boundingBox()
    }

    /**
     * Takes the whole selection from [box] to [target], as a block.
     *
     * Each element keeps the place it had within the set: the relative position and the size scale by
     * the same ratio. Applying the handle's offset to each one equally — which is what used to be done
     * — fattened them all by the same amount and undid the arrangement: two cards side by side ended up
     * overlapping, and the rectangle the handle showed was not what came out.
     *
     * Size limits still apply; spatial movement never changes a clip's page or timing.
     */
    private fun reshapeSelection(box: NormalizedRect, target: NormalizedRect) {
        val project = requireProject()
        val members = project.selectionMembers().filterNot(CompositionElement::locked)
        val ids = members.mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        val minX = members.maxOf { MIN_ELEMENT_SIZE / it.transform.bounds.width }
        val minY = members.maxOf { MIN_ELEMENT_SIZE / it.transform.bounds.height }
        val maxX = members.minOf { (if (it is CompositionElement.Video) 1f else project.pages.size.toFloat()) / it.transform.bounds.width }
        val maxY = members.minOf { 1f / it.transform.bounds.height }
        val width = box.width * (target.width / box.width).coerceIn(minX, maxX)
        val height = box.height * (target.height / box.height).coerceIn(minY, maxY)
        fun anchor(before: Float, next: Float, originalSize: Float, wantedSize: Float, limitedSize: Float): Float {
            val difference = wantedSize - originalSize
            val fraction = if (kotlin.math.abs(difference) < .00001f) 0f else (before - next) / difference
            return before - (limitedSize - originalSize) * fraction
        }
        val limited = NormalizedRect(anchor(box.x, target.x, box.width, target.width, width),
            anchor(box.y, target.y, box.height, target.height, height), width, height)
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id !in ids) return@map element
            val next = element.transform.bounds.mappedBetween(box, limited)
            element.withTransform(element.transform.copy(bounds = element.editableBounds(next, project.pages.size)))
        }) }
    }

    /** Absolute rotation, for the handle and the slider. [snap] only when it comes from a gesture. */
    fun setSelectedRotation(degrees: Float, snap: Boolean = false) {
        val target = if (snap) snapRotation(degrees) else normalizeDegrees(degrees)
        state.value.manipulatedLayout()?.let { layout ->
            val primary = requireProject().elements.firstOrNull { it.id == state.value.selectedElementId } ?: return
            return setSelectedLayoutFrame(layout.frame.copy(rotationDegrees = normalizeDegrees(layout.frame.rotationDegrees + target - primary.transform.rotationDegrees)))
        }
        if (resizableBlock() == null) {
            return transformSelected { it.copy(rotationDegrees = target) }
        }
        val primary = requireProject().elements.firstOrNull { it.id == state.value.selectedElementId } ?: return
        rotateSelectionBlock(target - primary.transform.rotationDegrees)
    }

    /**
     * Incremental rotation, for the two-finger pinch.
     *
     * Unlike [setSelectedRotation], it does not receive the final angle — it adds to what is there. It
     * has to be so: a pinch delivers **this frame's** angle, not the accumulated one, and adding here,
     * inside `transformSelected`, always reads the most recent value of the state. A lambda on the
     * canvas doing the same calculation from a variable captured in the composition stayed stuck on
     * the value from one frame earlier whenever two touches of the gesture fell in the same UI frame.
     */
    fun rotateSelectedBy(deltaDegrees: Float) {
        state.value.manipulatedLayout()?.let { layout ->
            return setSelectedLayoutFrame(layout.frame.copy(rotationDegrees = normalizeDegrees(layout.frame.rotationDegrees + deltaDegrees)))
        }
        if (resizableBlock() != null) return rotateSelectionBlock(deltaDegrees)
        transformSelected { it.copy(rotationDegrees = normalizeDegrees(it.rotationDegrees + deltaDegrees)) }
    }

    /**
     * Rotates the selection like a sheet of paper with things stuck on it: each element orbits the
     * set's centre **and** rotates on itself.
     *
     * The orbit is computed in pixels, not in normalised coordinates. Those are not square — one unit
     * in x is worth the page's width and one in y its height — and rotating directly in them tilted
     * the arrangement instead of rotating it.
     */
    private fun rotateSelectionBlock(deltaDegrees: Float) {
        if (deltaDegrees == 0f) return
        val block = resizableBlock() ?: return
        val project = requireProject()
        val ids = project.selectionMembers().filterNot(CompositionElement::locked).mapTo(mutableSetOf()) { it.id }
        val centreX = groupRotationPivot?.first ?: (block.x + block.width / 2f)
        val centreY = groupRotationPivot?.second ?: (block.y + block.height / 2f)
        val aspect = project.format.pixelWidth.toFloat() / project.format.pixelHeight
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id !in ids) return@map element
            val next = element.transform.bounds.orbited(centreX, centreY, deltaDegrees, aspect)
            element.withTransform(
                element.transform.copy(
                    bounds = element.editableBounds(next, project.pages.size),
                    rotationDegrees = normalizeDegrees(element.transform.rotationDegrees + deltaDegrees),
                ),
            )
        }) }
    }

    fun toggleMirrorHorizontal() = transformSelected { it.copy(mirrorHorizontal = !it.mirrorHorizontal) }
    fun toggleMirrorVertical() = transformSelected { it.copy(mirrorVertical = !it.mirrorVertical) }
    fun setSelectedOpacity(value: Float) {
        val project = requireProject()
        val ids = project.selectionMembers().map { it.id }.toSet()
        if (ids.isEmpty()) return
        val opacity = value.coerceIn(.05f, 1f)
        val elements = project.elements.map { if (it.id in ids) it.withTransform(it.transform.copy(opacity = opacity)) else it }
        val layouts = project.layouts.map { layout ->
            if (state.value.editingGroupId == null && layout.root.memberIds().all(ids::contains)) layout.copy(frame = layout.frame.copy(opacity = opacity)) else layout
        }
        if (elements != project.elements || layouts != project.layouts) commit { project.copy(elements = elements, layouts = layouts) }
    }

    fun setFrameCorner(value: Float) = mutateFrame { it.copy(cornerRadius = value.coerceIn(0f, MAX_FRAME_CORNER)) }
    fun setFrameBorder(value: Float) = mutateFrame { it.copy(borderWidth = value.coerceIn(0f, MAX_FRAME_BORDER)) }
    fun setFrameShadow(value: Float) = mutateFrame { it.copy(shadowRadius = value.coerceIn(0f, MAX_FRAME_SHADOW)) }

    fun setMediaFrameDesign(cutout: MediaCutout, decoration: MediaFrameDecoration) {
        val project = requireProject()
        val members = state.value.frameDesignSelection()
        if (members.isEmpty()) return
        val ids = members.map { it.id }.toSet()
        val elements = project.elements.map { element ->
            val frame = element.mediaFrame()
            if (element.id !in ids || frame == null) element else element.withMediaFrame(frame.copy(cutout = cutout, decoration = decoration))
        }
        if (elements != project.elements) commitDiscrete { project.copy(elements = elements) }
    }

    /**
     * The frame outline's colour. It always existed in the model and on export — `borderArgb` — but
     * there was no way to change it: the inspector had the thickness and nothing else, so the outline
     * was white for everyone.
     */
    fun setFrameBorderColor(argb: Long) = mutateFrame { it.copy(borderArgb = argb) }

    fun setShapeFill(argb: Long) = mutateSelected { element ->
        if (element is CompositionElement.Shape) element.copy(fillArgb = argb) else element
    }

    fun setShapeNoFill() = mutateSelected { element ->
        if (element is CompositionElement.Shape && element.isClosedShape()) element.copy(fillArgb = element.fillArgb.withColorAlpha(0f)) else element
    }

    fun setOutlineColor(argb: Long) = mutateOutline { _, style -> style.copy(borderArgb = argb) }
    fun setOutlineOpacity(value: Float) = mutateOutline { _, style -> style.copy(borderArgb = style.borderArgb.withColorAlpha(value)) }
    fun setOutlineWidth(value: Float) = mutateOutline { element, style ->
        val minimum = if (element is CompositionElement.Shape) element.kind.minStroke() else 0f
        style.copy(borderWidth = value.coerceIn(minimum, MAX_SHAPE_STROKE))
    }
    fun setOutlineCorner(value: Float) = mutateOutline { element, style ->
        if (element.hasOutlineCorners()) style.copy(cornerRadius = value.coerceIn(0f, MAX_FRAME_CORNER)) else style
    }

    private fun mutateOutline(block: (CompositionElement, FrameStyle) -> FrameStyle) {
        if (state.value.outlineSelection().isEmpty()) return
        mutateSelected { element ->
            val before = element.outlineStyle() ?: return@mutateSelected element
            val style = block(element, before)
            when (element) {
                is CompositionElement.Shape -> element.copy(strokeArgb = style.borderArgb, strokeWidth = style.borderWidth,
                    cornerRadius = if (element.kind == ShapeKind.RECTANGLE) style.cornerRadius else element.cornerRadius)
                is CompositionElement.Photo -> element.copy(frame = style)
                is CompositionElement.Video -> element.copy(frame = style)
                is CompositionElement.MediaPlaceholder -> element.copy(frame = style)
                else -> element
            }
        }
    }

    fun setShapeStroke(argb: Long) = mutateSelected { element ->
        if (element is CompositionElement.Shape) element.copy(strokeArgb = argb) else element
    }

    fun setShapeStrokeWidth(value: Float) = mutateSelected { element ->
        if (element is CompositionElement.Shape) {
            element.copy(strokeWidth = value.coerceIn(element.kind.minStroke(), MAX_SHAPE_STROKE))
        } else element
    }

    /** The colour of text and of the freehand stroke, which until now only changed through the brand kit's palette. */
    fun setElementColor(argb: Long) = mutateSelected { element ->
        when (element) {
            is CompositionElement.Text -> element.copy(colorArgb = argb)
            is CompositionElement.Drawing -> element.copy(colorArgb = argb)
            else -> element
        }
    }

    private fun mutateFrame(block: (FrameStyle) -> FrameStyle) = mutateSelected { element ->
        when (element) {
            is CompositionElement.MediaPlaceholder -> element.copy(frame = block(element.frame))
            is CompositionElement.Photo -> element.copy(frame = block(element.frame))
            is CompositionElement.Video -> element.copy(frame = block(element.frame))
            else -> element
        }
    }

    fun setTextSize(value: Float) = mutateSelected { element ->
        if (element is CompositionElement.Text) element.copy(fontSize = value.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)) else element
    }

    fun setTextFont(fontId: String) = mutateText { it.withFont(fontId) }
    fun setTextWeight(weight: Int) = mutateText { it.withWeight(weight) }
    fun setTextAlignment(alignment: TextAlignment) = mutateText { it.copy(alignment = alignment) }
    fun toggleTextAllCaps() = mutateText { it.copy(allCaps = !it.allCaps) }
    fun setTextLetterSpacing(value: Float) = mutateText { it.withLetterSpacing(value) }
    fun setTextLineSpacing(value: Float) = mutateText { it.withLineSpacing(value) }

    /** Turns the outline on with [argb], or off with `null`. The thickness stays saved meanwhile. */
    fun setTextStroke(argb: Long?) = mutateText { it.copy(strokeArgb = argb) }
    fun setTextStrokeWidth(value: Float) = mutateText { it.withStrokeWidth(value) }
    fun setTextShadow(argb: Long?) = mutateText { it.copy(shadowArgb = argb) }
    fun setTextShadowRadius(value: Float) = mutateText { it.withShadowRadius(value) }
    fun setTextShadowOffsetX(value: Float) = mutateText { it.withShadowOffset(x = value) }
    fun setTextShadowOffsetY(value: Float) = mutateText { it.withShadowOffset(y = value) }
    fun setTextBackground(argb: Long?) = mutateText { it.copy(backgroundArgb = argb) }
    fun setTextBackgroundPadding(value: Float) = mutateText { it.withBackgroundPadding(value) }
    fun setTextBackgroundCorner(value: Float) = mutateText { it.withBackgroundCorner(value) }

    /** Like [mutateSelected], but only touches the texts in the selection. */
    private fun mutateText(block: (CompositionElement.Text) -> CompositionElement.Text) = mutateSelected { element ->
        if (element is CompositionElement.Text) block(element) else element
    }

    fun alignSelectedHorizontal(mode: Int) = alignSelected(mode, null)
    fun alignSelectedVertical(mode: Int) = alignSelected(null, mode)

    /**
     * Spreads the elements with equal gaps between the first and the last.
     *
     * It stopped requiring a group: choosing three things is enough. Three is the minimum that means
     * anything — with two there is nothing between them to space.
     */
    fun setArrangementReference(reference: CompositionArrangementReference) = mutableState.update { it.copy(arrangementReference = reference) }

    private fun arrangementBounds(members: List<CompositionElement>): NormalizedRect? {
        val project = requireProject()
        val units = members.selectionUnits(state.value.editingGroupId)
        val reference = if (units.size < 2) CompositionArrangementReference.PAGE else state.value.arrangementReference
        if (reference == CompositionArrangementReference.SELECTION) return arrangementRepresentatives(members).map { it.visualBounds(aspect = project.format.aspectRatio) }.boundingBox()
        if (members.any { it is CompositionElement.Video && it.pageIndex != state.value.currentPage }) {
            notice(uiText(Res.string.composer_arrange_video_page)); return null
        }
        return NormalizedRect(state.value.currentPage.toFloat(), 0f, 1f, 1f)
    }

    fun distributeSelected(horizontal: Boolean) {
        val members = state.value.editableSelection()
        val minimum = if (state.value.arrangementReference == CompositionArrangementReference.PAGE) 2 else 3
        if (members.selectionUnits(state.value.editingGroupId).size < minimum) {
            return notice(uiText(if (minimum == 2) Res.string.composer_distribute_needs_two else Res.string.composer_distribute_needs_three))
        }
        val bounds = arrangementBounds(members) ?: return
        applyArrangedSelection(arrangementRepresentatives(members).distributedSelection(horizontal, bounds, requireProject().format.aspectRatio, state.value.editingGroupId))
    }

    private fun arrangementRepresentatives(members: List<CompositionElement>): List<CompositionElement> {
        if (state.value.editingGroupId != null) return members
        val ids = members.map { it.id }.toSet()
        val layouts = requireProject().layouts.filter { it.root.memberIds().all(ids::contains) }
        val layoutIds = layouts.flatMap { it.root.memberIds() }.toSet()
        return members.filterNot { it.id in layoutIds } + layouts.map { it.proxy() }
    }

    private fun applyArrangedSelection(members: List<CompositionElement>) {
        val project = requireProject()
        val replacements = members.associateBy { it.id }
        val offsets = if (state.value.editingGroupId != null) emptyMap() else project.layouts.mapNotNull { layout ->
            val proxy = replacements[layout.root.memberIds().first()] ?: return@mapNotNull null
            if (proxy is CompositionElement.MediaPlaceholder && proxy.groupId == layout.groupId && proxy.transform.bounds.width == layout.frame.bounds.width)
                layout.groupId to (proxy.transform.bounds.x - layout.frame.bounds.x to proxy.transform.bounds.y - layout.frame.bounds.y) else null
        }.toMap()
        val elements = project.elements.map { element ->
            val offset = offsets[element.groupId]
            if (offset == null) replacements[element.id] ?: element else element.withTransform(element.transform.copy(
                bounds = element.transform.bounds.copy(x = element.transform.bounds.x + offset.first, y = element.transform.bounds.y + offset.second)))
        }
        if (elements != project.elements) commitDiscrete { project.copy(elements = elements) }
    }
    /**
     * The timeline's edits. They name the clip, and not the selection, because the timeline shows every
     * video on the page and any of them can be dragged; the one dragged becomes the selection.
     *
     * Values are in page time, already snapped by the timeline. The page does not grow with the clip:
     * how long the page lasts is its own handle on the timeline, and a clip that runs past it is cut
     * there, as in the export.
     */
    fun moveVideoClip(id: String, startOffsetMs: Long) =
        updateVideoTiming(id) { _, timing -> VideoTimelineEdits.move(timing, startOffsetMs) }

    fun trimVideoClipStart(id: String, pageMs: Long) =
        updateVideoTiming(id) { _, timing -> VideoTimelineEdits.trimStart(timing, pageMs) }

    fun trimVideoClipEnd(id: String, pageMs: Long) =
        updateVideoTiming(id) { video, timing -> VideoTimelineEdits.trimEnd(timing, pageMs, video.media.durationMs) }

    /** Whether a clip is heard. Turning one on takes away a solo that would keep it silent. */
    fun setVideoAudible(id: String, audible: Boolean) {
        if (!canEdit(id)) return
        val project = requireProject()
        val video = project.elements.firstOrNull { it.id == id } as? CompositionElement.Video ?: return
        val soloElsewhere = project.elements.any {
            it is CompositionElement.Video && it.id != id && it.pageIndex == video.pageIndex && it.timing.solo
        }
        if (audible && soloElsewhere && project.elements.any { it is CompositionElement.Video && it.pageIndex == video.pageIndex && it.timing.solo && !canEdit(it.id) }) return
        commit {
            project.copy(elements = project.elements.map { element ->
                when {
                    element !is CompositionElement.Video || element.pageIndex != video.pageIndex -> element
                    element.id == id -> element.copy(timing = element.timing.copy(
                        muted = !audible,
                        volume = if (audible && element.timing.volume == 0f) 1f else element.timing.volume,
                    ))
                    audible && soloElsewhere -> element.copy(timing = element.timing.copy(solo = false))
                    else -> element
                }
            })
        }
    }

    fun setPageDuration(pageIndex: Int, durationMs: Long) {
        val project = requireProject()
        if (pageIndex !in project.pages.indices) return
        val duration = VideoTimelineEdits.pageDuration(durationMs)
        commit { project.copy(pages = project.pages.map { if (it.index == pageIndex) it.copy(durationMs = duration) else it }) }
    }

    fun adjustVideoVolume(delta: Float) = updateSelectedVideoTiming { _, timing ->
        timing.copy(volume = (timing.volume + delta).coerceIn(0f, 1f))
    }

    fun toggleVideoMute() = updateSelectedVideoTiming { _, timing -> timing.copy(muted = !timing.muted) }
    fun toggleVideoSolo() = updateSelectedVideoTiming { _, timing -> timing.copy(solo = !timing.solo) }
    fun toggleVideoLoop() = updateSelectedVideoTiming { _, timing -> timing.copy(loop = !timing.loop) }

    fun toggleLocked() {
        val project = requireProject()
        val ids = project.selectionMembers().mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        commit { project.copy(elements = project.elements.map { if (it.id in ids) it.withLocked(true) else it }) }
        clearSelection()
    }
    fun toggleVisible() = mutateSelected { it.withVisible(!it.visible) }

    fun toggleElementLocked(id: String, wholeGroup: Boolean = true) {
        val project = requireProject()
        val target = project.elements.firstOrNull { it.id == id } ?: return
        commit { project.copy(elements = project.elements.map {
            if (it.id == id || wholeGroup && target.groupId != null && it.groupId == target.groupId) it.withLocked(!target.locked) else it
        }) }
        mutableState.update { current ->
            val ids = current.selectedElementIds.filterTo(mutableSetOf()) { selected -> current.canEditElement(selected) }
            current.copy(selectedElementIds = ids, selectedElementId = current.selectedElementId?.takeIf { it in ids } ?: ids.firstOrNull(),
                multiSelect = current.multiSelect && ids.isNotEmpty())
        }
    }

    fun toggleElementVisible(id: String) {
        val project = requireProject()
        val target = project.elements.firstOrNull { it.id == id } ?: return
        commit { project.copy(elements = project.elements.map { if (it.id == id) it.withVisible(!target.visible) else it }) }
    }

    internal fun canEdit(id: String): Boolean {
        return state.value.canEditElement(id)
    }

    fun moveElement(id: String, dx: Float, dy: Float) { if (canEdit(id)) { selectElement(id); moveSelected(dx, dy) } }
    fun bringElementForward(id: String) { if (canEdit(id)) { selectElement(id); bringForward() } }
    fun sendElementBackward(id: String) { if (canEdit(id)) { selectElement(id); sendBackward() } }
    fun deleteElement(id: String) { if (canEdit(id)) { selectElement(id); deleteSelected() } }

    fun groupSelectedWith(otherId: String) {
        if (!canEdit(otherId)) return
        val selectedId = state.value.selectedElementId ?: return
        if (selectedId == otherId || !canEdit(selectedId)) return
        mutableState.update { it.copy(selectedElementIds = setOf(selectedId, otherId), editingGroupId = null, multiSelect = true) }
        groupSelection()
    }

    /**
     * Groups what is chosen, without going through the "with which one?" dialog.
     *
     * That dialog exists for when there is only one element in hand and one has to say who it joins.
     * With several chosen the question is already answered.
     */
    fun groupSelection() {
        if (state.value.editingGroupId != null) return notice(uiText(Res.string.composer_finish_group_editing))
        val members = state.value.editableSelection()
        if (members.selectionUnits().size < 2) return notice(uiText(Res.string.composer_group_needs_two))
        if (members.any { it.groupId != null }) mutableState.update {
            it.copy(groupMergeRequest = ComposerGroupMergeRequest(members.associate { member -> member.id to member.groupId }))
        } else createGroup(members)
    }

    fun cancelGroupMerge() = mutableState.update { it.copy(groupMergeRequest = null) }

    fun confirmGroupMerge() {
        val request = state.value.groupMergeRequest ?: return
        val members = state.value.editableSelection()
        cancelGroupMerge()
        if (members.associate { it.id to it.groupId } != request.members) return notice(uiText(Res.string.composer_group_selection_changed))
        createGroup(members)
    }

    private fun createGroup(members: List<CompositionElement>) {
        val project = requireProject()
        val group = UUID.randomUUID().toString()
        val ids = members.mapTo(mutableSetOf()) { it.id }
        commitDiscrete { project.copy(elements = project.elements.map { if (it.id in ids) it.withGroupId(group) else it }) }
        mutableState.update { it.copy(selectedElementIds = ids, multiSelect = false, editingGroupId = null) }
    }

    fun ungroupSelected() {
        val project = requireProject()
        val selectedIds = state.value.selectionElements().mapTo(mutableSetOf()) { it.id }
        val groups = state.value.selectionElements().mapNotNullTo(mutableSetOf()) { it.groupId }
        if (groups.isEmpty()) return
        val members = project.elements.filter { it.groupId in groups }
        if (members.any { it.locked }) return notice(uiText(Res.string.composer_group_has_locked_members))
        commitDiscrete { project.copy(elements = project.elements.map { if (it.groupId in groups) it.withGroupId(null) else it }) }
        mutableState.update { it.copy(editingGroupId = null, selectedElementIds = selectedIds + members.map { member -> member.id }, multiSelect = true) }
    }

    fun beginGrid() {
        val project = requireProject()
        val members = state.value.selectionElements()
        if (members.size < 2 || state.value.editingGroupId != null) return
        if (state.value.editableSelection().size != members.size) return notice(uiText(Res.string.composer_group_has_locked_members))
        if (members.any { it.repeatOnPages.isNotEmpty() }) return notice(uiText(Res.string.composer_grid_repeated_notice))
        val existing = state.value.selectedLayout()
        val reference = if (existing == null) state.value.arrangementReference else CompositionArrangementReference.SELECTION
        val box = existing?.frame?.bounds ?: if (reference == CompositionArrangementReference.PAGE)
            NormalizedRect(state.value.currentPage.toFloat(), 0f, 1f, 1f) else members.map { it.visualBounds(aspect = project.format.aspectRatio) }.boundingBox()!!
        if (members.filterIsInstance<CompositionElement.Video>().any { it.pageIndex != state.value.currentPage })
            return notice(uiText(Res.string.composer_grid_video_page_notice))
        val columns = kotlin.math.ceil(kotlin.math.sqrt(members.size.toDouble())).toInt()
        val layout = existing ?: CompositionLayout(UUID.randomUUID().toString(), ElementTransform(box),
            layoutTree(members.sortedBy { it.zIndex }.map { it.id }, CompositionLayoutKind.GRID, columns), columns = columns,
            content = members.associate { it.id to LayoutContent(it.transform.bounds.width * project.format.aspectRatio / it.transform.bounds.height,
                it.transform.rotationDegrees) })
        val groups = members.mapNotNull { it.groupId }.distinct()
        mutableState.update { it.copy(gridEditor = ComposerGridEditor(project, layout, reference, it.currentPage,
            combinesGroups = existing == null && groups.isNotEmpty())) }
    }

    fun cancelGrid() = mutableState.update { it.copy(gridEditor = null) }

    fun openGrid(groupId: String) {
        val project = requireProject()
        if (project.layouts.none { it.groupId == groupId } || project.elements.any { it.groupId == groupId && it.locked }) return
        selectGroup(groupId)
        beginGrid()
    }

    fun swapGridDraftMembers(first: String, second: String) {
        val editor = state.value.gridEditor ?: return
        if ((editor.base.elements + editor.addedElements).any { it.id in setOf(first, second) && it.locked }) return
        val next = editor.layout.copy(root = editor.layout.root.swapMembers(first, second))
        if (!gridVideoBoundsFit(next, editor.copy(layout = next).preview().elements)) return
        updateGridDraft(next)
    }

    fun createGridFromMedia(media: List<ComposerMedia>, pageIndex: Int = state.value.currentPage) =
        insertGridMedia(media, null, false, pageIndex)

    fun addMediaToGrid(media: List<ComposerMedia>, groupId: String, draft: Boolean = false) =
        insertGridMedia(media, groupId, draft, state.value.currentPage)

    private fun insertGridMedia(media: List<ComposerMedia>, groupId: String?, draft: Boolean, pageIndex: Int) = viewModelScope.launch {
        if (media.isEmpty()) return@launch
        if (groupId == null && media.size < 2) return@launch notice(uiText(Res.string.composer_grid_choose_multiple))
        val incoming = media.toInsertable(recipes)
        val project = requireProject()
        val editor = if (draft) state.value.gridEditor?.takeIf { it.layout.groupId == groupId } ?: return@launch else null
        if (editor != null && editor.base != project) return@launch notice(uiText(Res.string.composer_grid_changed_notice))
        val layout = editor?.layout ?: groupId?.let { id -> project.layouts.firstOrNull { it.groupId == id } ?: return@launch }
        val source = editor?.preview() ?: project
        if (groupId != null && source.elements.any { it.groupId == groupId && it.locked }) {
            return@launch notice(uiText(Res.string.composer_group_has_locked_members))
        }
        if (groupId != null && source.elements.any { it.groupId == groupId && it.repeatOnPages.isNotEmpty() }) {
            return@launch notice(uiText(Res.string.composer_grid_repeated_notice))
        }
        val videoPage = groupId?.let { id -> source.elements.filterIsInstance<CompositionElement.Video>().firstOrNull { it.groupId == id }?.pageIndex }
        val page = (videoPage ?: editor?.pageIndex ?: layout?.frame?.bounds?.x?.let { floor(it).toInt() } ?: pageIndex).coerceIn(source.pages.indices)
        if (incoming.count { it.isVideo } + source.elements.count { it is CompositionElement.Video && it.pageIndex == page } > MAX_VIDEOS_PER_PAGE) {
            return@launch notice(uiText(Res.string.composer_videos_per_page_limit, MAX_VIDEOS_PER_PAGE))
        }
        // Keep existing videos for capacity checks, but never fill unrelated placeholders.
        val insertion = source.copy(elements = source.elements.filterIsInstance<CompositionElement.Video>(), layouts = emptyList())
            .withMedia(incoming, Instant.now().toString(), stackPage = page)
        val existingIds = source.elements.mapTo(mutableSetOf()) { it.id }
        val nextZ = (source.elements.maxOfOrNull { it.zIndex } ?: -1) + 1
        val added = insertion.project.elements.filterNot { it.id in existingIds }.mapIndexed { index, element ->
            element.withZIndex(nextZ + index).withTransform(element.transform.copy(opacity = layout?.frame?.opacity ?: 1f))
        }
        if (added.size != incoming.size) return@launch notice(uiText(Res.string.composer_videos_per_page_limit, MAX_VIDEOS_PER_PAGE))
        val ids = layout?.root?.memberIds().orEmpty() + added.map { it.id }
        val columns = layout?.columns ?: kotlin.math.ceil(kotlin.math.sqrt(ids.size.toDouble())).toInt()
        val kind = layout?.kind ?: CompositionLayoutKind.GRID
        val next = (layout ?: CompositionLayout(UUID.randomUUID().toString(), ElementTransform(NormalizedRect(page.toFloat(), 0f, 1f, 1f)),
            layoutTree(ids, kind, columns), columns = columns)).let {
            it.copy(root = layoutTree(ids, kind, columns, it.rows), content = it.content + added.associate { element ->
                element.id to LayoutContent(element.transform.bounds.width * project.format.aspectRatio / element.transform.bounds.height)
            })
        }
        val elements = next.applyTo(source.elements + added, source.format.aspectRatio)
        if (!gridVideoBoundsFit(next, elements)) return@launch
        if (editor != null) {
            mutableState.update { it.copy(gridEditor = editor.copy(layout = next, addedElements = editor.addedElements + added, pages = insertion.project.pages)) }
        } else {
            commitDiscrete { source.copy(elements = elements, pages = insertion.project.pages,
                layouts = source.layouts.filterNot { it.groupId == next.groupId } + next) }
            selectGroup(next.groupId)
        }
    }

    fun updateGridDraft(layout: CompositionLayout) {
        val editor = state.value.gridEditor ?: return
        if (layout.groupId != editor.layout.groupId || layout.root.memberIds().toSet() != editor.layout.root.memberIds().toSet()) return
        mutableState.update { it.copy(gridEditor = editor.copy(layout = layout)) }
    }

    fun setGridKind(kind: CompositionLayoutKind, columns: Int? = null, rows: Int? = null) {
        val editor = state.value.gridEditor ?: return
        val count = (columns ?: editor.layout.columns).coerceIn(1, editor.layout.root.memberIds().size)
        val rowCount = (rows ?: if (columns == null) editor.layout.rows else 0).coerceIn(0, editor.layout.root.memberIds().size)
        updateGridDraft(editor.layout.copy(kind = kind, columns = count, rows = rowCount, root = layoutTree(editor.layout.root.memberIds(), kind, count, rowCount)))
    }

    fun setGridReference(reference: CompositionArrangementReference) {
        val editor = state.value.gridEditor ?: return
        val ids = editor.layout.root.memberIds().toSet()
        val box = if (reference == CompositionArrangementReference.PAGE) NormalizedRect(editor.pageIndex.toFloat(), 0f, 1f, 1f)
            else (editor.base.elements + editor.addedElements).filter { it.id in ids }.map { it.visualBounds(aspect = editor.base.format.aspectRatio) }.boundingBox()!!
        mutableState.update { it.copy(gridEditor = editor.copy(reference = reference, layout = editor.layout.copy(frame = editor.layout.frame.copy(bounds = box, rotationDegrees = 0f)))) }
    }

    fun confirmGrid() {
        val editor = state.value.gridEditor ?: return
        val project = requireProject()
        if (project != editor.base) { cancelGrid(); return notice(uiText(Res.string.composer_grid_changed_notice)) }
        val ids = editor.layout.root.memberIds().toSet()
        if (project.elements.any { it.id in ids && it.locked }) return notice(uiText(Res.string.composer_group_has_locked_members))
        val preview = editor.preview()
        if (!gridVideoBoundsFit(editor.layout, preview.elements)) return
        if (preview.elements == project.elements && preview.layouts == project.layouts) { cancelGrid(); return }
        commitDiscrete { preview }
        if (state.value.project?.layouts?.any { it.groupId == editor.layout.groupId } == true) {
            mutableState.update { it.copy(gridEditor = null, selectedElementId = ids.first(), selectedElementIds = ids,
                editingGroupId = null, multiSelect = false) }
        }
    }

    fun adjustGridDivider(groupId: String, splitId: String, index: Int, delta: Float) {
        val project = requireProject()
        val layout = project.layouts.firstOrNull { it.groupId == groupId } ?: return
        if (project.elements.any { it.groupId == groupId && it.locked }) return
        val root = layout.root.adjustDivider(splitId, index, delta)
        if (root == layout.root) return
        val next = layout.copy(root = root)
        val elements = next.applyTo(project.elements, project.format.aspectRatio)
        if (!gridVideoBoundsFit(next, elements)) return
        commit { project.copy(elements = elements,
            layouts = project.layouts.map { if (it.groupId == groupId) next else it }) }
    }

    private fun setSelectedLayoutFrame(frame: ElementTransform) {
        val project = requireProject()
        val layout = state.value.manipulatedLayout() ?: return
        if (frame == layout.frame) return
        val next = layout.copy(frame = frame)
        val elements = next.applyTo(project.elements, project.format.aspectRatio)
        if (!gridVideoBoundsFit(next, elements)) return
        commit { project.copy(elements = elements,
            layouts = project.layouts.map { if (it.groupId == layout.groupId) next else it }) }
    }

    private fun gridVideoBoundsFit(layout: CompositionLayout, elements: List<CompositionElement>): Boolean {
        val ids = layout.root.memberIds().toSet()
        if (elements.any { it.id in ids && it is CompositionElement.Video && (it.transform.bounds.width > 1f || it.transform.bounds.height > 1f) }) {
            notice(uiText(Res.string.composer_grid_video_size_notice)); return false
        }
        return true
    }

    fun selectGroup(groupId: String) {
        val member = requireProject().elements.firstOrNull { it.groupId == groupId } ?: return
        val next = state.value.copy(editingGroupId = null)
        if (!next.canEditElement(member.id)) return
        mutableState.update { it.copy(editingGroupId = null) }
        focusElement(member.id)
    }

    fun editGroup(groupId: String) {
        val member = requireProject().elements.firstOrNull { it.groupId == groupId && !it.locked } ?: return
        focusGroupMember(member.id)
    }

    fun focusGroupMember(id: String) {
        val element = requireProject().elements.firstOrNull { it.id == id && !it.locked } ?: return
        val group = element.groupId ?: return focusElement(id)
        clearMovementGuides()
        mutableState.update { it.copy(editingGroupId = group, selectedElementId = id, selectedElementIds = setOf(id),
            multiSelect = false, backgroundSelected = false, revealSelectionRequest = it.revealSelectionRequest + 1) }
    }

    fun finishEditingGroup() {
        val group = state.value.editingGroupId ?: return
        if (requireProject().elements.any { it.groupId == group && it.locked }) clearSelection() else selectGroup(group)
    }

    fun beginGroupMemberMultiSelection(id: String) {
        if (state.value.editingGroupId != requireProject().elements.firstOrNull { it.id == id }?.groupId) focusGroupMember(id)
        beginMultiSelection(id)
    }

    fun deleteGroupMember(id: String) {
        val project = requireProject()
        if (project.elements.firstOrNull { it.id == id }?.locked != false) return
        commitDiscrete { project.copy(elements = project.elements.filterNot { it.id == id }) }
    }

    fun deleteGroup(groupId: String) {
        val project = requireProject()
        val members = project.elements.filter { it.groupId == groupId }
        if (members.isEmpty() || members.any { it.locked }) return
        commitDiscrete { project.copy(elements = project.elements.filterNot { it.groupId == groupId }) }
    }

    fun toggleGroupLocked(groupId: String) {
        val project = requireProject()
        val members = project.elements.filter { it.groupId == groupId }
        if (members.isEmpty()) return
        val lock = members.none { it.locked }
        commitDiscrete { project.copy(elements = project.elements.map { if (it.groupId == groupId) it.withLocked(lock) else it }) }
        clearSelection()
    }

    fun toggleGroupVisible(groupId: String) {
        val project = requireProject()
        if (project.elements.none { it.groupId == groupId }) return
        val show = project.elements.none { it.groupId == groupId && it.visible }
        commitDiscrete { project.copy(elements = project.elements.map { if (it.groupId == groupId) it.withVisible(show) else it }) }
    }
    fun selectionRepeatsOnAllPages(): Boolean {
        val project = state.value.project ?: return false
        val members = project.selectionMembers().filterNot { it is CompositionElement.Video }
        return members.isNotEmpty() && members.all { it.repeatOnPages == project.pages.indices.toSet() }
    }

    fun repeatOnAllPages() = changePageRepetition(true)
    fun stopRepeatingSelected() = changePageRepetition(false)

    private fun changePageRepetition(repeat: Boolean) {
        val project = requireProject()
        val selected = project.selectionMembers()
        if (repeat && project.layouts.any { layout ->
                val members = selected.filter { it.id in layout.root.memberIds() }
                members.isNotEmpty() && (members.any { it is CompositionElement.Video } || members.map { it.repeatOriginPage() }.distinct().size > 1)
            }) return notice(uiText(Res.string.composer_grid_repeat_notice))
        val ids = project.selectionMembers().filterNot { it is CompositionElement.Video }.mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        // A menu command must never be absorbed into a slider whose finish callback was lost.
        endGesture()
        val pages = if (repeat) project.pages.indices.toSet() else emptySet()
        val elements = project.elements.map { if (it.id in ids) it.withRepeatOnPages(pages) else it }
        if (elements != project.elements) commit { project.copy(elements = elements) }
    }

    fun bringForward() = moveSelectedLayer(1)
    fun sendBackward() = moveSelectedLayer(-1)

    /**
     * Duplicates what is chosen, offset enough to see there are two.
     *
     * The copies come out with a new `groupId` — linked to each other and loose from the originals,
     * which is what is expected of a copy of a group. The selection jumps to them: duplicating is
     * almost always the first step of "and now I change this one".
     */
    fun duplicateSelected() {
        val project = requireProject()
        val members = project.selectionMembers().ifEmpty { return }
        val groups = members.mapNotNull { it.groupId }.distinct().associateWith { UUID.randomUUID().toString() }
        val editing = state.value.editingGroupId
        var z = nextZ(project)
        val copies = members.map { element ->
            val bounds = element.transform.bounds
            val shifted = element.editableBounds(
                bounds.copy(x = bounds.x + DUPLICATE_OFFSET, y = bounds.y + DUPLICATE_OFFSET),
                project.pages.size,
            )
            element
                .withId(UUID.randomUUID().toString())
                .withTransform(element.transform.copy(bounds = shifted))
                .withGroupId(element.groupId?.let { if (it == editing) it else groups[it] })
                .withZIndex(z++)
        }
        val copiedIds = members.zip(copies).associate { (original, copy) -> original.id to copy.id }
        val copiedLayouts = if (editing != null) emptyList() else project.layouts.mapNotNull { layout ->
            val group = groups[layout.groupId] ?: return@mapNotNull null
            if (layout.root.memberIds().any { it !in copiedIds }) null else layout.remap(group, copiedIds, DUPLICATE_OFFSET, DUPLICATE_OFFSET)
        }
        commitDiscrete { project.copy(elements = project.elements + copies, layouts = project.layouts + copiedLayouts) }
        // Only after `commit`: if the invariants refuse the copy, the selection has to stay on what still
        // exists instead of pointing to ids that never reached the project.
        if (state.value.project?.elements?.any { it.id == copies.first().id } == true) {
            mutableState.update {
                it.copy(
                    selectedElementId = copies.last().id,
                    selectedElementIds = copies.mapTo(mutableSetOf()) { copy -> copy.id },
                    backgroundSelected = false,
                )
            }
        }
    }

    fun deleteSelected() {
        val project = requireProject()
        val ids = project.selectionMembers().mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        commitDiscrete { project.copy(elements = project.elements.filterNot { it.id in ids }) }
        clearSelection()
    }

    fun undo() {
        endGesture()
        if (historyIndex <= 0) return
        historyIndex--
        restoreHistory()
    }

    fun redo() {
        endGesture()
        if (historyIndex >= history.lastIndex) return
        historyIndex++
        restoreHistory()
    }

    private fun restoreHistory() {
        val snapshot = history[historyIndex]
        val saved = persisted
        val restored = if (saved != null && snapshot.copy(revision = saved.revision, updatedAt = saved.updatedAt) == saved) saved
            else snapshot.copy(revision = requireProject().revision + 1, updatedAt = Instant.now().toString())
        restore(restored)
        scheduleSave(restored)
    }

    fun saveNow() {
        requestSave(false)
    }

    fun saveDraft() {
        requestSave(true)
    }

    private fun requestSave(confirm: Boolean) {
        saveJob?.cancel()
        val project = state.value.project ?: return
        if (project == persisted) {
            mutableState.update { it.copy(isSaving = false, saveFailed = false) }
            if (confirm) notice(uiText(Res.string.composer_draft_saved))
            return
        }
        mutableState.update { it.copy(isSaving = true, saveFailed = false) }
        saveJob = viewModelScope.launch {
            try {
                persist(project)
                mutableState.update { it.copy(isSaving = false, saveFailed = false) }
                if (confirm) notice(uiText(Res.string.composer_draft_saved))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(isSaving = false, saveFailed = true, error = error.toUiText(Res.string.composer_save_failed)) }
            }
        }
    }

    fun saveAsTemplate(name: String, includeMedia: Boolean) = viewModelScope.launch {
        val project = requireProject()
        val now = Instant.now().toString()
        val templateProject = if (includeMedia) project else project.copy(elements = project.elements.map { element ->
            when (element) {
                is CompositionElement.Photo -> CompositionElement.MediaPlaceholder(
                    element.id, element.transform, element.zIndex, frame = element.frame,
                    groupId = element.groupId, locked = element.locked, visible = element.visible,
                )
                is CompositionElement.Video -> CompositionElement.MediaPlaceholder(
                    element.id, element.transform, element.zIndex, acceptsVideo = true, frame = element.frame,
                    groupId = element.groupId, locked = element.locked, visible = element.visible,
                )
                else -> element
            }
        })
        templates.save(CompositionTemplate(UUID.randomUUID().toString(), name.trim(), templateProject, includeMedia, now, now))
        notice(uiText(Res.string.composer_template_saved))
    }

    fun export(
        format: StaticExportFormat,
        currentPageOnly: Boolean,
        targetLibraryId: String? = null,
        videoQuality: VideoExportQuality = VideoExportQuality.Default,
    ) {
        if (exportJob?.isActive == true) return
        val project = requireProject()
        val pageIndex = state.value.currentPage.takeIf { currentPageOnly }
        exportJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isExporting = true,
                    exportCompleted = 0,
                    exportTotal = if (currentPageOnly) 1 else project.pages.size,
                    exportedUris = emptyList(),
                    exportedMimeTypes = emptyList(),
                )
            }
            runCatching { persist(project) }.onFailure { error ->
                mutableState.update { it.copy(isExporting = false, error = error.toUiText(Res.string.composer_save_failed)) }
                return@launch
            }
            runCatching {
                // Before rendering: a key without the upload permission would otherwise only show up after
                // every page was made, as a count of failed uploads.
                targetLibraryId?.let { mediaRepository.checkCanUpload(it) }
                exports.export(project, format, pageIndex, targetLibraryId, videoQuality) { completed, total ->
                    mutableState.update { it.copy(exportCompleted = completed, exportTotal = total) }
                }
            }.onSuccess { result ->
                mutableState.update {
                    it.copy(
                        isExporting = false,
                        exportedUris = result.locations,
                        exportedMimeTypes = result.mimeTypes,
                        notice = if (result.immichUploadFailures == 0) {
                            uiPlural(Res.plurals.composer_files_exported, result.locations.size, result.locations.size)
                        } else {
                            uiPlural(
                                Res.plurals.composer_immich_upload_failures,
                                result.immichUploadFailures,
                                result.immichUploadFailures,
                            )
                        },
                    )
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.update { it.copy(isExporting = false, error = error.toUiText(Res.string.composer_export_failed)) }
            }
        }
    }

    fun cancelExport() {
        state.value.project?.id?.let(exports::cancel)
        exportJob?.cancel()
        mutableState.update { it.copy(isExporting = false, notice = uiText(Res.string.composer_export_cancelled)) }
    }

    fun previewCurrentPage() {
        if (proxyJob?.isActive == true) return
        val project = requireProject()
        val page = state.value.currentPage
        if (project.elements.none { it is CompositionElement.Video && it.visible && it.pageIndex == page }) {
            return notice(uiText(Res.string.composer_page_has_no_video))
        }
        proxyJob = viewModelScope.launch {
            mutableState.update { it.copy(isGeneratingProxy = true) }
            runCatching { exports.previewPage(project, page) }
                .onSuccess { file ->
                    proxyFile?.delete(); proxyFile = file
                    mutableState.update { it.copy(isGeneratingProxy = false, previewProxyUri = file.absolutePath) }
                }.onFailure { error ->
                    mutableState.update { it.copy(isGeneratingProxy = false, error = error.toUiText(Res.string.composer_preview_failed)) }
                }
        }
    }

    fun consumePreview() {
        proxyFile?.delete(); proxyFile = null
        mutableState.update { it.copy(previewProxyUri = null) }
    }

    fun consumeExportedUris() = mutableState.update { it.copy(exportedUris = emptyList(), exportedMimeTypes = emptyList()) }

    fun consumeNotice() = mutableState.update { it.copy(notice = null) }
    fun consumeError() = mutableState.update { it.copy(error = null) }
    fun previewUrl(assetId: String) = mediaRepository.previewUrl(assetId)
    fun thumbnailUrl(assetId: String) = mediaRepository.thumbnailUrl(assetId)
    fun videoPlaybackUrl(assetId: String) = mediaRepository.videoPlaybackUrl(assetId)
    fun apiKey(assetId: String) = mediaRepository.apiKey(assetId)

    private fun transformSelected(block: (ElementTransform) -> ElementTransform) =
        mutateSelected { if (it.locked) it else it.withTransform(block(it.transform)) }

    /** Like [transformSelected], but the block sees the element — it needs it to know how to snap. */
    private fun transformSelectedElement(block: (CompositionElement, ElementTransform) -> ElementTransform) =
        mutateSelected { if (it.locked) it else it.withTransform(block(it, it.transform)) }

    private fun updateSelectedVideoTiming(block: (CompositionElement.Video, VideoTiming) -> VideoTiming) {
        updateVideoTiming(state.value.selectedElementId ?: return, block)
    }

    private fun updateVideoTiming(id: String, block: (CompositionElement.Video, VideoTiming) -> VideoTiming) {
        val project = requireProject()
        val video = project.elements.firstOrNull { it.id == id } as? CompositionElement.Video ?: return
        if (!canEdit(id)) return
        val updated = block(video, video.timing)
        if (updated == video.timing) return
        commit { project.copy(elements = project.elements.map { if (it.id == id) video.copy(timing = updated) else it }) }
    }

    /**
     * Layers move by unit, not by element: a group goes up and down whole.
     *
     * The list comes from bottom to top (increasing zIndex) — the layers sheet shows it inverted.
     */
    private fun CompositionProject.layerUnits(): MutableList<MutableList<CompositionElement>> {
        return elements.sortedWith(compareBy<CompositionElement> { it.zIndex }.thenBy { it.id })
            .selectionUnits().mapTo(mutableListOf()) { it.members.toMutableList() }
    }

    /** Layer units, from top to bottom — the order the layers sheet shows. */
    fun layerUnitKeys(): List<String> = state.value.project?.layerUnits()
        ?.map { it.first().groupId ?: it.first().id }?.asReversed().orEmpty()

    /** Reorders by dragging in the layers sheet. The indices go from top to bottom. */
    fun reorderLayer(fromTopIndex: Int, toTopIndex: Int) {
        val project = requireProject()
        val units = project.layerUnits()
        if (units.size < 2) return
        val from = units.lastIndex - fromTopIndex
        val to = units.lastIndex - toTopIndex
        if (from !in units.indices || to !in units.indices || from == to) return
        val first = minOf(from, to)
        val last = maxOf(from, to)
        val affected = units.subList(first, last + 1).flatten()
        if (affected.any { it.locked }) return
        val slots = affected.map { it.zIndex }.sorted()
        units.add(to, units.removeAt(from))
        val z = units.subList(first, last + 1).flatten().mapIndexed { index, element -> element.id to slots[index] }.toMap()
        commitDiscrete { project.copy(elements = project.elements.map { element -> z[element.id]?.let(element::withZIndex) ?: element }) }
    }

    fun reorderGroupMember(groupId: String, from: Int, to: Int) {
        val project = requireProject()
        val members = project.elements.filter { it.groupId == groupId }.sortedByDescending { it.zIndex }
        if (from !in members.indices || to !in members.indices || from == to) return
        if (members.subList(minOf(from, to), maxOf(from, to) + 1).any { it.locked }) return
        val reordered = members.toMutableList().apply { add(to, removeAt(from)) }
        val z = reordered.mapIndexed { index, element -> element.id to members[index].zIndex }.toMap()
        commitDiscrete { project.copy(elements = project.elements.map { element -> z[element.id]?.let(element::withZIndex) ?: element }) }
    }

    private fun moveSelectedLayer(direction: Int) {
        val project = requireProject()
        val selected = project.elements.firstOrNull { it.id == state.value.selectedElementId } ?: return
        if (selected.groupId != null && selected.groupId == state.value.editingGroupId) {
            val members = project.elements.filter { it.groupId == selected.groupId }.sortedByDescending { it.zIndex }
            val index = members.indexOfFirst { it.id == selected.id }
            reorderGroupMember(selected.groupId!!, index, index - direction)
            return
        }
        val units = project.layerUnits()
        if (!canEdit(selected.id)) return
        val from = units.indexOfFirst { unit -> unit.any { it.id == selected.id } }
        val to = from + direction
        if (from < 0 || to !in units.indices) return
        reorderLayer(units.lastIndex - from, units.lastIndex - to)
    }

    /**
     * Aligns, with two readings depending on what is chosen.
     *
     * One element — or a group, which moves whole — aligns **to the page**: it is the only reference
     * there is when nothing else is chosen.
     *
     * Several elements align **with each other**, to the edge of the rectangle that covers them.
     * Pushing them all to the page's margin would gather them in a pile, and nobody chooses three things
     * to stack them.
     */
    private fun alignSelected(horizontal: Int?, vertical: Int?) {
        val members = state.value.editableSelection().ifEmpty { return }
        val bounds = arrangementBounds(members) ?: return
        applyArrangedSelection(arrangementRepresentatives(members).alignedSelection(horizontal, vertical, bounds, requireProject().format.aspectRatio, state.value.editingGroupId))
    }
    /**
     * Which elements a command acts on.
     *
     * Choosing several is choosing exactly those: no group invites itself to the party. Choosing one
     * brings its group along, because aligning on its own a card that belongs to a group would split
     * the group into two positions without anyone asking.
     */
    private fun CompositionProject.selectionMembers(): List<CompositionElement> = state.value.editableSelection(this)
    private fun mutateSelected(block: (CompositionElement) -> CompositionElement) {
        val project = requireProject()
        val ids = project.selectionMembers().mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        commit { project.copy(elements = project.elements.map { if (it.id in ids) block(it) else it }) }
    }

    /**
     * Safety net: the model's invariants can never kill the app.
     *
     * `CompositionProject`, `NormalizedRect` and `VideoTiming` validate themselves in `init`, and that
     * `init` ran inside gesture callbacks — a video dragged over a separator threw with nobody to catch
     * it. The clamps in [CompositionGeometry] deal with that at the source; this guarantees that a
     * forgotten path gives a message instead of a crash.
     */
    private fun commit(build: () -> CompositionProject) {
        // `build` runs **in here** on purpose. `CompositionProject.copy()` runs `init` again, and that is
        // where the invariants throw — if the project were built at the call site, the exception would
        // go up through the gesture callback before this method even started.
        val stamped = runCatching { build().reconcileLayouts(requireProject()).let { it.copy(revision = it.revision + 1, updatedAt = Instant.now().toString()) } }
            .getOrElse { error ->
                notice(error.toUiText(Res.string.composer_change_failed))
                return
            }
        if (historyIndex < history.lastIndex) history.subList(historyIndex + 1, history.size).clear()
        if (gestureActive && gestureCommitted && historyIndex in history.indices) {
            // A continuous gesture is a single edit: it replaces the top instead of stacking per frame.
            history[historyIndex] = stamped
        } else {
            history += stamped
            historyIndex++
            // When trimming the history the index has to move back with it. Without this it was one
            // position ahead of what was there, and the first "undo" after the hundredth edit jumped.
            if (history.size > 100) { history.removeAt(0); historyIndex-- }
            if (gestureActive) gestureCommitted = true
        }
        restore(stamped)
        scheduleSave(stamped)
    }

    private fun restore(project: CompositionProject) = mutableState.update { previous ->
        val editing = previous.editingGroupId?.takeIf { group -> project.elements.any { it.groupId == group } }
        val next = previous.copy(project = project, editingGroupId = editing)
        val ids = previous.selectedElementIds.filterTo(mutableSetOf()) { id -> next.canEditElement(id) }
        next.copy(selectedElementIds = ids, selectedElementId = previous.selectedElementId?.takeIf { it in ids } ?: ids.firstOrNull(),
            multiSelect = previous.multiSelect && ids.isNotEmpty(), groupMergeRequest = null,
            canUndo = historyIndex > 0, canRedo = historyIndex < history.lastIndex)
    }

    private fun commitDiscrete(build: () -> CompositionProject) { endGesture(); commit(build) }

    private fun scheduleSave(project: CompositionProject) {
        saveJob?.cancel()
        if (project == persisted) {
            mutableState.update { it.copy(isSaving = false, saveFailed = false) }
            return
        }
        mutableState.update { it.copy(isSaving = true, saveFailed = false) }
        saveJob = viewModelScope.launch {
            delay(600)
            runCatching { persist(project) }
                .onSuccess { mutableState.update { it.copy(isSaving = false) } }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    mutableState.update { it.copy(isSaving = false, saveFailed = true, error = error.toUiText(Res.string.composer_save_failed)) }
                }
        }
    }

    private suspend fun persist(project: CompositionProject) = saveMutex.withLock {
        // Export, exit and autosave can overlap. An older queued snapshot must not overwrite a newer save.
        val previous = persisted
        if (project == previous || previous?.id == project.id && previous.revision > project.revision) return@withLock
        projects.save(project)
        persisted = project
    }

    private fun notice(message: UiText) { mutableState.update { it.copy(notice = message) } }
    private fun requireProject() = checkNotNull(state.value.project)
    private fun nextZ(project: CompositionProject) = (project.elements.maxOfOrNull(CompositionElement::zIndex) ?: -1) + 1

    override fun onCleared() {
        proxyFile?.delete()
        super.onCleared()
    }
}
