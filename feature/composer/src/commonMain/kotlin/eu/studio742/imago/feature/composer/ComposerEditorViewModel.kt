package eu.studio742.imago.feature.composer

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
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.CompositionTemplate
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.FrameStyle
import eu.studio742.imago.core.composition.MAX_COMPOSITION_PAGES
import eu.studio742.imago.core.composition.MAX_PAGE_DURATION_MS
import eu.studio742.imago.core.composition.MAX_VIDEOS_PER_PAGE
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.boundingBox
import eu.studio742.imago.core.composition.mappedBetween
import eu.studio742.imago.core.composition.orbited
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.PhotoRecipeSnapshot
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
import eu.studio742.imago.core.composition.clampedBounds
import eu.studio742.imago.core.composition.normalizeDegrees
import eu.studio742.imago.core.composition.resizedFrom
import eu.studio742.imago.core.composition.scaledAboutCentre
import eu.studio742.imago.core.composition.snapCoordinate
import eu.studio742.imago.core.composition.snapRotation
import eu.studio742.imago.core.composition.VideoTiming
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
const val MAX_FRAME_BORDER = 24f
const val MAX_FRAME_SHADOW = 48f
const val MIN_TEXT_SIZE = 8f
const val MAX_TEXT_SIZE = 240f
const val DEFAULT_TEXT_SIZE = 48f
const val MIN_SHAPE_STROKE = .5f
const val MAX_SHAPE_STROKE = 40f
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
    /** The background is a layer like the others: either it or an element, never both. */
    val backgroundSelected: Boolean = false,
    /** True while an element is being moved, rotated or resized on the stage. */
    val stageGesture: Boolean = false,
    val currentPage: Int = 0,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
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
    private var exportJob: Job? = null
    private var proxyJob: Job? = null
    private var proxyFile: java.io.File? = null
    private var gestureActive = false
    private var gestureCommitted = false
    /** The absolute angle the handle has already asked of the block, and the selection it belongs to. */
    private var blockRotation = 0f
    private var blockRotationOf: Set<String> = emptySet()

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

    fun selectElement(id: String?) = mutableState.update {
        it.copy(selectedElementId = id, selectedElementIds = setOfNotNull(id), backgroundSelected = false)
    }

    /**
     * Adds this element to the selection or takes it out.
     *
     * Taking out the primary leaves the selection headless: whichever of the remaining ones takes
     * over, because the inspector always needs a concrete element to read the type from.
     */
    fun toggleElementInSelection(id: String) = mutableState.update { state ->
        val next = if (id in state.selectedElementIds) state.selectedElementIds - id else state.selectedElementIds + id
        state.copy(
            selectedElementIds = next,
            selectedElementId = if (id in next) id else next.firstOrNull(),
            backgroundSelected = false,
        )
    }

    fun selectBackground() = mutableState.update {
        it.copy(selectedElementId = null, selectedElementIds = emptySet(), backgroundSelected = true)
    }

    fun clearSelection() = mutableState.update {
        it.copy(selectedElementId = null, selectedElementIds = emptySet(), backgroundSelected = false)
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
        if (stage) mutableState.update { it.copy(stageGesture = false) }
    }

    fun addPage() {
        val project = requireProject()
        if (project.pages.size >= MAX_COMPOSITION_PAGES) return notice(uiText(Res.string.composer_max_pages, MAX_COMPOSITION_PAGES))
        val insertAt = state.value.currentPage + 1
        if (project.elements.any { insertAt in it.crossedBoundaries() }) {
            return notice(uiText(Res.string.composer_insert_page_blocked))
        }
        val pages = project.pages.toMutableList().apply {
            add(insertAt, CompositionPage(UUID.randomUUID().toString(), insertAt))
        }.mapIndexed { index, page -> page.copy(index = index) }
        val elements = project.elements.map { element ->
            val shifted = if (element.transform.bounds.x >= insertAt) {
                element.withTransform(element.transform.copy(bounds = element.transform.bounds.copy(x = element.transform.bounds.x + 1f)))
            } else element
            val withPage = if (shifted is CompositionElement.Video && shifted.pageIndex >= insertAt) {
                shifted.copy(pageIndex = shifted.pageIndex + 1)
            } else shifted
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
            val bounds = element.transform.bounds
            when {
                bounds.x >= index + 1f -> element.withTransform(element.transform.copy(bounds = bounds.copy(x = bounds.x - 1f)))
                bounds.x >= index.toFloat() && bounds.right <= index + 1f -> null
                else -> element
            }
        }.map { element ->
            val withPage = if (element is CompositionElement.Video && element.pageIndex > index) {
                element.copy(pageIndex = element.pageIndex - 1)
            } else element
            withPage.withRepeatOnPages(withPage.repeatOnPages.mapNotNullTo(mutableSetOf()) { page ->
                when { page == index -> null; page > index -> page - 1; else -> page }
            })
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
        fun remapPage(value: Float): Float {
            val page = value.toInt()
            val local = value - page
            val mapped = when (page) { from -> to; to -> from; else -> page }
            return mapped + local
        }
        val elements = project.elements.map { element ->
            val b = element.transform.bounds
            val moved = if (b.x.toInt() == from || b.x.toInt() == to) {
                element.withTransform(element.transform.copy(bounds = b.copy(x = remapPage(b.x))))
            } else element
            val withPage = if (moved is CompositionElement.Video) {
                moved.copy(pageIndex = when (moved.pageIndex) { from -> to; to -> from; else -> moved.pageIndex })
            } else moved
            withPage.withRepeatOnPages(withPage.repeatOnPages.mapTo(mutableSetOf()) {
                when (it) { from -> to; to -> from; else -> it }
            })
        }
        commit { project.copy(pages = pages, elements = elements) }; selectPage(to)
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

    fun addShape(kind: ShapeKind) {
        val project = requireProject()
        val id = UUID.randomUUID().toString()
        val page = state.value.currentPage.toFloat()
        commit { project.copy(elements = project.elements + CompositionElement.Shape(
            id, ElementTransform(NormalizedRect(page + .25f, .3f, .5f, .35f)), nextZ(project), kind,
        )) }
        selectElement(id)
    }

    fun addDrawing(points: List<StrokePoint>, kind: StrokeKind = StrokeKind.PEN) {
        if (points.size < 2) return
        val project = requireProject()
        val minX = points.minOf { it.x }.coerceIn(0f, project.pages.size.toFloat())
        val maxX = points.maxOf { it.x }.coerceIn(minX, project.pages.size.toFloat())
        val minY = points.minOf { it.y }.coerceIn(0f, 1f)
        val maxY = points.maxOf { it.y }.coerceIn(minY, 1f)
        val width = (maxX - minX).coerceAtLeast(.001f)
        val height = (maxY - minY).coerceAtLeast(.001f)
        val localPoints = points.map { point ->
            StrokePoint(
                x = ((point.x - minX) / width).coerceIn(0f, 1f),
                y = ((point.y - minY) / height).coerceIn(0f, 1f),
            )
        }
        val id = UUID.randomUUID().toString()
        commit { project.copy(elements = project.elements + CompositionElement.Drawing(
            id = id,
            transform = ElementTransform(NormalizedRect(minX, minY, width, height)),
            zIndex = nextZ(project),
            kind = kind,
            points = localPoints,
            colorArgb = 0xFFFFFFFF,
            strokeWidth = if (kind == StrokeKind.MARKER) 16f else 6f,
        )) }
        selectElement(id)
    }

    fun eraseDrawing(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val project = requireProject()
        val hitRadiusSquared = .03f * .03f
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
        val previousBlur = (currentBackground() as? CompositionBackground.Photo)?.blurRadius ?: 0f
        applyBackground(CompositionBackground.Photo(media.toReference(), blurRadius = previousBlur), currentPageOnly)
        selectBackground()
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
        val background = CompositionBackground.Photo(photo.media, photo.crop, blurRadius = if (blurred) 24f else 0f)
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
        if (media.isEmpty()) return@launch
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
            )
        }
    }

    fun moveSelected(dx: Float, dy: Float) {
        val block = resizableBlock() ?: return moveSingle(dx, dy)
        val project = requireProject()
        val ids = project.selectionMembers().filterNot(CompositionElement::locked).mapTo(mutableSetOf()) { it.id }
        // Snapping belongs to the set and is measured by the outer edges. Done element by element, each
        // one found its own guide — often the edge of the chosen neighbour — and the block deformed
        // mid-drag instead of moving whole.
        val outsiders = project.elements.filterNot { it.id in ids }
        val pageCandidates = project.pages.indices.flatMap { page ->
            listOf(page.toFloat(), page + .5f - block.width / 2f, page + 1f - block.width)
        }
        val x = snapCoordinate(
            block.x + dx,
            pageCandidates + outsiders.flatMap { listOf(it.transform.bounds.x, it.transform.bounds.right) },
        )
        val y = snapCoordinate(
            block.y + dy,
            listOf(0f, .5f - block.height / 2f, 1f - block.height) +
                outsiders.flatMap { listOf(it.transform.bounds.y, it.transform.bounds.bottom) },
        )
        // The step is clamped once, against the block's limits. Leaving each element's `clampedBounds`
        // to sort itself out pushed against the margin only what got there first.
        val stepX = (x - block.x).coerceIn(-block.x, project.pages.size - block.right)
        val stepY = (y - block.y).coerceIn(-block.y, 1f - block.bottom)
        if (stepX == 0f && stepY == 0f) return
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id !in ids) return@map element
            val b = element.transform.bounds
            val moved = element.clampedBounds(b.copy(x = b.x + stepX, y = b.y + stepY), project.pages.size)
            element.withTransform(element.transform.copy(bounds = moved))
        }) }
    }

    private fun moveSingle(dx: Float, dy: Float) = transformSelectedElement { element, transform ->
        val project = requireProject()
        val b = transform.bounds
        val pageCandidates = project.pages.indices.flatMap { page ->
            listOf(page.toFloat(), page + .5f - b.width / 2f, page + 1f - b.width)
        }
        val otherEdges = project.elements.filterNot { it.id == element.id }
            .flatMap { listOf(it.transform.bounds.x, it.transform.bounds.right) }
        // Snapping runs **before** the final clamp. The other way round, `snapCoordinate` could throw the
        // value outside the range the clamp had just guaranteed — that is how the rectangle left the
        // canvas and the model's invariant blew up further on.
        val x = snapCoordinate(b.x + dx, pageCandidates + otherEdges)
        val y = snapCoordinate(b.y + dy, listOf(0f, .5f - b.height / 2f, 1f - b.height) + project.elements.flatMap {
            if (it.id == element.id) emptyList() else listOf(it.transform.bounds.y, it.transform.bounds.bottom)
        })
        transform.copy(bounds = element.clampedBounds(b.copy(x = x, y = y), project.pages.size))
    }

    /**
     * Resizes from a corner. [dx] and [dy] already come in the element's frame of reference — the
     * counter-rotation happens in the overlay, which is what knows the stage's pixels.
     */
    fun resizeSelectedFromCorner(corner: ResizeCorner, dx: Float, dy: Float) {
        val block = resizableBlock() ?: return transformSelectedElement { element, transform ->
            val resized = transform.bounds.resizedFrom(corner, dx, dy)
            transform.copy(bounds = element.clampedBounds(resized, requireProject().pages.size))
        }
        reshapeSelection(block, block.resizedFrom(corner, dx, dy))
    }

    /** Scales around the centre, for the two-finger pinch. */
    fun scaleSelected(factor: Float) {
        val block = resizableBlock() ?: return transformSelectedElement { element, transform ->
            val scaled = transform.bounds.scaledAboutCentre(factor)
            transform.copy(bounds = element.clampedBounds(scaled, requireProject().pages.size))
        }
        reshapeSelection(block, block.scaledAboutCentre(factor))
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
     * `clampedBounds` still runs per element. It is what keeps a video from leaving the page it belongs
     * to, and that rule is each one's, not the block's.
     */
    private fun reshapeSelection(box: NormalizedRect, target: NormalizedRect) {
        val project = requireProject()
        val ids = project.selectionMembers()
            .filterNot(CompositionElement::locked)
            .mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id !in ids) return@map element
            val next = element.transform.bounds.mappedBetween(box, target)
            element.withTransform(element.transform.copy(bounds = element.clampedBounds(next, project.pages.size)))
        }) }
    }

    /** Absolute rotation, for the handle and the slider. [snap] only when it comes from a gesture. */
    fun setSelectedRotation(degrees: Float, snap: Boolean = false) {
        val target = if (snap) snapRotation(degrees) else normalizeDegrees(degrees)
        if (resizableBlock() == null) {
            return transformSelected { it.copy(rotationDegrees = target) }
        }
        // The block keeps no angle in the model — it is a computed rectangle, not an element. What is
        // kept here is only what the handle has already asked for, to turn an absolute angle into the
        // difference left to apply.
        val selection = state.value.selectedElementIds
        if (selection != blockRotationOf) {
            blockRotationOf = selection
            blockRotation = 0f
        }
        val delta = target - blockRotation
        blockRotation = target
        rotateSelectionBlock(delta)
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
        val centreX = block.x + block.width / 2f
        val centreY = block.y + block.height / 2f
        val aspect = project.format.pixelWidth.toFloat() / project.format.pixelHeight
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id !in ids) return@map element
            val next = element.transform.bounds.orbited(centreX, centreY, deltaDegrees, aspect)
            element.withTransform(
                element.transform.copy(
                    bounds = element.clampedBounds(next, project.pages.size),
                    rotationDegrees = normalizeDegrees(element.transform.rotationDegrees + deltaDegrees),
                ),
            )
        }) }
    }

    fun toggleMirrorHorizontal() = transformSelected { it.copy(mirrorHorizontal = !it.mirrorHorizontal) }
    fun toggleMirrorVertical() = transformSelected { it.copy(mirrorVertical = !it.mirrorVertical) }
    fun setSelectedOpacity(value: Float) = transformSelected { it.copy(opacity = value.coerceIn(0.05f, 1f)) }

    fun setFrameCorner(value: Float) = mutateFrame { it.copy(cornerRadius = value.coerceIn(0f, MAX_FRAME_CORNER)) }
    fun setFrameBorder(value: Float) = mutateFrame { it.copy(borderWidth = value.coerceIn(0f, MAX_FRAME_BORDER)) }
    fun setFrameShadow(value: Float) = mutateFrame { it.copy(shadowRadius = value.coerceIn(0f, MAX_FRAME_SHADOW)) }

    /**
     * The frame outline's colour. It always existed in the model and on export — `borderArgb` — but
     * there was no way to change it: the inspector had the thickness and nothing else, so the outline
     * was white for everyone.
     */
    fun setFrameBorderColor(argb: Long) = mutateFrame { it.copy(borderArgb = argb) }

    fun setShapeFill(argb: Long) = mutateSelected { element ->
        if (element is CompositionElement.Shape) element.copy(fillArgb = argb) else element
    }

    fun setShapeStroke(argb: Long) = mutateSelected { element ->
        if (element is CompositionElement.Shape) element.copy(strokeArgb = argb) else element
    }

    fun setShapeStrokeWidth(value: Float) = mutateSelected { element ->
        if (element is CompositionElement.Shape) {
            element.copy(strokeWidth = value.coerceIn(MIN_SHAPE_STROKE, MAX_SHAPE_STROKE))
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
    fun distributeSelected(horizontal: Boolean) {
        val project = requireProject()
        val members = project.selectionMembers()
        if (members.size < 3) return notice(uiText(Res.string.composer_distribute_needs_three))
        val sorted = if (horizontal) members.sortedBy { it.transform.bounds.x } else members.sortedBy { it.transform.bounds.y }
        val first = sorted.first().transform.bounds
        val last = sorted.last().transform.bounds
        val totalSize = sorted.sumOf { if (horizontal) it.transform.bounds.width.toDouble() else it.transform.bounds.height.toDouble() }.toFloat()
        val span = if (horizontal) last.right - first.x else last.bottom - first.y
        val gap = (span - totalSize) / (sorted.size - 1)
        var cursor = if (horizontal) first.x else first.y
        val replacements = mutableMapOf<String, CompositionElement>()
        sorted.forEach { element ->
            val b = element.transform.bounds
            val next = if (horizontal) b.copy(x = cursor) else b.copy(y = cursor)
            replacements[element.id] = element.withTransform(
                element.transform.copy(bounds = element.clampedBounds(next, project.pages.size)),
            )
            cursor += (if (horizontal) b.width else b.height) + gap
        }
        commit { project.copy(elements = project.elements.map { replacements[it.id] ?: it }) }
    }

    fun adjustVideoTrimStart(deltaMs: Long) = updateSelectedVideoTiming { _, timing ->
        // The top of the range has to stay above the bottom even in a short clip, or `coerceIn` gets the
        // minimum above the maximum and throws.
        timing.copy(trimStartMs = (timing.trimStartMs + deltaMs).coerceIn(0, (timing.trimEndMs - 100).coerceAtLeast(0)))
    }

    fun adjustVideoTrimEnd(deltaMs: Long) = updateSelectedVideoTiming { video, timing ->
        val duration = minOf(video.media.durationMs ?: MAX_PAGE_DURATION_MS, MAX_PAGE_DURATION_MS)
        val floor = timing.trimStartMs + 100
        timing.copy(trimEndMs = (timing.trimEndMs + deltaMs).coerceIn(floor, duration.coerceAtLeast(floor)))
    }

    fun adjustVideoOffset(deltaMs: Long) = updateSelectedVideoTiming { _, timing ->
        timing.copy(startOffsetMs = (timing.startOffsetMs + deltaMs).coerceIn(0, MAX_PAGE_DURATION_MS))
    }

    fun adjustVideoVolume(delta: Float) = updateSelectedVideoTiming { _, timing ->
        timing.copy(volume = (timing.volume + delta).coerceIn(0f, 1f))
    }

    fun toggleVideoMute() = updateSelectedVideoTiming { _, timing -> timing.copy(muted = !timing.muted) }
    fun toggleVideoSolo() = updateSelectedVideoTiming { _, timing -> timing.copy(solo = !timing.solo) }
    fun toggleVideoLoop() = updateSelectedVideoTiming { _, timing -> timing.copy(loop = !timing.loop) }

    fun adjustPageDuration(deltaMs: Long) {
        val project = requireProject()
        val pageIndex = state.value.currentPage
        val page = project.pages[pageIndex]
        val duration = (page.durationMs + deltaMs).coerceIn(1_000, MAX_PAGE_DURATION_MS)
        commit { project.copy(pages = project.pages.map { if (it.index == pageIndex) it.copy(durationMs = duration) else it }) }
    }

    fun toggleLocked() = mutateSelected { it.withLocked(!it.locked) }
    fun toggleVisible() = mutateSelected { it.withVisible(!it.visible) }
    fun toggleElementLocked(id: String) { selectElement(id); toggleLocked() }
    fun toggleElementVisible(id: String) { selectElement(id); toggleVisible() }
    fun moveElement(id: String, dx: Float, dy: Float) { selectElement(id); moveSelected(dx, dy) }
    fun bringElementForward(id: String) { selectElement(id); bringForward() }
    fun sendElementBackward(id: String) { selectElement(id); sendBackward() }
    fun deleteElement(id: String) { selectElement(id); deleteSelected() }

    fun groupSelectedWith(otherId: String) {
        val selectedId = state.value.selectedElementId ?: return
        if (selectedId == otherId) return
        val project = requireProject()
        val selected = project.elements.firstOrNull { it.id == selectedId } ?: return
        val other = project.elements.firstOrNull { it.id == otherId } ?: return
        val group = selected.groupId ?: other.groupId ?: UUID.randomUUID().toString()
        val members = setOfNotNull(selected.groupId, other.groupId)
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id == selectedId || element.id == otherId || element.groupId in members) element.withGroupId(group) else element
        }) }
    }

    /**
     * Groups what is chosen, without going through the "with which one?" dialog.
     *
     * That dialog exists for when there is only one element in hand and one has to say who it joins.
     * With several chosen the question is already answered.
     */
    fun groupSelection() {
        val project = requireProject()
        val members = project.selectionMembers()
        if (members.size < 2) return notice(uiText(Res.string.composer_group_needs_two))
        val group = members.firstNotNullOfOrNull { it.groupId } ?: UUID.randomUUID().toString()
        val existing = members.mapNotNullTo(mutableSetOf()) { it.groupId }
        val ids = members.mapTo(mutableSetOf()) { it.id }
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id in ids || element.groupId in existing) element.withGroupId(group) else element
        }) }
    }

    fun ungroupSelected() {
        val project = requireProject()
        val selected = project.elements.firstOrNull { it.id == state.value.selectedElementId } ?: return
        val group = selected.groupId ?: return
        commit { project.copy(elements = project.elements.map { if (it.groupId == group) it.withGroupId(null) else it }) }
    }
    fun repeatOnAllPages() = mutateSelected { element ->
        if (element is CompositionElement.Video) element else element.withRepeatOnPages(requireProject().pages.indices.toSet())
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
        val group = if (members.size > 1) UUID.randomUUID().toString() else null
        var z = nextZ(project)
        val copies = members.map { element ->
            val bounds = element.transform.bounds
            val shifted = element.clampedBounds(
                bounds.copy(x = bounds.x + DUPLICATE_OFFSET, y = bounds.y + DUPLICATE_OFFSET),
                project.pages.size,
            )
            element
                .withId(UUID.randomUUID().toString())
                .withTransform(element.transform.copy(bounds = shifted))
                .withGroupId(group)
                .withZIndex(z++)
        }
        commit { project.copy(elements = project.elements + copies) }
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
        commit { project.copy(elements = project.elements.filterNot { it.id in ids }) }
        clearSelection()
    }

    fun undo() {
        if (historyIndex <= 0) return
        historyIndex--
        restore(history[historyIndex])
    }

    fun redo() {
        if (historyIndex >= history.lastIndex) return
        historyIndex++
        restore(history[historyIndex])
    }

    fun saveNow() {
        saveJob?.cancel()
        val project = state.value.project ?: return
        // Without changes nothing is saved: the in-memory copy may already be older than the repository's.
        if (project == persisted) return mutableState.update { it.copy(isSaving = false) }
        viewModelScope.launch { persist(project); mutableState.update { it.copy(isSaving = false) } }
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

    fun export(format: StaticExportFormat, currentPageOnly: Boolean, targetLibraryId: String? = null) {
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
                exports.export(project, format, pageIndex, targetLibraryId) { completed, total ->
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
        val project = requireProject()
        val id = state.value.selectedElementId ?: return
        val video = project.elements.firstOrNull { it.id == id } as? CompositionElement.Video ?: return
        val updated = block(video, video.timing)
        val clipEnd = updated.startOffsetMs + (updated.trimEndMs - updated.trimStartMs)
        val pages = project.pages.map { page ->
            if (page.index == video.pageIndex && clipEnd > page.durationMs) page.copy(durationMs = clipEnd.coerceAtMost(MAX_PAGE_DURATION_MS)) else page
        }
        commit { project.copy(
            pages = pages,
            elements = project.elements.map { if (it.id == id) video.copy(timing = updated) else it },
        ) }
    }

    /**
     * Layers move by unit, not by element: a group goes up and down whole.
     *
     * The list comes from bottom to top (increasing zIndex) — the layers sheet shows it inverted.
     */
    private fun CompositionProject.layerUnits(): MutableList<MutableList<CompositionElement>> {
        val units = mutableListOf<MutableList<CompositionElement>>()
        elements.sortedWith(compareBy<CompositionElement> { it.zIndex }.thenBy { it.id }).forEach { element ->
            val key = element.groupId ?: element.id
            val existing = units.firstOrNull { (it.first().groupId ?: it.first().id) == key }
            if (existing == null) units += mutableListOf(element) else existing += element
        }
        return units
    }

    private fun CompositionProject.withDenseZIndexes(units: List<List<CompositionElement>>): CompositionProject {
        val zById = units.flatten().mapIndexed { index, element -> element.id to index }.toMap()
        return copy(elements = elements.map { element -> zById[element.id]?.let(element::withZIndex) ?: element })
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
        units.add(to, units.removeAt(from))
        commit { project.withDenseZIndexes(units) }
    }

    private fun moveSelectedLayer(direction: Int) {
        val project = requireProject()
        val selected = project.elements.firstOrNull { it.id == state.value.selectedElementId } ?: return
        val units = project.layerUnits()
        val key = selected.groupId ?: selected.id
        val from = units.indexOfFirst { (it.first().groupId ?: it.first().id) == key }
        val to = from + direction
        if (from < 0 || to !in units.indices) return
        java.util.Collections.swap(units, from, to)
        commit { project.withDenseZIndexes(units) }
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
        val project = requireProject()
        val members = project.selectionMembers().ifEmpty { return }
        val left = members.minOf { it.transform.bounds.x }
        val right = members.maxOf { it.transform.bounds.right }
        val top = members.minOf { it.transform.bounds.y }
        val bottom = members.maxOf { it.transform.bounds.bottom }
        val toEachOther = state.value.selectedElementIds.size > 1
        val page = floor(left).toInt().coerceIn(project.pages.indices)
        val targetLeft = if (toEachOther) left else page.toFloat()
        val targetRight = if (toEachOther) right else page + 1f
        val targetTop = if (toEachOther) top else 0f
        val targetBottom = if (toEachOther) bottom else 1f

        fun offsets(element: CompositionElement): Pair<Float, Float> {
            val b = element.transform.bounds
            // Aligned with each other, each element moves what it needs; aligned to the page, the whole
            // set moves by the same amount and keeps its internal distances.
            val fromLeft = if (toEachOther) b.x else left
            val fromRight = if (toEachOther) b.right else right
            val fromTop = if (toEachOther) b.y else top
            val fromBottom = if (toEachOther) b.bottom else bottom
            val dx = when (horizontal) {
                -1 -> targetLeft - fromLeft
                0 -> (targetLeft + targetRight) / 2f - (fromLeft + fromRight) / 2f
                1 -> targetRight - fromRight
                else -> 0f
            }
            val dy = when (vertical) {
                -1 -> targetTop - fromTop
                0 -> (targetTop + targetBottom) / 2f - (fromTop + fromBottom) / 2f
                1 -> targetBottom - fromBottom
                else -> 0f
            }
            return dx to dy
        }

        val ids = members.mapTo(mutableSetOf()) { it.id }
        commit { project.copy(elements = project.elements.map { element ->
            if (element.id in ids) {
                val (dx, dy) = offsets(element)
                val b = element.transform.bounds
                val moved = element.clampedBounds(b.copy(x = b.x + dx, y = b.y + dy), project.pages.size)
                element.withTransform(element.transform.copy(bounds = moved))
            } else element
        }) }
    }

    /**
     * Which elements a command acts on.
     *
     * Choosing several is choosing exactly those: no group invites itself to the party. Choosing one
     * brings its group along, because aligning on its own a card that belongs to a group would split
     * the group into two positions without anyone asking.
     */
    private fun CompositionProject.selectionMembers(): List<CompositionElement> {
        val ids = state.value.selectedElementIds
        if (ids.size > 1) return elements.filter { it.id in ids }
        val primary = elements.firstOrNull { it.id == state.value.selectedElementId } ?: return emptyList()
        return if (primary.groupId == null) listOf(primary) else elements.filter { it.groupId == primary.groupId }
    }

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
        val stamped = runCatching { build().let { it.copy(revision = it.revision + 1, updatedAt = Instant.now().toString()) } }
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

    private fun restore(project: CompositionProject) = mutableState.update {
        it.copy(project = project, canUndo = historyIndex > 0, canRedo = historyIndex < history.lastIndex)
    }

    private fun scheduleSave(project: CompositionProject) {
        saveJob?.cancel()
        mutableState.update { it.copy(isSaving = true) }
        saveJob = viewModelScope.launch {
            delay(600)
            runCatching { persist(project) }
                .onSuccess { mutableState.update { it.copy(isSaving = false) } }
                .onFailure { error -> mutableState.update { it.copy(isSaving = false, error = error.toUiText(Res.string.composer_save_failed)) } }
        }
    }

    private suspend fun persist(project: CompositionProject) {
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
