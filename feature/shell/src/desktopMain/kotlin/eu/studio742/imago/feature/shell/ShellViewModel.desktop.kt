package eu.studio742.imago.feature.shell

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.studio742.imago.core.data.LocalDesktopDataGraph

@Composable
actual fun shellViewModel(): ShellViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { ShellViewModel(graph.configuration) }
}
