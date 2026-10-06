package eu.studio742.imago.feature.library

import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions as Contract
import org.junit.Assert.assertEquals
import org.junit.Test

class ImmichKeyPermissionsTest {
    /**
     * A new endpoint in the contract with a permission the screen does not explain left whoever
     * follows the list with a key that fails — and an explained permission nobody uses any more asked
     * for more access. The two lists have to be the same.
     */
    @Test fun explainsExactlyThePermissionsTheContractNeeds() {
        val shown = ImmichKeyPermissionList.map { it.id }
        assertEquals(shown.distinct(), shown)
        assertEquals(Contract.REQUIRED.sorted(), shown.sorted())
    }
}
