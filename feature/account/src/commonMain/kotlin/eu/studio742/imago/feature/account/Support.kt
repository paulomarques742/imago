package eu.studio742.imago.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.studio742.imago.core.designsystem.CollapsibleSettingsCard
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.sync.PlayPurchaseResult
import eu.studio742.imago.core.sync.SupporterState
import eu.studio742.imago.core.sync.Supporters
import eu.studio742.imago.feature.account.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Support bought through the store the app was installed from. Only a copy installed by Google Play has
 * one: the Play payments policy wants its billing for anything paid inside that copy, and it never
 * links to another way of paying. Every other copy shows the programme's page instead.
 */
interface SupportStore {
    /** The monthly subscription's price as the store shows it; null until known, or when it is not sold here. */
    val price: StateFlow<String?>

    /** Tokens of purchases the store just completed, to send to the server. */
    val purchases: Flow<String>

    /** Opens the store's purchase sheet; the purchase carries [accountHash] as its account. */
    fun buy(accountHash: String)

    /** Purchases the store already knows of, sent again whenever the card opens: a lost answer costs nothing. */
    suspend fun owned(): List<String>
}

/** Provided by the Android app when Google Play installed it; absent elsewhere. */
val LocalSupportStore = staticCompositionLocalOf<SupportStore?> { null }

/** The day the support ends or ended, in the app's language. */
internal expect fun supportEndText(iso: String): String

enum class SupportMessage { THANKS, OTHER_ACCOUNT, PENDING, FAILED }

/** The account's support and what a purchase did, for the settings card. */
@Stable
class SupportController(
    private val supporters: Supporters,
    private val store: SupportStore?,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf<SupporterState?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<SupportMessage?>(null)
        private set

    fun refresh() {
        scope.launch { state = supporters.state() }
    }

    /** Sends again what the store already holds, then reads the support. */
    fun resendOwned() {
        val shop = store ?: return refresh()
        scope.launch {
            val owned = runCatching { shop.owned() }.getOrDefault(emptyList())
            for (token in owned) supporters.recordPlayPurchase(token)
            state = supporters.state()
        }
    }

    fun buy() {
        val hash = supporters.accountHash() ?: return
        message = null
        store?.buy(hash)
    }

    fun purchased(token: String) {
        working = true
        message = null
        scope.launch {
            message = when (supporters.recordPlayPurchase(token)) {
                PlayPurchaseResult.RECORDED -> SupportMessage.THANKS
                PlayPurchaseResult.OTHER_ACCOUNT -> SupportMessage.OTHER_ACCOUNT
                PlayPurchaseResult.NOT_PAID -> SupportMessage.PENDING
                PlayPurchaseResult.FAILED -> SupportMessage.FAILED
            }
            state = supporters.state()
            working = false
        }
    }
}

/**
 * «Support IMAGO» in the settings: what the programme gives, the account's support and, in the Google Play
 * build, the subscription. Shown only with the programme open; in the Play build, only once the store
 * has the subscription to sell, or for someone who already supports.
 */
@Composable
fun SupportSettingsCard(onOpenAccount: (signUp: Boolean) -> Unit, viewModel: AccountViewModel = accountViewModel()) {
    val supporters = viewModel.supporters
    if (!supporters.available) return
    val store = LocalSupportStore.current
    val session by viewModel.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val support = remember(supporters, store, scope) { SupportController(supporters, store, scope) }
    val price = store?.price?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(support, session?.userId) { if (session != null) support.resendOwned() }
    LaunchedEffect(support, store) { store?.purchases?.collect(support::purchased) }
    val active = support.state?.active == true
    if (store != null && price == null && !active) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val body = MaterialTheme.typography.bodyMedium
    CollapsibleSettingsCard(
        stringResource(Res.string.support_title), Icons.Filled.Favorite, expanded, { expanded = it },
        iconTint = ImagoUi.colors.supportHeart, highlight = ImagoUi.colors.accent,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            Text(stringResource(Res.string.support_intro), style = body, color = ImagoUi.colors.text)
            for (benefit in listOf(Res.string.support_benefit_roadmap, Res.string.support_benefit_gallery, Res.string.support_benefit_rounds, Res.string.support_benefit_own_photos)) {
                Text("· " + stringResource(benefit), style = body, color = ImagoUi.colors.secondaryText)
            }
            Text(stringResource(Res.string.support_not_premium), style = MaterialTheme.typography.bodySmall, color = ImagoUi.colors.secondaryText)
            val state = support.state
            when {
                session == null -> {
                    Text(stringResource(Res.string.support_needs_account), style = body, color = ImagoUi.colors.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                        Button(onClick = { onOpenAccount(false) }) { Text(stringResource(Res.string.account_sign_in)) }
                        OutlinedButton(onClick = { onOpenAccount(true) }) { Text(stringResource(Res.string.account_create)) }
                    }
                    return@Column
                }
                state?.active == true && state.until != null ->
                    Text(stringResource(Res.string.support_active, supportEndText(state.until!!)), style = body, color = ImagoUi.colors.text)
                state?.until != null ->
                    Text(stringResource(Res.string.support_ended, supportEndText(state.until!!)), style = body, color = ImagoUi.colors.secondaryText)
            }
            when {
                support.working -> CircularProgressIndicator(color = ImagoUi.colors.emphasis)
                store != null && price != null && !active -> {
                    Button(onClick = support::buy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.support_buy, price)) }
                    Text(stringResource(Res.string.support_store_note), style = MaterialTheme.typography.bodySmall, color = ImagoUi.colors.secondaryText)
                }
                // Outside a Google Play install the programme's page says how to support, Buy Me a Coffee included.
                store == null && !active -> supporters.programmeUrl(Locale.current.language == "pt")?.let { url ->
                    OutlinedButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.support_how))
                    }
                }
            }
            support.message?.let { shown ->
                val text = when (shown) {
                    SupportMessage.THANKS -> Res.string.support_thanks
                    SupportMessage.OTHER_ACCOUNT -> Res.string.support_other_account
                    SupportMessage.PENDING -> Res.string.support_pending
                    SupportMessage.FAILED -> Res.string.support_failed
                }
                Text(stringResource(text), style = body, color = if (shown == SupportMessage.THANKS) ImagoUi.colors.text else ImagoUi.colors.error)
            }
        }
    }
}
