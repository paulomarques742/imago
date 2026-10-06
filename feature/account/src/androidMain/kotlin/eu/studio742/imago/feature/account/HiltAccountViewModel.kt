package eu.studio742.imago.feature.account

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.studio742.imago.core.sync.AccountRepository
import eu.studio742.imago.core.sync.SyncEngine
import javax.inject.Inject

/** The shared [AccountViewModel], with Hilt injection. */
@HiltViewModel
class HiltAccountViewModel @Inject constructor(account: AccountRepository, sync: SyncEngine) : AccountViewModel(account, sync)

@Composable
actual fun accountViewModel(): AccountViewModel = hiltViewModel<HiltAccountViewModel>()
