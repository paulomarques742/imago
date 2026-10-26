package eu.studio742.imago.feature.account

import androidx.compose.runtime.Composable
import eu.studio742.imago.core.sync.CommunityRepository

@Composable
fun communityRepository(): CommunityRepository? = accountViewModel().community
