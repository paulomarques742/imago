package eu.studio742.imago

import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The network security config read the way Android reads it — the most specific matching domain wins,
 * the base config covers the rest — so that a renamed domain or a stray `usesCleartextTraffic` cannot
 * quietly let the account's traffic go in clear again.
 */
class NetworkSecurityConfigTest {
    private val config = NetworkPolicy.read(File("src/main/res/xml/network_security_config.xml"))

    @Test
    fun theManifestUsesTheConfigAndNotTheGlobalSwitch() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("usesCleartextTraffic"))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
    }

    @Test
    fun anImmichServerOnTheLocalNetworkMayStillBePlainHttp() {
        assertTrue(config.cleartextPermitted("192.168.1.10"))
        assertTrue(config.cleartextPermitted("immich.local"))
        assertTrue(config.cleartextPermitted("100.101.102.103"))
    }

    @Test
    fun theAppsOwnServicesRefuseCleartext() {
        listOf("abcdefgh.supabase.co", "supabase.co", "imago.742studio.eu", "742studio.eu", "tiles.openfreemap.org")
            .forEach { host -> assertFalse(host, config.cleartextPermitted(host)) }
    }

    @Test
    fun theAccountServerThisBuildTalksToRefusesCleartext() {
        val host = URI(BuildConfig.SUPABASE_URL).host ?: return
        assertFalse(host, config.cleartextPermitted(host))
    }

    @Test
    fun aDomainThatOnlyEndsLikeOursIsNotOurs() {
        assertTrue(config.cleartextPermitted("notsupabase.co"))
        assertTrue(config.cleartextPermitted("other742studio.eu"))
    }
}

private class NetworkPolicy(
    private val base: Boolean,
    private val domains: List<Triple<String, Boolean, Boolean>>,
) {
    fun cleartextPermitted(host: String): Boolean {
        val name = host.lowercase()
        // Android picks the longest matching domain; an exact match beats a subdomain one of the same name.
        return domains
            .filter { (domain, subdomains, _) -> name == domain || (subdomains && name.endsWith(".$domain")) }
            .maxByOrNull { (domain, _, _) -> domain.length }
            ?.third ?: base
    }

    companion object {
        fun read(file: File): NetworkPolicy {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val base = root.getElementsByTagName("base-config").item(0) as Element?
            val domains = mutableListOf<Triple<String, Boolean, Boolean>>()
            val configs = root.getElementsByTagName("domain-config")
            for (i in 0 until configs.length) {
                val config = configs.item(i) as Element
                val permitted = config.getAttribute("cleartextTrafficPermitted") != "false"
                val entries = config.getElementsByTagName("domain")
                for (j in 0 until entries.length) {
                    val entry = entries.item(j) as Element
                    domains += Triple(entry.textContent.trim().lowercase(), entry.getAttribute("includeSubdomains") == "true", permitted)
                }
            }
            return NetworkPolicy(base?.getAttribute("cleartextTrafficPermitted") != "false", domains)
        }
    }
}
