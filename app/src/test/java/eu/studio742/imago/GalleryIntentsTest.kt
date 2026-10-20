package eu.studio742.imago

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * The requests other apps make of a gallery, resolved against the manifest the way
 * `startActivity` resolves them (only filters with DEFAULT, and IntentFilter's data rules), so
 * that a dropped action cannot quietly hand the camera's thumbnail back to the stock gallery.
 */
class GalleryIntentsTest {
    private val filters = ManifestFilters.read(File("src/main/AndroidManifest.xml"))

    @Test
    fun theCamerasLastPhotoOpensInTheViewerWhicheverReviewActionItUses() {
        val actions = listOf("com.android.camera.action.REVIEW", "android.provider.action.REVIEW", "android.provider.action.REVIEW_SECURE")
        for (action in actions) for (type in listOf("image/jpeg", "video/mp4")) {
            assertEquals("$action $type", listOf(".ViewerActivity"), filters.resolve(action, scheme = "content", type = type))
        }
    }

    @Test
    fun aCameraWithNothingTakenYetOpensTheLibrary() {
        for (type in listOf("vnd.android.cursor.dir/image", "vnd.android.cursor.dir/video")) {
            assertEquals(type, listOf(".MainActivity"), filters.resolve("android.intent.action.VIEW", scheme = "content", type = type))
        }
    }

    // No other gallery reviews a collection, so IMAGO would be the only answer and open without a
    // choice; the camera falls back to VIEW, where the stock gallery is offered next to IMAGO.
    @Test
    fun reviewingACollectionIsLeftToFallThroughToView() {
        for (action in listOf("com.android.camera.action.REVIEW", "android.provider.action.REVIEW")) {
            assertEquals(action, emptyList<String>(), filters.resolve(action, scheme = "content", type = "vnd.android.cursor.dir/image"))
        }
    }

    @Test
    fun askingForTheGalleryAppOpensTheLibrary() {
        assertEquals(listOf(".MainActivity"), filters.resolve("android.intent.action.MAIN", categories = setOf("android.intent.category.APP_GALLERY")))
    }

    @Test
    fun openingEditingAndPickingStillReachTheirActivities() {
        assertEquals(listOf(".ViewerActivity"), filters.resolve("android.intent.action.VIEW", scheme = "content", type = "image/png"))
        assertEquals(listOf(".ViewerActivity"), filters.resolve("android.intent.action.EDIT", scheme = "content", type = "image/jpeg"))
        assertEquals(listOf(".PickerActivity"), filters.resolve("android.intent.action.PICK", type = "vnd.android.cursor.dir/image"))
        assertEquals(
            listOf(".PickerActivity"),
            filters.resolve("android.intent.action.GET_CONTENT", categories = setOf("android.intent.category.OPENABLE"), type = "image/*"),
        )
    }

    @Test
    fun theAccountLinkIsNotMistakenForAPhoto() {
        assertEquals(
            listOf(".MainActivity"),
            filters.resolve("android.intent.action.VIEW", categories = setOf("android.intent.category.BROWSABLE"), scheme = "imago", host = "auth"),
        )
    }
}

private data class Filter(
    val activity: String,
    val actions: Set<String>,
    val categories: Set<String>,
    val schemes: Set<String>,
    val hosts: Set<String>,
    val types: Set<String>,
)

private class ManifestFilters(private val filters: List<Filter>) {
    /** The activities that would be offered, the way `startActivity` asks: DEFAULT is always required. */
    fun resolve(action: String, categories: Set<String> = emptySet(), scheme: String? = null, host: String? = null, type: String? = null) =
        filters
            .filter { action in it.actions && (categories + DEFAULT).all(it.categories::contains) && matchesData(it, scheme, host, type) }
            .map(Filter::activity)
            .distinct()

    // IntentFilter.matchData: without schemes in the filter, a typed intent may only be content: or file:.
    private fun matchesData(filter: Filter, scheme: String?, host: String?, type: String?): Boolean {
        if (filter.schemes.isEmpty() && filter.types.isEmpty()) return scheme == null && type == null
        val schemeMatches = if (filter.schemes.isEmpty()) scheme == null || scheme == "content" || scheme == "file" else scheme in filter.schemes
        val hostMatches = filter.hosts.isEmpty() || host in filter.hosts
        val typeMatches = if (filter.types.isEmpty()) type == null else type != null && filter.types.any { matchesType(it, type) }
        return schemeMatches && hostMatches && typeMatches
    }

    private fun matchesType(pattern: String, type: String): Boolean {
        if (pattern == "*/*" || pattern == type) return true
        val (major, minor) = pattern.split('/', limit = 2)
        if (minor == "*") return type.substringBefore('/') == major
        return type.endsWith("/*") && type.substringBefore('/') == major
    }

    companion object {
        private const val DEFAULT = "android.intent.category.DEFAULT"
        private const val ANDROID = "http://schemas.android.com/apk/res/android"

        fun read(file: File): ManifestFilters {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val root = factory.newDocumentBuilder().parse(file).documentElement
            val activities = root.getElementsByTagName("activity")
            val filters = mutableListOf<Filter>()
            for (i in 0 until activities.length) {
                val activity = activities.item(i) as Element
                val name = activity.getAttributeNS(ANDROID, "name")
                val declared = activity.getElementsByTagName("intent-filter")
                for (j in 0 until declared.length) {
                    val filter = declared.item(j) as Element
                    fun values(tag: String, attribute: String) = buildSet {
                        val nodes = filter.getElementsByTagName(tag)
                        for (k in 0 until nodes.length) (nodes.item(k) as Element).getAttributeNS(ANDROID, attribute).takeIf(String::isNotEmpty)?.let(::add)
                    }
                    filters += Filter(
                        activity = name,
                        actions = values("action", "name"),
                        categories = values("category", "name"),
                        schemes = values("data", "scheme"),
                        hosts = values("data", "host"),
                        types = values("data", "mimeType"),
                    )
                }
            }
            return ManifestFilters(filters)
        }
    }
}
