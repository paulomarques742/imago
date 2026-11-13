package eu.studio742.imago.feature.account

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticEvent
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.DiagnosticOutcome
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The support subscription through Google Play billing. The server acknowledges each purchase once it
 * has checked it with Google Play, so nothing is acknowledged here. Failures go to the diagnostics as
 * `ACCOUNT SUPPORTER` with Play's response code only — never the purchase token.
 */
class PlaySupportStore(
    private val activity: Activity,
    private val productId: String,
) : SupportStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prices = MutableStateFlow<String?>(null)
    private val completed = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var product: ProductDetails? = null

    override val price: StateFlow<String?> = prices
    override val purchases: SharedFlow<String> = completed

    private val listener = PurchasesUpdatedListener { result, bought ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> bought.orEmpty()
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && productId in it.products }
                .forEach { completed.tryEmit(it.purchaseToken) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> failed(result)
        }
    }

    private val client = BillingClient.newBuilder(activity)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Connects to Play and reads the subscription's price; without it the settings card stays hidden. */
    fun start() {
        if (productId.isBlank()) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    // A phone without Play, or a build Play does not know: nothing to sell here.
                    if (result.responseCode != BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) failed(result)
                    return
                }
                scope.launch { loadProduct() }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private suspend fun loadProduct() {
        val query = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.SUBS).build(),
        )).build()
        val result = client.queryProductDetails(query)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return failed(result.billingResult)
        val details = result.productDetailsList?.firstOrNull { it.productId == productId } ?: return
        product = details
        prices.value = details.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
    }

    override fun buy(accountHash: String) {
        val details = product ?: return
        val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer.offerToken).build(),
            ))
            .setObfuscatedAccountId(accountHash)
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) failed(result)
    }

    override suspend fun owned(): List<String> {
        if (!client.isReady) return emptyList()
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            failed(result.billingResult)
            return emptyList()
        }
        return result.purchasesList
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && productId in it.products }
            .map { it.purchaseToken }
    }

    fun close() {
        scope.cancel()
        client.endConnection()
    }

    private fun failed(result: BillingResult) {
        LocalDiagnostics.record {
            DiagnosticEvent(DiagnosticArea.ACCOUNT, DiagnosticOutcome.FAILURE, DiagnosticOperation.SUPPORTER, status = result.responseCode)
        }
    }
}
