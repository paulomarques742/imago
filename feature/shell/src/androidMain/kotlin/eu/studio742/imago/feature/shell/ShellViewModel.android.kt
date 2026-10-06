package eu.studio742.imago.feature.shell

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.studio742.imago.core.data.ConfigurationRepository
import javax.inject.Inject

@HiltViewModel
class HiltShellViewModel @Inject constructor(configuration: ConfigurationRepository) : ShellViewModel(configuration)

@Composable
actual fun shellViewModel(): ShellViewModel = hiltViewModel<HiltShellViewModel>()
