package eu.studio742.imago.feature.composer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.asAndroidPath
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.videoTextureSampling
import eu.studio742.imago.core.composition.MAX_PAGE_SIDE
import eu.studio742.imago.core.composition.VideoFrameProjection
import kotlin.math.cos
import kotlin.math.sin

@androidx.annotation.OptIn(UnstableApi::class)
internal class CompositionVideoFrameEffect(
    private val video: CompositionElement.Video, private val projection: VideoFrameProjection,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = try {
        FrameProgram(context, useHdr, video, projection)
    } catch (error: Exception) { throw VideoFrameProcessingException(error) }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class FrameProgram(context: Context, useHdr: Boolean, private val video: CompositionElement.Video,
    private val projection: VideoFrameProjection,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(context, "shaders/imago_composer_frame.vert", "shaders/imago_composer_frame.frag")
    private var maskTexture = 0
    private val width = projection.width
    private val height = projection.height

    init { program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4) }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            val sampling = videoTextureSampling(inputWidth.toFloat(), inputHeight.toFloat(), projection.frameWidth, projection.frameHeight, video.crop)
            program.setFloatsUniform("uScale", floatArrayOf(sampling.scaleX, sampling.scaleY))
            program.setFloatsUniform("uOffset", floatArrayOf(sampling.offsetX, sampling.offsetY))
            program.setFloatsUniform("uWindowOrigin", floatArrayOf(projection.left.toFloat(), projection.top.toFloat()))
            program.setFloatsUniform("uWindowSize", floatArrayOf(width.toFloat(), height.toFloat()))
            program.setFloatsUniform("uCentre", floatArrayOf(projection.centreX, projection.centreY))
            program.setFloatsUniform("uFrameSize", floatArrayOf(projection.frameWidth, projection.frameHeight))
            val angle = Math.toRadians(video.transform.rotationDegrees.toDouble())
            program.setFloatsUniform("uRotation", floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat()))
            program.setFloatsUniform("uMirror", floatArrayOf(if (video.transform.mirrorHorizontal) -1f else 1f, if (video.transform.mirrorVertical) -1f else 1f))
            program.setFloatUniform("uVisible", if (projection.visible) 1f else 0f)
            if (maskTexture == 0) {
                val limit = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
                require(width <= limit && height <= limit && width.toLong() * height <= MAX_PAGE_SIDE.toLong() * MAX_PAGE_SIDE) {
                    "The video frame exceeds the composition texture limit."
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    if (projection.visible) Canvas(bitmap).apply {
                        translate(-projection.left.toFloat(), -projection.top.toFloat())
                        rotate(video.transform.rotationDegrees, projection.centreX, projection.centreY)
                        scale(if (video.transform.mirrorHorizontal) -1f else 1f, if (video.transform.mirrorVertical) -1f else 1f, projection.centreX, projection.centreY)
                        translate(projection.centreX - projection.frameWidth / 2f, projection.centreY - projection.frameHeight / 2f)
                        drawPath(mediaCutoutPath(video.frame, ComposeSize(projection.frameWidth, projection.frameHeight)).asAndroidPath(),
                            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
                    }
                    maskTexture = GlUtil.createTexture(bitmap)
                } finally { bitmap.recycle() }
            }
            return Size(width, height)
        } catch (error: Exception) { throw VideoFrameProcessingException(error) }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uVideo", inputTexId, 0)
            program.setSamplerTexIdUniform("uMask", maskTexture, 1)
            program.bindAttributesAndUniforms()
            val blending = GLES20.glIsEnabled(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_BLEND)
            try { GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4) }
            finally { if (blending) GLES20.glEnable(GLES20.GL_BLEND) }
            GlUtil.checkGlError()
        } catch (error: Exception) { throw VideoFrameProcessingException(error, presentationTimeUs) }
    }

    override fun release() {
        try {
            program.delete()
            if (maskTexture != 0) GlUtil.deleteTexture(maskTexture)
        } catch (error: Exception) { throw VideoFrameProcessingException(error) }
        finally { super.release() }
    }
}
