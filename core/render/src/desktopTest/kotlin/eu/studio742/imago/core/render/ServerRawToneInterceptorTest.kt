package eu.studio742.imago.core.render

import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRawToneInterceptorTest {
    private val url = "https://server.test/api/assets/raw-1/thumbnail?size=preview"

    /** A real JPEG in greys, as the start of a RAF would carry it. */
    private fun jpeg(): ByteArray {
        val image = BufferedImage(160, 104, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 104) for (x in 0 until 160) {
            val value = 40 + (x + y) * 160 / 264
            image.setRGB(x, y, (value shl 16) or (value shl 8) or value)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    private val server = PixelBuffer(96, 64, IntArray(96 * 64) { i ->
        val value = 90 + (i % 96 + i / 96) * 165 / 160
        -0x1000000 or (value shl 16) or (value shl 8) or value
    })

    private class Chain(override val request: ImageRequest, override val size: Size = Size.ORIGINAL) : Interceptor.Chain {
        var proceeded: ImageRequest? = null
        private var current = request
        override fun withRequest(request: ImageRequest) = apply { current = request }
        override fun withSize(size: Size) = this
        override suspend fun proceed(): ImageResult {
            proceeded = current
            return ErrorResult(null, current, IOException("no network in a test"))
        }
    }

    private fun request(build: ImageRequest.Builder.() -> Unit = {}) =
        ImageRequest.Builder(PlatformContext.INSTANCE).data(url).apply(build).build()

    private fun run(interceptor: ServerRawToneInterceptor, request: ImageRequest = request()): ImageRequest = runBlocking {
        val chain = Chain(request)
        interceptor.intercept(chain)
        requireNotNull(chain.proceeded)
    }

    private val raw = LibraryRaw("raw-1", "sum")

    @Test fun `what is not a RAW goes through untouched, with nothing read`() {
        var reads = 0
        val interceptor = ServerRawToneInterceptor(rawAt = { null }, head = { _, _ -> reads++; jpeg() }, serverPixels = { server })
        assertTrue(run(interceptor).transformations.isEmpty())
        assertEquals(0, reads)
    }

    @Test fun `a RAW gets its curve, under the recipe`() {
        val recipe = object : Transformation() {
            override val cacheKey = "recipe"
            override suspend fun transform(input: coil3.Bitmap, size: Size) = input
        }
        val interceptor = ServerRawToneInterceptor(rawAt = { raw }, head = { _, _ -> jpeg() }, serverPixels = { server })
        val done = run(interceptor, request { transformations(recipe) })
        assertEquals(2, done.transformations.size)
        assertTrue(done.transformations[0] is CameraToneTransformation)
        assertEquals("recipe", done.transformations[1].cacheKey)
    }

    @Test fun `the file is read once for a photo`() {
        var reads = 0
        val interceptor = ServerRawToneInterceptor(rawAt = { raw }, head = { _, _ -> reads++; jpeg() }, serverPixels = { server })
        repeat(3) { run(interceptor) }
        assertEquals(1, reads)
    }

    @Test fun `a small JPEG further in is reached by a longer read`() {
        val sizes = ArrayList<Int>()
        val interceptor = ServerRawToneInterceptor(
            rawAt = { raw },
            head = { _, bytes ->
                sizes += bytes
                if (bytes <= 64 * 1024) ByteArray(bytes) else ByteArray(70_000) + jpeg() + ByteArray(bytes - 70_000 - jpeg().size)
            },
            serverPixels = { server },
        )
        assertEquals(1, run(interceptor).transformations.size)
        assertEquals(listOf(16 * 1024, 64 * 1024, 128 * 1024), sizes)
    }

    @Test fun `a photo with no small JPEG keeps the server's image and is not read again`() {
        var reads = 0
        val interceptor = ServerRawToneInterceptor(rawAt = { raw }, head = { _, bytes -> reads++; ByteArray(bytes) }, serverPixels = { server })
        assertTrue(run(interceptor).transformations.isEmpty())
        val afterFirst = reads
        assertTrue(run(interceptor).transformations.isEmpty())
        assertEquals(afterFirst, reads)
    }

    @Test fun `a server that fails is left alone for a while, and the image still shows`() {
        var reads = 0
        val other = LibraryRaw("raw-2", "sum")
        var next = raw
        val interceptor = ServerRawToneInterceptor(
            rawAt = { next },
            head = { _, _ -> reads++; throw IOException("unreachable") },
            serverPixels = { server },
        )
        assertTrue(run(interceptor).transformations.isEmpty())
        next = other
        val second = request { data("https://server.test/api/assets/raw-2/thumbnail?size=thumbnail") }
        assertTrue(run(interceptor, second).transformations.isEmpty())
        assertEquals(1, reads)
    }

    @Test fun `a develop asked for is not corrected`() {
        var reads = 0
        val interceptor = ServerRawToneInterceptor(rawAt = { raw }, head = { _, _ -> reads++; jpeg() }, serverPixels = { server })
        val done = run(interceptor, request { developedLibraryRaw("raw-1", "sum", download = true) })
        assertTrue(done.transformations.isEmpty())
        assertEquals(0, reads)
    }

    @Test fun `the curve changes the pixels it is given`() = runBlocking {
        val curve = requireNotNull(CameraToneCurve.between(server, requireNotNull(decodePixels(jpeg()))))
        val buffer = PixelBuffer(server.width, server.height, server.pixels.copyOf())
        curve.applyTo(buffer)
        assertTrue(buffer.pixels.indices.any { buffer.pixels[it] != server.pixels[it] })
    }
}
