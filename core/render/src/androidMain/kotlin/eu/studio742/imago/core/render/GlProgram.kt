package eu.studio742.imago.core.render

import android.opengl.GLES30

/**
 * A linked program, with the locations of its uniforms cached.
 *
 * `glGetUniformLocation` takes a string and crosses JNI. With five programs and a few dozen uniforms,
 * resolving them every frame would be constant work within the 16 ms budget to discover nothing new:
 * the locations do not change while the program lives.
 */
internal class GlProgram(vertexSource: String, fragmentSource: String) {
    val id: Int = link(vertexSource, fragmentSource)
    private val locations = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(id)

    fun location(name: String): Int = locations.getOrPut(name) {
        GLES30.glGetUniformLocation(id, name)
    }

    fun float(name: String, value: Float) = GLES30.glUniform1f(location(name), value)

    fun int(name: String, value: Int) = GLES30.glUniform1i(location(name), value)

    fun boolean(name: String, value: Boolean) = int(name, if (value) 1 else 0)

    fun vec2(name: String, x: Float, y: Float) = GLES30.glUniform2f(location(name), x, y)

    fun vec3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(location(name), x, y, z)

    /** Row-major, as [Homography.values] gives it: GL transposes it into its column order. */
    fun mat3(name: String, rowMajor: FloatArray) = GLES30.glUniformMatrix3fv(location(name), 1, true, rowMajor, 0)

    fun floats(name: String, count: Int, values: FloatArray) =
        GLES30.glUniform1fv(location("$name[0]"), count, values, 0)

    fun vec3s(name: String, count: Int, values: FloatArray) =
        GLES30.glUniform3fv(location("$name[0]"), count, values, 0)

    fun vec4(name: String, values: FloatArray) =
        GLES30.glUniform4f(location(name), values[0], values[1], values[2], values[3])

    fun ints(name: String, count: Int, values: IntArray) =
        GLES30.glUniform1iv(location("$name[0]"), count, values, 0)

    fun vec4s(name: String, count: Int, values: FloatArray) =
        GLES30.glUniform4fv(location("$name[0]"), count, values, 0)

    fun sampler(name: String, texture: Int, unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        int(name, unit)
    }

    private fun link(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also { program ->
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(program) }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
        }
    }

    private fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
    }
}

/**
 * Half Gaussian kernel already in the format the blur shader consumes, with the vector padded to the
 * size declared in GLSL so it can be sent as is.
 */
internal class BlurKernel(sigma: Float) {
    private val weights = PhotoEffects.halfGaussianKernel(sigma)
    val radius = weights.lastIndex
    val padded = FloatArray(PhotoEffects.MAX_BLUR_RADIUS + 1).also(weights::copyInto)
}
