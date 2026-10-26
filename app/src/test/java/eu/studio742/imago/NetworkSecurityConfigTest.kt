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
    fun anImmichServerMayUseACertificateFromAnAuthorityThePersonInstalled() {
        assertTrue(config.userAuthoritiesTrusted("photos.example.test"))
        assertTrue(config.userAuthoritiesTrusted("immich.local"))
        assertTrue(config.systemAuthoritiesTrusted("photos.example.test"))
    }

    @Test
    fun theAppsOwnServicesTrustTheSystemsAuthoritiesOnly() {
        listOf("abcdefgh.supabase.co", "supabase.co", "imago.742studio.eu", "742studio.eu", "tiles.openfreemap.org").forEach { host ->
            assertFalse("$host must not trust an authority installed on the phone", config.userAuthoritiesTrusted(host))
            assertTrue(host, config.systemAuthoritiesTrusted(host))
        }
        val host = URI(BuildConfig.SUPABASE_URL).host ?: return
        assertFalse(host, config.userAuthoritiesTrusted(host))
    }

    @Test
    fun aDomainThatOnlyEndsLikeOursIsNotOurs() {
        assertTrue(config.cleartextPermitted("notsupabase.co"))
        assertTrue(config.cleartextPermitted("other742studio.eu"))
        assertTrue(config.userAuthoritiesTrusted("notsupabase.co"))
    }
}

private class NetworkPolicy(
    private val base: Rule,
    private val domains: List<Rule>,
) {
    /** What one `domain-config` or the `base-config` says. Anchors are the sources named, or null when none are. */
    class Rule(val domain: String?, val subdomains: Boolean, val cleartext: Boolean, val anchors: Set<String>?)

    // Android picks the longest matching domain; an exact match beats a subdomain one of the same name.
    private fun ruleFor(host: String): Rule {
        val name = host.lowercase()
        return domains
            .filter { rule -> name == rule.domain || (rule.subdomains && name.endsWith(".${rule.domain}")) }
            .maxByOrNull { it.domain!!.length } ?: base
    }

    fun cleartextPermitted(host: String) = ruleFor(host).cleartext

    // From API 24 an app trusts the system's authorities and not the user's unless the config says so.
    private fun anchors(host: String) = ruleFor(host).anchors ?: base.anchors ?: setOf("system")
    fun userAuthoritiesTrusted(host: String) = "user" in anchors(host)
    fun systemAuthoritiesTrusted(host: String) = "system" in anchors(host)

    companion object {
        fun read(file: File): NetworkPolicy {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            fun anchorsOf(config: Element): Set<String>? {
                val certificates = config.getElementsByTagName("certificates")
                if (certificates.length == 0) return null
                return (0 until certificates.length).map { (certificates.item(it) as Element).getAttribute("src") }.toSet()
            }
            val baseElement = root.getElementsByTagName("base-config").item(0) as Element
            val base = Rule(null, false, baseElement.getAttribute("cleartextTrafficPermitted") != "false", anchorsOf(baseElement))
            val domains = mutableListOf<Rule>()
            val configs = root.getElementsByTagName("domain-config")
            for (i in 0 until configs.length) {
                val config = configs.item(i) as Element
                val permitted = config.getAttribute("cleartextTrafficPermitted") != "false"
                val entries = config.getElementsByTagName("domain")
                for (j in 0 until entries.length) {
                    val entry = entries.item(j) as Element
                    domains += Rule(entry.textContent.trim().lowercase(), entry.getAttribute("includeSubdomains") == "true", permitted, anchorsOf(config))
                }
            }
            return NetworkPolicy(base, domains)
        }
    }
}
