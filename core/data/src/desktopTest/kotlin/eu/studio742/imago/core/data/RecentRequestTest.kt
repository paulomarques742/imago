package eu.studio742.imago.core.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library's opening asks for the months from three places; the server should hear it once. */
class RecentRequestTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 0L
    private val calls = AtomicInteger()

    @After
    fun tearDown() = scope.cancel()

    private fun request(gate: CompletableDeferred<Unit>? = null, fail: Boolean = false) =
        RecentRequest<String, String>(scope, freshForMillis = 5_000, now = { clock }) { key ->
            calls.incrementAndGet()
            gate?.await()
            if (fail) error("offline")
            "$key#${calls.get()}"
        }

    @Test
    fun `callers arriving while the request is out share it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val months = request(gate)
        val first = async { months.get("server") }
        val second = async { months.get("server") }
        val third = async { months.get("server") }
        gate.complete(Unit)
        assertEquals(setOf("server#1"), setOf(first.await(), second.await(), third.await()))
        assertEquals(1, calls.get())
    }

    @Test
    fun `an answer is reused while fresh, and asked again after`() = runBlocking {
        val months = request()
        assertEquals("server#1", months.get("server"))
        clock += 4_000
        assertEquals("server#1", months.get("server"))
        clock += 2_000
        assertEquals("server#2", months.get("server"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `another server is another request`() = runBlocking {
        val months = request()
        months.get("one")
        assertEquals("two#2", months.get("two"))
    }

    @Test
    fun `a failure is not kept`() = runBlocking {
        val months = request(fail = true)
        repeat(2) { assertTrue(runCatching { months.get("server") }.isFailure) }
        assertEquals(2, calls.get())
    }
}
