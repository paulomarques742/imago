package eu.studio742.imago.feature.shell

import eu.studio742.imago.feature.shell.resources.Res
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

/** What changed in a version, one line per change. */
internal class Release(val version: AppVersion, val notes: List<StringResource>)

/**
 * Every version with something to tell, newest first.
 *
 * A version without an entry — a fix nobody would notice — opens nothing after the update.
 */
internal val Releases: List<Release> = listOf(
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
