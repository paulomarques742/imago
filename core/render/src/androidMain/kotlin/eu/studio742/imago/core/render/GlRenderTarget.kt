package eu.studio742.imago.core.render

import android.opengl.GLES30

/**
 * A colour texture with its FBO.
 *
 * [ensure] only reallocates when the format or the size changes. The pyramid is rebuilt every frame
 * and allocating textures within the 16 ms budget is not an option.
 */
internal class GlRenderTarget {
    var texture = 0
        private set
    var framebuffer = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    private var halfFloat = true
    private var mipmapped = false

    /**
     * State determined at allocation time. `glCheckFramebufferStatus` forces the driver to synchronise
     * and has no place on a path that runs every frame; the format can only stop being renderable when
     * it changes, and then we go through [ensure] again.
     */
    var isComplete = false
        private set

    val texelWidth: Float get() = 1f / width.coerceAtLeast(1)
    val texelHeight: Float get() = 1f / height.coerceAtLeast(1)

    fun ensure(
        width: Int,
        height: Int,
        halfFloat: Boolean = true,
        mipmapped: Boolean = false,
    ): GlRenderTarget {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val matches = texture != 0 &&
            safeWidth == this.width &&
            safeHeight == this.height &&
            halfFloat == this.halfFloat &&
            mipmapped == this.mipmapped
        if (matches) return this

        release()
        this.width = safeWidth
        this.height = safeHeight
        this.halfFloat = halfFloat
        this.mipmapped = mipmapped

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            if (halfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8,
            safeWidth,
            safeHeight,
            0,
            GLES30.GL_RGBA,
            if (halfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmapped) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (mipmapped) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        val buffers = IntArray(1)
        GLES30.glGenFramebuffers(1, buffers, 0)
        framebuffer = buffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            0,
        )
        isComplete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return this
    }

    fun bindAsTarget() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    /** Level `mipmapLevels() - 3` has about 4×4 texels, which is what the atmospheric light reads. */
    fun mipmapLevels(): Int {
        var size = maxOf(width, height)
        var levels = 1
        while (size > 1) {
            size /= 2
            levels++
        }
        return levels
    }

    fun generateMipmaps() {
        if (texture == 0) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    fun release() {
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        }
        if (texture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
        forget()
    }

    /**
     * Forgets the handles without calling GL. It is what is used when the context was recreated: the
     * old names are no longer there and the new context may have reassigned them to other objects, so
     * deleting them would destroy someone else's resources.
     */
    fun forget() {
        framebuffer = 0
        texture = 0
        width = 0
        height = 0
        isComplete = false
    }
}
