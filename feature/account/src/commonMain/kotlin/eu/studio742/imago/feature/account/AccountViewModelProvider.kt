package eu.studio742.imago.feature.account

import androidx.compose.runtime.Composable

/** The account ViewModel on the current screen: through Hilt on Android, through the app's dependencies on desktop. */
@Composable
expect fun accountViewModel(): AccountViewModel
