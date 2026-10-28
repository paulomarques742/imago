package eu.studio742.imago.feature.editor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.net.toUri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.OffscreenRecipeRenderer
import eu.studio742.imago.core.render.toPixelBuffer
import eu.studio742.imago.core.render.toRenderParameters
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.reel_failed
import eu.studio742.imago.feature.editor.resources.reel_saved_gallery
import eu.studio742.imago.feature.editor.resources.reel_share_title
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun rememberReelPlatform(): ReelPlatform {
    val context = LocalContext.current.applicationContext
    val chooserTitle = stringResource(Res.string.reel_share_title)
    // Asked for when the export starts, which is when the notification has something to say. Refused,
    // the video is still made; it just does not announce it.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(context, chooserTitle) {
        AndroidReelPlatform(context, chooserTitle) {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * The video on Android: the editor's GPU pipeline for the photo, the device's H.264 encoder, the
 * gallery's IMAGO album and the system share menu. Only the application context is kept, since the
 * export outlives the screen.
 */
private class AndroidReelPlatform(
    private val context: Context,
    private val chooserTitle: String,
    private val askForNotifications: () -> Unit,
) : ReelPlatform {
    private val gallery = GalleryExporter(context)

    override fun exportStarted() {
        askForNotifications()
        ReelExportService.start(context)
    }

    override fun frameScale(): Float =
        if (encoderFor(avcFormat(REEL_WIDTH, REEL_HEIGHT, REEL_FPS, surfaceInput = true)) != null) 1f else 2f / 3f

    /**
     * The editor's GPU pipeline, drawing into memory: the same image the editor shows, many times
     * faster than the CPU render on a phone. A device that gives no OpenGL ES 3 context off screen
     * still gets its video, from the CPU.
     */
    override fun photoRenderer(
        photo: Bitmap,
        maxSide: Int,
        highPrecisionSource: eu.studio742.imago.core.render.FloatPixelBuffer?,
    ): ReelPhotoRenderer =
        runCatching { GpuReelPhotoRenderer(photo, maxSide, highPrecisionSource) }.getOrElse {
            highPrecisionSource?.let { FloatReelPhotoRenderer(it, maxSide) } ?: CpuReelPhotoRenderer(photo.toPixelBuffer(), maxSide)
        }

    override suspend fun encode(
        width: Int,
        height: Int,
        fps: Int,
        frameCount: Int,
        frame: (Int) -> ImageBitmap,
        onProgress: (Float) -> Unit,
    ): File {
        val output = File.createTempFile("imago-reel-", ".mp4", context.cacheDir)
        try {
            // The surface path hands the colour conversion to the GPU; the buffer path is there for
            // an encoder that will not give a surface an EGL context can draw on.
            val encoder = runCatching<ReelFrameSink> { SurfaceAvcEncoder(output, width, height, fps) }
                .getOrElse { BufferAvcEncoder(output, width, height, fps) }
            encoder.use {
                for (index in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    encoder.write(frame(index), index * 1_000_000L / fps)
                    onProgress((index + 1f) / frameCount)
                }
                encoder.finish(frameCount * 1_000_000L / fps)
            }
            return output
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    override suspend fun save(video: File, fileName: String): SavedReel {
        val uri = gallery.saveOriginal(video, fileName, isVideo = true, createdAt = Instant.now().toString())
        return SavedReel(uri.toString(), uiText(Res.string.reel_saved_gallery))
    }

    override val share: (SavedReel) -> Unit = { saved ->
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, saved.location.toUri())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private class GpuReelPhotoRenderer(
    private val photo: Bitmap,
    private val maxSide: Int,
    highPrecisionSource: eu.studio742.imago.core.render.FloatPixelBuffer?,
) : ReelPhotoRenderer {
    private val gpu = OffscreenRecipeRenderer(photo, highPrecisionSource)
    override val sourceWidth: Int get() = photo.width
    override val sourceHeight: Int get() = photo.height

    override fun render(recipe: EditRecipe, showMask: String?): ImageBitmap {
        val parameters = recipe.toRenderParameters(showMask = showMask)
        val overlay = if (showMask == null) -1 else parameters.masks.indexOfFirst { it.id == showMask }
        return gpu.render(parameters, maxSide, overlay).asImageBitmap()
    }

    override fun close() = gpu.close()
}

private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
private const val TIMEOUT_US = 10_000L

/**
 * The format asked of the encoder. BT.709 with limited range is what players assume for HD video;
 * the buffer path converts with that same matrix, and the surface path asks the encoder for it.
 */
private fun avcFormat(width: Int, height: Int, fps: Int, surfaceInput: Boolean): MediaFormat =
    MediaFormat.createVideoFormat(AVC, width, height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            if (surfaceInput) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            } else {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            },
        )
        setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    }

/** The name of an encoder that takes this format, or null: some refuse a portrait 1080 × 1920. */
private fun encoderFor(format: MediaFormat): String? =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)

/** Frames in, MP4 out, with every frame's time set by hand. */
private abstract class ReelFrameSink(output: File, width: Int, height: Int, fps: Int, surfaceInput: Boolean) : AutoCloseable {
    protected val codec: MediaCodec
    private val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false

    init {
        val format = avcFormat(width, height, fps, surfaceInput)
        val name = encoderFor(format) ?: throw LocalizedException(uiText(Res.string.reel_failed))
        codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (failure: Throwable) {
            codec.release()
            muxer.release()
            throw failure
        }
    }

    abstract fun write(frame: ImageBitmap, presentationUs: Long)

    abstract fun finish(endUs: Long)

    protected fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    // The codec's own setup travels in the format the muxer already has.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buffer != null && info.size > 0 && muxing) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        if (muxing) runCatching { muxer.stop() }
        muxer.release()
    }
}

/**
 * Frames drawn by the GPU onto the encoder's own input surface, each stamped with its time through
 * `eglPresentationTimeANDROID`: the render is slower than real time, and without the stamp a frame's
 * time would be when it was posted. The GPU also does the conversion to YUV.
 *
 * Everything runs on the calling thread, which holds the context while the video is made.
 */
private class SurfaceAvcEncoder(output: File, width: Int, height: Int, fps: Int) :
    ReelFrameSink(output, width, height, fps, surfaceInput = true) {
    private val inputSurface = codec.createInputSurface()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var uploaded: ImageBitmap? = null
    private val quad: ByteBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).apply {
        asFloatBuffer().put(QUAD)
    }

    init {
        try {
            setUpEgl()
            codec.start()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun write(frame: ImageBitmap, presentationUs: Long) {
        GLES20.glViewport(0, 0, frame.width, frame.height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        // A still frame comes back as the same bitmap: it is already on the GPU.
        if (frame !== uploaded) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame.asAndroidBitmap(), 0)
            uploaded = frame
        }
        GLES20.glUseProgram(program)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationUs * 1_000)
        check(EGL14.eglSwapBuffers(display, surface)) { "The frame did not reach the encoder" }
        drain(endOfStream = false)
    }

    override fun finish(endUs: Long) {
        codec.signalEndOfInputStream()
        drain(endOfStream = true)
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL did not initialise" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No recordable EGL configuration"
        }
        context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "No EGL context for the encoder" }
        surface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "No EGL surface on the encoder's input" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "The encoder's context did not become current" }
        program = linkProgram()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        val texel = GLES20.glGetAttribLocation(program, "aTexel")
        quad.position(8)
        GLES20.glVertexAttribPointer(texel, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(texel)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFrame"), 0)
    }

    private fun linkProgram(): Int {
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(it) }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(it) }
        }
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
        inputSurface.release()
        super.close()
    }

    private companion object {
        /** EGL_ANDROID_recordable: a configuration the encoder's surface accepts. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        /** Position and texel of each corner; the bitmap's first row is the top of the frame. */
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        const val VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aTexel;
            varying vec2 vTexel;
            void main() {
                vTexel = aTexel;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uFrame;
            varying vec2 vTexel;
            void main() {
                gl_FragColor = texture2D(uFrame, vTexel);
            }
        """
    }
}

/**
 * Frames converted to YUV here and fed as buffers: for an encoder that gives no surface to draw on.
 * Slower — the conversion and the copy run on the CPU — but it works wherever the codec does.
 */
private class BufferAvcEncoder(output: File, private val width: Int, private val height: Int, fps: Int) :
    ReelFrameSink(output, width, height, fps, surfaceInput = false) {
    private val argb = IntArray(width * height)
    private val yPlane = ByteArray(width * height)
    private val uPlane = ByteArray(width * height / 4)
    private val vPlane = ByteArray(width * height / 4)

    /** The frame the planes hold: a still frame comes back as the same bitmap and is not converted again. */
    private var converted: ImageBitmap? = null

    init {
        codec.start()
    }

    override fun write(frame: ImageBitmap, presentationUs: Long) {
        if (frame !== converted) {
            frame.readPixels(argb)
            argbToYuv709(argb, width, height, yPlane, uPlane, vPlane)
            converted = frame
        }
        val index = nextInput()
        val image = codec.getInputImage(index) ?: throw LocalizedException(uiText(Res.string.reel_failed))
        copyPlane(image.planes[0], yPlane, width, height)
        copyPlane(image.planes[1], uPlane, width / 2, height / 2)
        copyPlane(image.planes[2], vPlane, width / 2, height / 2)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, presentationUs, 0)
        drain(endOfStream = false)
    }

    override fun finish(endUs: Long) {
        codec.queueInputBuffer(nextInput(), 0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(endOfStream = true)
    }

    private fun nextInput(): Int {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            drain(endOfStream = false)
        }
    }
}

/**
 * One plane into the encoder's buffer, through its strides: the layout of a flexible buffer is the
 * encoder's choice, planar or with U and V interleaved.
 */
private fun copyPlane(plane: Image.Plane, source: ByteArray, width: Int, height: Int) {
    val buffer = plane.buffer
    for (row in 0 until height) {
        if (plane.pixelStride == 1) {
            buffer.position(row * plane.rowStride)
            buffer.put(source, row * width, width)
        } else {
            for (x in 0 until width) buffer.put(row * plane.rowStride + x * plane.pixelStride, source[row * width + x])
        }
    }
}
