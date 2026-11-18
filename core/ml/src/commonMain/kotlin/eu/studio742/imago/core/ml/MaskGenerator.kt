package eu.studio742.imago.core.ml

/**
 * Runs the segmentation models on the device, through the ONNX Runtime build in `prebuilt/`.
 *
 * The build has no telemetry and only the operators of [MaskModel]'s models; see the module README.
 */
object MaskGenerator {
    /**
     * Whether the runtime loads on this device. It is false on ABIs the build does not cover, and then the
     * tools that need it are left out of the interface.
     */
    val isAvailable: Boolean by lazy { runtimeLoads() }

    /**
     * Runs [model] from the file at [modelPath] on [image]. Slow and memory-hungry (hundreds of MB at
     * peak): call it once per mask, off the main thread. The session is closed before returning.
     */
    fun generate(model: MaskModel, modelPath: String, image: RgbImage): GeneratedMask {
        val (width, height) = model.inputSize(image.width, image.height)
        val (outputWidth, outputHeight, output) = runModel(modelPath, modelInput(image, width, height), width, height)
        return generatedMask(model, outputWidth, outputHeight, output)
    }
}

internal expect fun runtimeLoads(): Boolean

/** Runs the model on NCHW input of [width] × [height] and returns its single-channel output and size. */
internal expect fun runModel(modelPath: String, input: FloatArray, width: Int, height: Int): Triple<Int, Int, FloatArray>
