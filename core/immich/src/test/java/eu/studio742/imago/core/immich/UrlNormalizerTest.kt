package eu.studio742.imago.core.immich

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun removesApiSuffixWithoutDroppingReverseProxyPath() {
        assertEquals(
            "https://photos.example.test/immich/",
            normalizeServerUrl("https://photos.example.test/immich/api/").toString(),
        )
    }
}
