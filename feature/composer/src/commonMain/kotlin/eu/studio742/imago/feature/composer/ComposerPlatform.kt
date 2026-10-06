package eu.studio742.imago.feature.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.studio742.imago.core.composition.CompositionElement

@Composable
expect fun composerEditorViewModel(): ComposerEditorViewModel

@Composable
expect fun composerHubViewModel(): ComposerHubViewModel

@Composable
expect fun addToCompositionViewModel(): AddToCompositionViewModel

@Composable
expect fun brandKitViewModel(): BrandKitViewModel

/** What to do with the exported pages: the share menu on the phone, the folder on the computer. */
@Composable
expect fun rememberShareExports(): (locations: List<String>, mimeTypes: List<String>) -> Unit

/** The video of a stage element, with the trim and volume the element asks for. */
@Composable
expect fun ElementVideoPlayer(element: CompositionElement.Video, url: String, apiKey: String, modifier: Modifier = Modifier)

/** The preview of an already rendered page, in a local file. */
@Composable
expect fun ProxyVideoPlayer(path: String, modifier: Modifier = Modifier)
