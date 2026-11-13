package eu.studio742.imago.feature.account

import eu.studio742.imago.core.sync.PlayPurchaseResult
import eu.studio742.imago.core.sync.SupporterState
import eu.studio742.imago.core.sync.Supporters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SupportControllerTest {
    private class FakeSupporters(var result: PlayPurchaseResult = PlayPurchaseResult.RECORDED, var hash: String? = "hash") : Supporters {
        var supporting = false
        val recorded = mutableListOf<String>()
        override val available = true
        override fun programmeUrl(portuguese: Boolean) = "https://site.example/supporters/"
        override suspend fun state() = SupporterState(supporting, if (supporting) "2026-12-11T10:00:00Z" else null)
        override fun accountHash() = hash
        override suspend fun recordPlayPurchase(purchaseToken: String): PlayPurchaseResult {
            recorded += purchaseToken
            if (result == PlayPurchaseResult.RECORDED) supporting = true
            return result
        }
    }

    private class FakeStore(val held: List<String> = emptyList()) : SupportStore {
        var bought: String? = null
        override val price = MutableStateFlow<String?>("1,09 €")
        override val purchases = MutableSharedFlow<String>()
        override fun buy(accountHash: String) { bought = accountHash }
        override suspend fun owned() = held
    }

    private fun controller(supporters: Supporters, store: SupportStore?) = SupportController(supporters, store, CoroutineScope(Dispatchers.Unconfined))

    @Test fun buyingCarriesTheAccountsHash() {
        val store = FakeStore()
        controller(FakeSupporters(), store).buy()
        assertEquals("hash", store.bought)
    }

    @Test fun withoutAnAccountNothingIsBought() {
        val store = FakeStore()
        controller(FakeSupporters(hash = null), store).buy()
        assertNull(store.bought)
    }

    @Test fun aCompletedPurchaseIsRecordedAndTheSupportReadAgain() {
        val supporters = FakeSupporters()
        val support = controller(supporters, FakeStore())
        support.purchased("token")
        assertEquals(listOf("token"), supporters.recorded)
        assertEquals(SupportMessage.THANKS, support.message)
        assertEquals(true, support.state?.active)
        assertFalse(support.working)
    }

    @Test fun eachOutcomeSaysWhatHappened() {
        mapOf(
            PlayPurchaseResult.OTHER_ACCOUNT to SupportMessage.OTHER_ACCOUNT,
            PlayPurchaseResult.NOT_PAID to SupportMessage.PENDING,
            PlayPurchaseResult.FAILED to SupportMessage.FAILED,
        ).forEach { (result, message) ->
            val support = controller(FakeSupporters(result = result), FakeStore())
            support.purchased("token")
            assertEquals(result.name, message, support.message)
        }
    }

    @Test fun purchasesTheStoreAlreadyHoldsAreSentAgain() {
        val supporters = FakeSupporters()
        val support = controller(supporters, FakeStore(held = listOf("old-1", "old-2")))
        support.resendOwned()
        assertEquals(listOf("old-1", "old-2"), supporters.recorded)
        assertEquals(true, support.state?.active)
        assertNull(support.message)
    }

    @Test fun withoutAStoreTheSupportIsOnlyRead() {
        val supporters = FakeSupporters()
        val support = controller(supporters, null)
        support.resendOwned()
        assertEquals(emptyList<String>(), supporters.recorded)
        assertEquals(false, support.state?.active)
    }
}
