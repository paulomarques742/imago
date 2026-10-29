package eu.studio742.imago.core.raw

import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RawDecodeQueueTest {
    @Test fun `a waiting decode is cancellable before opening the next original`() {
        val failure = AtomicReference<Throwable>()
        val started = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            try {
                RawDecoder.decodePreview(File("missing-raw-original.asset"), maxSide = 128)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        RawDecodeQueue.run {
            worker.start()
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                awaitWaiting(worker)
                worker.interrupt()
                worker.join(5000)
                assertFalse("Cancellation must not wait for the active decode to finish.", worker.isAlive)
                assertTrue("${failure.get()}", failure.get() is CancellationException)
            } finally { worker.interrupt() }
        }
        worker.join(5000)
    }

    @Test fun `only one worker enters and a failed decode releases the next worker`() {
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { RawDecodeQueue.run { entered.countDown() } }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertThrows(IllegalStateException::class.java) {
                RawDecodeQueue.run {
                    worker.start()
                    awaitWaiting(worker)
                    assertEquals(1L, entered.count)
                    error("The active decode failed.")
                }
            }
            assertTrue("The next decode must acquire the released permit.", entered.await(5, TimeUnit.SECONDS))
            worker.join(5000)
            assertNull(failure.get())
        } finally {
            worker.interrupt()
            worker.join(5000)
        }
    }

    @Test fun `LibRaw's work runs on one thread that outlives its callers`() {
        val seen = Collections.synchronizedSet(HashSet<Thread>())
        repeat(4) {
            val caller = Thread { RawThreads.decode { seen += Thread.currentThread() } }
            caller.start()
            caller.join(5000)
        }
        assertEquals(1, seen.size)
        assertTrue(seen.single().isAlive)
    }

    @Test fun `a failure reaches the caller as it was thrown`() {
        val failure = RawDecodeException(-4)
        assertSame(failure, assertThrows(RawDecodeException::class.java) { RawThreads.read { throw failure } })
    }

    @Test fun `interrupting the caller interrupts the work`() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val caller = Thread {
            try {
                RawThreads.decode {
                    started.countDown()
                    try { Thread.sleep(10_000) } catch (interrupted: InterruptedException) { stopped.countDown() }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        caller.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        caller.interrupt()
        caller.join(5000)
        assertTrue("${failure.get()}", failure.get() is CancellationException)
        assertTrue("The work must hear the interruption.", stopped.await(5, TimeUnit.SECONDS))
    }

    private fun awaitWaiting(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.state != Thread.State.WAITING && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
        assertEquals("The worker must wait for the active decode's permit.", Thread.State.WAITING, worker.state)
    }
}
