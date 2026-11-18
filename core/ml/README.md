# On-device segmentation

`MaskGenerator.generate` runs a segmentation model on an oriented photo and returns its raw output as an
8-bit `GeneratedMask`: the part of a subject, background or sky mask that a recipe stores. The model runs
once per mask on the photo it was made for; refining the mask to the photo's resolution is the renderer's
job, from this and the original.

| `MaskModel` | Weights | Input | Output |
|---|---|---|---|
| `SUBJECT` | u2netp (U²-Net, Apache 2.0) | 320 × 320 | 320 × 320 saliency, stretched to 0..1 |
| `SKY` | PP-MobileSeg Base, ADE20K (PaddleSeg, Apache 2.0) | short side 768, multiples of 32 | sky probability at 1/8 |

The sky model is PaddleSeg's export with the final upsampling cut off and a softmax and slice of class 2
appended, so the runtime never holds 150 classes at full input resolution. Weights are not in this
repository: they are downloaded on first use.

Both models take ImageNet-normalised NCHW floats. The resampling to the model's input is an exact box
filter in common Kotlin (`ModelInput.kt`) rather than a platform scaler, so Android and desktop feed the
model the same numbers.

## The runtime

`prebuilt/` holds ONNX Runtime 1.32.0 built from source by `tools/build-onnxruntime.sh`, instead of the
official packages, for two reasons:

- the official Android package enables Microsoft's 1DS telemetry by default, registers a content provider
  that starts it, and adds the `INTERNET` and `ACCESS_NETWORK_STATE` permissions. This build passes
  `--no_telemetry`, which leaves the Java telemetry sources and that manifest out;
- it registers only the operators in `tools/required_operators.config`, generated from the two models and
  from their optimised copies, since graph optimisation fuses operators into ones the models do not
  contain (`FusedConv`, `FusedMatMul`, and on x86 the NCHWc layout). A model that needs another operator
  fails when its session is created, until the config is regenerated and the runtime rebuilt.

Android ships `arm64-v8a` only. On other ABIs `MaskGenerator.isAvailable` is false and the tools that need
it are left out. Desktop ships Windows x64 inside the jar, which the runtime extracts and loads itself.

## Tests

`ModelInputTest` covers the resampling and quantisation. The parity tests run our runtime on three sample
photos and compare with masks the official runtime produced from the same input
(`src/testFixtures/resources/masks`, mean difference at most 0.003). They need the model files:

```bash
./gradlew :core:ml:desktopTest -Pimago.ml.models=C:/path/to/models
./gradlew :core:ml:connectedDebugAndroidTest -Pimago.ml.models=C:/path/to/models
```
