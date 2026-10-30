package eu.studio742.imago.feature.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Deblur
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WbAuto
import androidx.compose.ui.graphics.vector.ImageVector
import eu.studio742.imago.feature.shell.resources.Res
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_1_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_1_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_2_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_2_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_3_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_3_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_4_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_4_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_5_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_5_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_6_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_6_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_7_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_7_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_1
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_2
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_3
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_4
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_5
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_6
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_7
import eu.studio742.imago.feature.shell.resources.shell_news_0_30_0_8_8
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_1_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_1_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_2_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_2_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_3_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_3_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_4_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_4_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_5_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_5_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_6_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_23_0_6_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_1_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_1_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_2_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_2_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_3_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_3_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_4_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_4_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_5_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_22_0_5_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_1_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_1_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_2_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_2_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_3_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_3_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_4_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_4_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_5_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_5_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_6_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_6_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_7_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_7_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_8_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_21_0_8_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_1_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_1_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_2_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_2_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_3_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_3_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_4_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_4_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_5_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_5_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_6_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_6_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_7_title
import eu.studio742.imago.feature.shell.resources.shell_news_0_20_0_7_body
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_1
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_2
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_3
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_4
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_5
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_6
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_7
import eu.studio742.imago.feature.shell.resources.shell_news_0_11_0_8
import org.jetbrains.compose.resources.StringResource

/** A MAJOR.MINOR.PATCH version, compared number by number: 0.10.0 comes after 0.9.0. */
internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): AppVersion? =
            Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(text.trim())?.destructured
                ?.let { (major, minor, patch) -> AppVersion(major.toInt(), minor.toInt(), patch.toInt()) }
    }
}

/**
 * One page of a big release's news: what it is about, in a title and a couple of sentences, or in a
 * title and a short list of [items] for the smaller things. A [featured] page is the release's lead,
 * set in gold.
 */
internal class NewsPage(
    val icon: ImageVector,
    val title: StringResource,
    val body: StringResource? = null,
    val items: List<StringResource> = emptyList(),
    val featured: Boolean = false,
) {
    init { require((body != null) != items.isNotEmpty()) { "A news page has either a body or items." } }
}

/**
 * What changed in a version: one line per change or, for a release with too much for a list, a page
 * per theme that the person swipes through.
 */
internal class Release(
    val version: AppVersion,
    val notes: List<StringResource> = emptyList(),
    val pages: List<NewsPage> = emptyList(),
)

/**
 * Every version with something to tell, newest first.
 *
 * A version without an entry — a fix nobody would notice — opens nothing after the update.
 */
