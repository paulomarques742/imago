package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.feature.composer.resources.*
import eu.studio742.imago.core.composition.BuiltInLayout
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.composition.SlotKind
import eu.studio742.imago.core.composition.slots
import java.time.Instant
import java.util.UUID

/**
 * An empty composition with the slots the layout asks for.
 *
 * It lives outside the `ViewModel`s because there are two places creating it — the hub, and the
 * library when it sends photos to a composition that does not exist yet — and a second copy of this
 * function would be a second definition of what a new project is.
 *
 * The name and the sample text are in the app language at the time of creation: they are the
 * project's content, not interface, and do not change language afterwards.
 */
internal suspend fun newCompositionProject(
    format: PageFormat,
    layout: BuiltInLayout,
    name: String? = null,
    now: String = Instant.now().toString(),
    newId: () -> String = { UUID.randomUUID().toString() },
): CompositionProject = newCompositionProject(
    format = format,
    layout = layout,
    name = name ?: appString(Res.string.composer_new_composition),
    placeholderText = appString(Res.string.composer_text_placeholder),
    now = now,
    newId = newId,
)

internal fun newCompositionProject(
    format: PageFormat,
    layout: BuiltInLayout,
    name: String,
    placeholderText: String,
    now: String,
    newId: () -> String,
): CompositionProject = CompositionProject(
    id = newId(),
    name = name,
    format = format,
    pages = List(layout.minimumPages) { index -> CompositionPage(newId(), index) },
    elements = layout.slots().mapIndexed { index, slot ->
        when (slot.kind) {
            SlotKind.MEDIA -> CompositionElement.MediaPlaceholder(
                id = newId(),
                transform = ElementTransform(slot.bounds),
                zIndex = index,
            )
            SlotKind.TEXT -> CompositionElement.Text(
                id = newId(),
                transform = ElementTransform(slot.bounds),
                zIndex = index,
                text = placeholderText,
            )
        }
    },
    createdAt = now,
    updatedAt = now,
)
