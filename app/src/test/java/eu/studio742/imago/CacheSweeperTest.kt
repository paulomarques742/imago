package eu.studio742.imago

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cache folder after a launch: the app's stale working files gone, everything else as it was. */
class CacheSweeperTest {
    private val cache: File = Files.createTempDirectory("cache-sweeper").toFile()
    private val now = 100L * DAY

    @After
    fun tearDown() {
        cache.deleteRecursively()
    }

    private fun file(path: String, ageMillis: Long): File = File(cache, path).apply {
        parentFile.mkdirs()
        writeText("x")
        setLastModified(now - ageMillis)
    }

    private fun sweep() = CacheSweeper(cache, maxAgeMillis = DAY, now = { now }).sweep()

    @Test
    fun staleWorkingFilesLeaveAndRecentOnesStay() {
        val download = file("download-123.asset", 2 * DAY)
        val reel = file("imago-reel-1.mp4", 2 * DAY)
        val proxy = file("composition-proxy-9.mp4", 2 * DAY)
        val shared = file("share/photo.jpg", 2 * DAY)
        val beingWritten = file("imago-export-2.jpg", 60_000L)

        assertEquals(4, sweep())
        listOf(download, reel, proxy, shared).forEach { assertFalse(it.path, it.exists()) }
        assertTrue(beingWritten.exists())
        // The folders the FileProvider serves are kept; only their contents went.
        assertTrue(File(cache, "share").isDirectory)
    }

    @Test
    fun theImageCachesAndUnknownFilesAreNotTouched() {
        val thumbnail = file("image_cache/journal", 30 * DAY)
        val render = file("recipe_renders/abc.1", 30 * DAY)
        val report = file("support-reports/report.txt", 30 * DAY)
        val someoneElses = file("cache.db", 30 * DAY)

        assertEquals(0, sweep())
        listOf(thumbnail, render, report, someoneElses).forEach { assertTrue(it.path, it.exists()) }
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