internal val Releases: List<Release> = listOf(
    Release(
        AppVersion(0, 30, 0),
        pages = listOf(
            NewsPage(Icons.Outlined.Camera, Res.string.shell_news_0_30_0_1_title, Res.string.shell_news_0_30_0_1_body, featured = true),
            NewsPage(Icons.Outlined.Healing, Res.string.shell_news_0_30_0_2_title, Res.string.shell_news_0_30_0_2_body),
            NewsPage(Icons.Outlined.Deblur, Res.string.shell_news_0_30_0_3_title, Res.string.shell_news_0_30_0_3_body),
            NewsPage(Icons.Outlined.BarChart, Res.string.shell_news_0_30_0_4_title, Res.string.shell_news_0_30_0_4_body),
            NewsPage(Icons.Outlined.Lens, Res.string.shell_news_0_30_0_5_title, Res.string.shell_news_0_30_0_5_body),
            NewsPage(Icons.Outlined.MotionPhotosOn, Res.string.shell_news_0_30_0_6_title, Res.string.shell_news_0_30_0_6_body),
            NewsPage(Icons.Outlined.CloudUpload, Res.string.shell_news_0_30_0_7_title, Res.string.shell_news_0_30_0_7_body),
            NewsPage(
                Icons.Outlined.Checklist,
                Res.string.shell_news_0_30_0_8_title,
                items = listOf(
                    Res.string.shell_news_0_30_0_8_1,
                    Res.string.shell_news_0_30_0_8_2,
                    Res.string.shell_news_0_30_0_8_3,
                    Res.string.shell_news_0_30_0_8_4,
                    Res.string.shell_news_0_30_0_8_5,
                    Res.string.shell_news_0_30_0_8_6,
                    Res.string.shell_news_0_30_0_8_7,
                    Res.string.shell_news_0_30_0_8_8,
                ),
            ),
        ),
    ),
    Release(
        AppVersion(0, 23, 0),
        pages = listOf(
            NewsPage(Icons.Outlined.Lock, Res.string.shell_news_0_23_0_1_title, Res.string.shell_news_0_23_0_1_body),
            NewsPage(Icons.Outlined.Checklist, Res.string.shell_news_0_23_0_2_title, Res.string.shell_news_0_23_0_2_body),
            NewsPage(Icons.Outlined.Info, Res.string.shell_news_0_23_0_3_title, Res.string.shell_news_0_23_0_3_body),
            NewsPage(Icons.Outlined.Forum, Res.string.shell_news_0_23_0_4_title, Res.string.shell_news_0_23_0_4_body),
            NewsPage(Icons.Outlined.Settings, Res.string.shell_news_0_23_0_5_title, Res.string.shell_news_0_23_0_5_body),
            NewsPage(Icons.Outlined.Build, Res.string.shell_news_0_23_0_6_title, Res.string.shell_news_0_23_0_6_body),
        ),
    ),
    Release(
        AppVersion(0, 22, 0),
        pages = listOf(
            NewsPage(Icons.Outlined.Tune, Res.string.shell_news_0_22_0_1_title, Res.string.shell_news_0_22_0_1_body),
            NewsPage(Icons.Outlined.Brush, Res.string.shell_news_0_22_0_2_title, Res.string.shell_news_0_22_0_2_body),
            NewsPage(Icons.Outlined.Palette, Res.string.shell_news_0_22_0_3_title, Res.string.shell_news_0_22_0_3_body),
            NewsPage(Icons.Outlined.Layers, Res.string.shell_news_0_22_0_4_title, Res.string.shell_news_0_22_0_4_body),
            NewsPage(Icons.Outlined.Build, Res.string.shell_news_0_22_0_5_title, Res.string.shell_news_0_22_0_5_body),
        ),
    ),
    Release(
        AppVersion(0, 21, 0),
        pages = listOf(
            NewsPage(Icons.Outlined.SlowMotionVideo, Res.string.shell_news_0_21_0_1_title, Res.string.shell_news_0_21_0_1_body),
            NewsPage(Icons.Outlined.JoinInner, Res.string.shell_news_0_21_0_2_title, Res.string.shell_news_0_21_0_2_body),
            NewsPage(Icons.Outlined.Brush, Res.string.shell_news_0_21_0_3_title, Res.string.shell_news_0_21_0_3_body),
            NewsPage(Icons.Outlined.ViewTimeline, Res.string.shell_news_0_21_0_4_title, Res.string.shell_news_0_21_0_4_body),
            NewsPage(Icons.Outlined.WbAuto, Res.string.shell_news_0_21_0_5_title, Res.string.shell_news_0_21_0_5_body),
            NewsPage(Icons.Outlined.Checklist, Res.string.shell_news_0_21_0_6_title, Res.string.shell_news_0_21_0_6_body),
            NewsPage(Icons.Outlined.VisibilityOff, Res.string.shell_news_0_21_0_7_title, Res.string.shell_news_0_21_0_7_body),
            NewsPage(Icons.Outlined.Build, Res.string.shell_news_0_21_0_8_title, Res.string.shell_news_0_21_0_8_body),
        ),
    ),
    Release(
        AppVersion(0, 20, 0),
        pages = listOf(
            NewsPage(Icons.Outlined.PhotoLibrary, Res.string.shell_news_0_20_0_1_title, Res.string.shell_news_0_20_0_1_body),
            NewsPage(Icons.Outlined.TravelExplore, Res.string.shell_news_0_20_0_2_title, Res.string.shell_news_0_20_0_2_body),
            NewsPage(Icons.Outlined.Layers, Res.string.shell_news_0_20_0_3_title, Res.string.shell_news_0_20_0_3_body),
            NewsPage(Icons.Outlined.Straighten, Res.string.shell_news_0_20_0_4_title, Res.string.shell_news_0_20_0_4_body),
            NewsPage(Icons.Outlined.Palette, Res.string.shell_news_0_20_0_5_title, Res.string.shell_news_0_20_0_5_body),
            NewsPage(Icons.Outlined.Tune, Res.string.shell_news_0_20_0_6_title, Res.string.shell_news_0_20_0_6_body),
            NewsPage(Icons.Outlined.PhoneAndroid, Res.string.shell_news_0_20_0_7_title, Res.string.shell_news_0_20_0_7_body),
        ),
    ),
    Release(
        AppVersion(0, 11, 0),
        listOf(
            Res.string.shell_news_0_11_0_1,
            Res.string.shell_news_0_11_0_2,
            Res.string.shell_news_0_11_0_3,
            Res.string.shell_news_0_11_0_4,
            Res.string.shell_news_0_11_0_5,
            Res.string.shell_news_0_11_0_6,
            Res.string.shell_news_0_11_0_7,
            Res.string.shell_news_0_11_0_8,
        ),
    ),
)

internal val InstalledVersion: AppVersion =
    checkNotNull(AppVersion.parse(APP_VERSION)) { "imago.version is not MAJOR.MINOR.PATCH: $APP_VERSION" }

/**
 * The last version that did not record which version was seen. An install that has been used but
 * has no recorded version was updated from it.
 */
internal val VersionBeforeNews = AppVersion(0, 10, 0)

/**
 * The releases to tell about at launch: those after the last one seen, up to the installed one.
 *
 * A new install has nothing to catch up on, and is told nothing. One that never recorded a version
 * but already got past the welcome was updated from [VersionBeforeNews]. A recorded version that
 * cannot be read shows nothing rather than every release ever made.
 */
internal fun unseenReleases(
    lastSeen: String?,
    welcomeCompleted: Boolean,
    installed: AppVersion = InstalledVersion,
    releases: List<Release> = Releases,
): List<Release> {
    val from = when {
        lastSeen != null -> AppVersion.parse(lastSeen) ?: return emptyList()
        welcomeCompleted -> VersionBeforeNews
        else -> return emptyList()
    }
    return releases.filter { it.version > from && it.version <= installed }.sortedByDescending { it.version }
}

/** What the settings tell about the installed version: the newest release up to it. */
internal fun currentRelease(
    installed: AppVersion = InstalledVersion,
    releases: List<Release> = Releases,
): Release? = releases.filter { it.version <= installed }.maxByOrNull { it.version }
