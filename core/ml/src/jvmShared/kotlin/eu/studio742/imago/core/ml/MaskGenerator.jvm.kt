package eu.studio742.imago.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

// Compiled into both androidMain and desktopMain: the ONNX Runtime Java API is the same on both, and only
// how its native libraries are packaged differs (jniLibs on Android, resources inside the jar on desktop).

internal actual fun runtimeLoads(): Boolean = try {
    OrtEnvironment.getEnvironment()
    true
} catch (_: LinkageError) {
    false
}

internal actual fun runModel(modelPath: String, input: FloatArray, width: Int, height: Int): Triple<Int, Int, FloatArray> {
    val env = OrtEnvironment.getEnvironment()
    OrtSession.SessionOptions().use { options ->
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(modelPath, options).use { session ->
            val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    val output = result.get(0) as OnnxTensor
                    val dims = output.info.shape
                    val buffer = output.floatBuffer
                    val values = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    return Triple(dims[3].toInt(), dims[2].toInt(), values)
                }
            }
        }
    }
}
