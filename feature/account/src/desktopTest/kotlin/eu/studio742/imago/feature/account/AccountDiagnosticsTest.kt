package eu.studio742.imago.feature.account

import eu.studio742.imago.core.model.diagnostics.*
import eu.studio742.imago.core.sync.AccountException
import eu.studio742.imago.feature.account.resources.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyAccountRefusalIsRecordedWithoutTheServerMessageOrCredentials() {
        val store = DiagnosticStore(temporary.newFolder())
        LocalDiagnostics.start(store, emptyMap())
        try {
            AccountException.Reason.entries.forEach { reason ->
                AccountException(reason, "private-email@example.invalid private-password private-token")
                    .accountText(Res.string.account_request_failed)
            }
            val report = store.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()).readText()
            assertEquals(AccountException.Reason.entries.size, report.lineSequence().count { it.contains("ACCOUNT ACCOUNT_REQUEST FAILURE") })
            listOf("private-email", "private-password", "private-token", "example.invalid").forEach { assertFalse(report.contains(it)) }
        } finally { LocalDiagnostics.stop() }
    }
}
