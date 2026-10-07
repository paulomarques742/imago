package eu.studio742.imago.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private fun v(text: String) = AppVersion.parse(text)!!
    private fun releases(vararg versions: String) = versions.map { Release(v(it), emptyList()) }
    private fun List<Release>.versions() = map { it.version.toString() }

    private val history = releases("0.13.0", "0.12.1", "0.12.0", "0.11.0")

    @Test fun versionsCompareNumberByNumber() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.12.1") > v("0.12.0"))
        assertNull(AppVersion.parse("0.12"))
        assertNull(AppVersion.parse("0.12.0-beta"))
    }

    @Test fun aNewInstallIsToldNothing() {
        assertEquals(emptyList<String>(), unseenReleases(null, welcomeCompleted = false, v("0.13.0"), history).versions())
    }

    /** The install already in the testers' hands records nothing: it came from 0.10.0. */
    @Test fun anInstallFromBeforeTheNewsCatchesUpFrom0_10_0() {
        assertEquals(
            listOf("0.13.0", "0.12.1", "0.12.0", "0.11.0"),
            unseenReleases(null, welcomeCompleted = true, v("0.13.0"), history).versions(),
        )
    }

    @Test fun skippedVersionsAreAllShownNewestFirst() {
        assertEquals(listOf("0.13.0", "0.12.1"), unseenReleases("0.12.0", true, v("0.13.0"), history).versions())
    }

    @Test fun releasesAfterTheInstalledVersionWaitForIt() {
        assertEquals(listOf("0.12.1", "0.12.0"), unseenReleases("0.11.0", true, v("0.12.1"), history).versions())
    }

    @Test fun theSameVersionAgainShowsNothing() {
        assertEquals(emptyList<String>(), unseenReleases("0.13.0", true, v("0.13.0"), history).versions())
    }

    /** A patch with no entry opens nothing, even though the version changed. */
    @Test fun aVersionWithoutNotesShowsNothing() {
        assertEquals(emptyList<String>(), unseenReleases("0.13.0", true, v("0.13.1"), history).versions())
    }

    @Test fun anUnreadableRecordedVersionShowsNothing() {
        assertEquals(emptyList<String>(), unseenReleases("garbage", true, v("0.13.0"), history).versions())
    }

    @Test fun theSettingsShowTheNewestReleaseUpToTheInstalledOne() {
        assertEquals("0.13.0", currentRelease(v("0.13.2"), history)?.version.toString())
        assertEquals("0.12.1", currentRelease(v("0.12.1"), history)?.version.toString())
        assertNull(currentRelease(v("0.10.0"), history))
    }

    /** The list is written by hand at each release; a slip there would show notes out of order or twice. */
    @Test fun theReleaseListIsNewestFirstWithoutRepeatsOrEmptyEntries() {
        val versions = Releases.map { it.version }
        assertEquals(versions.sortedDescending(), versions)
        assertEquals(versions.distinct(), versions)
        assertTrue(Releases.all { it.notes.isNotEmpty() })
        assertTrue(Releases.all { it.version > VersionBeforeNews })
    }

    @Test fun theInstalledVersionIsReadable() {
        assertEquals(APP_VERSION, InstalledVersion.toString())
    }
}
