package eu.studio742.imago.core.data

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The editor, the export and a share of the same photo: one download between them. */
class WorkingCopiesTest {
    @get:Rule val folder = TemporaryFolder()
    private val made = AtomicInteger()

    @After
    fun tearDown() = WorkingCopies.uninstall()

    private val copies get() = File(folder.root, "copies")

    private fun install(limit: Long = 1_000) = WorkingCopies.install(copies, limit)

    private suspend fun fetch(key: String, content: String = key): File {
        val destination = folder.newFile()
        WorkingCopies.fill(key, destination) { target ->
            made.incrementAndGet()
            delay(20)
            target.writeText(content)
        }
        return destination
    }

    @Test
    fun `the second caller gets the kept copy and its own file`() = runBlocking {
        install()
        val first = fetch("original:a")
        val second = fetch("original:a")
        assertEquals(1, made.get())
        assertEquals("original:a", second.readText())
        // Each caller deletes its own file; the kept one is not theirs.
        first.delete()
        assertEquals("original:a", fetch("original:a").readText())
        assertEquals(1, made.get())
    }

    @Test
    fun `callers asking at once share one download`() = runBlocking {
        install()
        val files = List(3) { async { fetch("original:a") } }.awaitAll()
        assertEquals(1, made.get())
        files.forEach { assertEquals("original:a", it.readText()) }
    }

    @Test
    fun `the oldest copies leave once over the limit`() = runBlocking {
        install(limit = 25)
        fetch("one", "x".repeat(10))
        Thread.sleep(20)
        fetch("two", "y".repeat(10))
        Thread.sleep(20)
        fetch("three", "z".repeat(10))
        assertEquals(2, copies.listFiles()!!.size)
        fetch("one", "x".repeat(10))
        assertEquals("the first had been dropped", 4, made.get())
    }

    @Test
    fun `a file larger than the whole allowance is handed over and not kept`() = runBlocking {
        install(limit = 5)
        assertEquals("too large", fetch("big", "too large").readText())
        assertTrue(copies.listFiles()!!.isEmpty())
    }

    @Test
    fun `a failed download leaves nothing behind and is tried again`() = runBlocking {
        install()
        val destination = folder.newFile()
        val failure = runCatching {
            WorkingCopies.fill("original:a", destination) { target ->
                target.writeText("half")
                error("connection reset")
            }
        }
        assertTrue(failure.isFailure)
        assertTrue(copies.listFiles()!!.isEmpty())
        assertEquals("original:a", fetch("original:a").readText())
    }

    @Test
    fun `a launch starts empty`() = runBlocking {
        install()
        fetch("original:a")
        install()
        assertTrue(copies.listFiles()!!.isEmpty())
    }

    @Test
    fun `without a folder every call makes its own`() = runBlocking {
        fetch("original:a")
        fetch("original:a")
        assertEquals(2, made.get())
        assertFalse(copies.exists())
    }
}
