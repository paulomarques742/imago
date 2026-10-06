package eu.studio742.imago.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.studio742.imago.core.sync.AccountRepository
import eu.studio742.imago.core.sync.SyncEngine

/** The desktop account, provided by the app at the top of the window. */
val LocalAccountRepository = staticCompositionLocalOf<AccountRepository> {
    error("The desktop app has to provide LocalAccountRepository.")
}

/** The desktop sync engine; null leaves the account without a sync state. */
val LocalSyncEngine = staticCompositionLocalOf<SyncEngine?> { null }

@Composable
actual fun accountViewModel(): AccountViewModel {
    val account = LocalAccountRepository.current
    val sync = LocalSyncEngine.current
    return viewModel { AccountViewModel(account, sync) }
}
