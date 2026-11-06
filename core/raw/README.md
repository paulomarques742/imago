# RAW decoding

The native entry point is shared by Android and Windows. LibRaw identifies file contents;
there is no extension, camera brand, or DNG-only allowlist in the decoder.

`RawDecoder.decode` reads an original on a worker thread and returns oriented, camera-balanced,
scene-linear sRGB in IEEE binary16 storage, including negative and above-white values. `RawImage.sample`
decodes each stored component to a Kotlin Float. An unsupported format returns null; a decoding failure
throws `RawDecodeException`. Thread interruption cancels native processing through LibRaw's
progress callback. Each decode owns its LibRaw instance and closes the input before returning.

`RawDecoder.decodePreview(file, maxSide)` uses half-size development and reduces oriented,
scene-linear channels in the native bridge before allocating the Kotlin FP16 buffer. The long
side is bounded by the request, with no upscaling. Full-resolution exports keep using `decode`.
Developments share an interruptible queue so image grids cannot accumulate concurrent LibRaw
sensor and demosaic buffers. Cancellation also removes a worker waiting for its turn.
This bounds preview storage on the JVM; LibRaw still needs native memory to unpack the sensor.

White balance uses LibRaw's gain normalization that preserves sensor headroom. Camera channels
stay unconverted until IMAGO applies the camera-to-sRGB matrix in floating point; display clipping
comes after exposure and white-balance adjustments in the shared editor pipeline. Binary16 storage
matches the Android GPU targets and halves the full-resolution working image's heap requirement.
It is floating-point storage, not 16-bit integer precision. Sensor-clipped detail cannot be restored.

`identify` reads header metadata for the desktop folder index without developing the sensor.
The editor, recipe thumbnails, Coil library previews, full-resolution JPEG export and edit videos
use the same source and processing. Recipes record the development engine and process version;
unknown engines are rejected rather than silently interpreted with the current decoder.

## DNG opcode lists

Without the Adobe DNG SDK, LibRaw reads `OpcodeList1`, `OpcodeList2` and `OpcodeList3` as raw bytes into
`imgdata.color.dng_levels.rawopcodes` and applies none of them; it only uses a `MapPolynomial` from
`OpcodeList2` to build the tone curve of lossy DNGs. Lens shading (`GainMap`), distortion
(`WarpRectilinear`) and vignetting (`FixVignetteRadial`) that phones write into their DNGs are therefore
not part of the current development. `tools/dng_opcodes.py FILE_OR_FOLDER` lists the opcode lists,
colour matrices and as-shot neutral of DNG files without reading sensor data, using only the Python
standard library.

LibRaw also reads a DNG's `BaselineExposure` without applying it. `decode` and `decodePreview` scale
the developed values by 2^BaselineExposure when asked (`baselineExposure = true`) and always report the
value found in `RawImage.baselineExposure`. Whether to apply it is the recipe's choice: recipes from
before process 20 were edited without it.

## Lens corrections from the file

Sony writes the corrections it applies to its own JPEG into the ARW: `DistortionCorrParams` (0x7037),
`ChromaticAberrationCorrParams` (0x7035) and `VignettingCorrParams` (0x7032), each a count followed by
signed 16-bit knots spaced evenly from the centre to the half-diagonal. LibRaw does not read them.
`EmbeddedLensCorrection.read` finds them in IFD0 or its sub-IFDs without reading image data, using the
scales of darktable's "embedded metadata" correction (version 2): distortion `1 + d·2⁻¹⁴`, aberration
`1 + c·2⁻²¹` on top of it, vignetting gain `2^(½ − 2^(v·2⁻¹³ − 1))`, and the zoom that keeps the frame filled.

`decode(..., lensCorrection = true)` applies them inside the native development, with no second image in
memory: the aberration moves a Bayer sensor's red and blue sites before demosaicing, and the distortion and
vignetting resample the camera channels while they are copied out. After demosaicing the aberration can no
longer be taken out cleanly, because the interpolation borrows red's and blue's detail from green. A half-size
development has no demosaicing, so there each channel is moved as it is copied out. On an
ILCE-6700 ARW with a Tamron 18-300 at 18 mm, measured against the JPEG the file carries, the geometry
residual went from 10.8 px to 1.0 px (at 2048 px) and the red and blue fringes from up to 2.6 px to under
0.4 px (at full resolution). `RawImage.fileLensCorrection` says whether the file has them, applied or not.

Other makers' tags (Fujifilm, OM/Olympus, Panasonic) are not read yet.

## Native builds

Android uses the pinned NDK and CMake versions in `build.gradle.kts`, packaged through the render
and data module dependencies. Windows uses Visual Studio
2022 C++ Build Tools and CMake; set `-Pimago.cmake=<executable>` to override CMake discovery.
The desktop native library is packaged as a classpath resource and loaded from a temporary file.
Run `:core:raw:desktopTest` for native integration tests, including an in-memory-generated Bayer
DNG, Unicode filenames, orientation, half-size development, and file-handle cleanup.

The optional editor integration test accepts `-Pimago.raw.samples=<folder>` and develops real
external DNG, CR3 and RAF samples at full resolution, writing JPEGs next to the originals. Large
camera samples stay outside version control. Device performance and maximum practical resolution
must be measured separately; successful native compilation is not a hardware validation.

## Third-party source and license

LibRaw 0.22.2 is distributed here as the unmodified official source archive, with its original
copyright and license notices. IMAGO uses the CDDL 1.0 licensing option. The archive SHA-256 is
verified by CMake before extraction. Source: <https://www.libraw.org/download>.
Original source archives and notices are included at the Android assets root and in desktop
resources under `licences/raw/`.

The current build includes the built-in decoders and demosaicers, including X-Trans, and pins
zlib 1.3.2 on both platforms, without optional RawSpeed, Adobe DNG SDK, GoPro SDK or LCMS
dependencies. Consequently, capabilities depend on this build configuration,
not just on LibRaw's general camera list. `supportedCameras` reports that list and is not a promise
that every compression mode of each camera is available. Additional optional dependencies need
their own source, license and packaging review before enabling them.

zlib sources are included unmodified from <https://zlib.net/>, with the original zlib license.
