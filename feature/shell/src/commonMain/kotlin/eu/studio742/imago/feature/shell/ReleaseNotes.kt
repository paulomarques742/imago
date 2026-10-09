package eu.studio742.imago.feature.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import eu.studio742.imago.feature.shell.resources.Res
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

/** One page of a big release's news: what it is about, in a title and a couple of sentences. */
internal class NewsPage(val icon: ImageVector, val title: StringResource, val body: StringResource)

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
