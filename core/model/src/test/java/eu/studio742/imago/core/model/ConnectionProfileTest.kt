package eu.studio742.imago.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionProfileTest {
    private fun profile(vararg headers: Pair<String, String>) =
        ConnectionProfile(headers.map { ConnectionHeader(it.first, it.second) })

    @Test fun emptyProfileNeedsNothing() {
        assertNull(ConnectionProfile.None.problem("http://192.168.1.10:2283"))
    }

    @Test fun cloudflareAccessTokenIsAccepted() {
        val token = profile("CF-Access-Client-Id" to "abc.access", "CF-Access-Client-Secret" to "s3cret")
        assertNull(token.problem("https://photos.example.test"))
        assertEquals(ConnectionHeaders.CLOUDFLARE_ACCESS, token.headers.map { it.name })
    }

    @Test fun secretsNeverGoOverPlainHttp() {
        assertEquals(UserMessage.CONNECTION_NEEDS_HTTPS, profile("X-Secret" to "v").problem("http://photos.example.test"))
    }

    @Test fun namesTheAppOwnsAreRefused() {
        for (name in listOf("x-api-key", "X-API-KEY", "Host", "Cookie", "Content-Length", "Transfer-Encoding")) {
            assertEquals(name, UserMessage.CONNECTION_HEADER_NAME_RESERVED, profile(name to "v").problem("https://a.test"))
        }
    }

    @Test fun malformedNamesAndValuesAreRefused() {
        assertEquals(UserMessage.CONNECTION_HEADER_NAME_INVALID, profile("Bad Name" to "v").problem("https://a.test"))
        assertEquals(UserMessage.CONNECTION_HEADER_NAME_INVALID, profile("Bad:Name" to "v").problem("https://a.test"))
        assertEquals(UserMessage.CONNECTION_HEADER_NAME_INVALID, profile("" to "v").problem("https://a.test"))
        assertEquals(UserMessage.CONNECTION_HEADER_VALUE_INVALID, profile("X-A" to "").problem("https://a.test"))
        assertEquals(UserMessage.CONNECTION_HEADER_VALUE_INVALID, profile("X-A" to "a\r\nX-B: injected").problem("https://a.test"))
    }

    @Test fun theSameHeaderTwiceIsRefusedWhateverTheCase() {
        assertEquals(UserMessage.CONNECTION_HEADER_REPEATED, profile("X-A" to "1", "x-a" to "2").problem("https://a.test"))
    }
}
