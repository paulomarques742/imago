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
