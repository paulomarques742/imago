#!/usr/bin/env bash
# Rebuilds the ONNX Runtime in core/ml/prebuilt from source, for Android arm64-v8a and Windows x64.
#
# Why not the official packages: they enable Microsoft's 1DS telemetry by default, and the Android one
# adds network permissions and a content provider that starts it. --no_telemetry leaves all of that out.
# --include_ops_by_config keeps only the operators our models use. Graph optimisation adds operators of its
# own (FusedConv, FusedMatMul, and on x86 the NCHWc ones), so the config is generated from each model and
# from the optimised copies the official Python runtime saves at ORT_ENABLE_EXTENDED and ORT_ENABLE_ALL
# (SessionOptions.optimized_model_filepath), all in one folder:
#   python "$WORK/src/tools/python/create_reduced_build_config.py" --format ONNX MODELS_DIR required_operators.config
#
# Runs from Git Bash on Windows. Needs Python 3.12, CMake >= 3.28, Ninja, Visual Studio 2022 with C++,
# a JDK 17 in JAVA_HOME, and the Android SDK with NDK 29. Override the paths below through the environment.
# Each build takes about an hour on a 16-thread machine.
set -euo pipefail

ORT_VERSION=1.32.0
HERE="$(cd "$(dirname "$0")" && pwd)"
PREBUILT="$HERE/../prebuilt"
WORK="${ORT_WORK:-/c/tmp/ort}"
ANDROID_SDK="${ANDROID_SDK:-C:/tmp/ImmichRoom-toolchain/android-sdk}"
ANDROID_NDK="${ANDROID_NDK:-$ANDROID_SDK/ndk/29.0.14206865}"
PYTHON="${PYTHON:-python}"
VS_CMAKE="${VS_CMAKE:-/c/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/Common7/IDE/CommonExtensions/Microsoft/CMake}"
export PATH="$JAVA_HOME/bin:$VS_CMAKE/CMake/bin:$VS_CMAKE/Ninja:$PATH"

if [ ! -d "$WORK/src" ]; then
    git clone --depth 1 --branch "v$ORT_VERSION" https://github.com/microsoft/onnxruntime.git "$WORK/src"
fi
cd "$WORK/src"

# The unit tests do not compile against a reduced operator set (unused-variable errors under -Werror).
COMMON=(--config MinSizeRel --build_java --build_shared_lib --parallel --skip_tests --skip_submodule_sync
    --no_telemetry --include_ops_by_config "$HERE/required_operators.config"
    --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF)

"$PYTHON" tools/ci_build/build.py --build_dir "$WORK/build-android" "${COMMON[@]}" \
    --android --android_sdk_path "$ANDROID_SDK" --android_ndk_path "$ANDROID_NDK" \
    --android_abi arm64-v8a --android_api 26 --cmake_generator Ninja

"$PYTHON" tools/ci_build/build.py --build_dir "$WORK/build-windows" "${COMMON[@]}" \
    --cmake_generator "Visual Studio 17 2022"

# Android: the classes jar and the two native libraries out of the AAR.
AAR="$(find "$WORK/build-android/MinSizeRel/java/build/android/outputs/aar" -name '*release.aar' | head -1)"
UNPACKED="$WORK/aar"
rm -rf "$UNPACKED" && mkdir -p "$UNPACKED" && unzip -q "$AAR" -d "$UNPACKED"
if grep -q -i "telemetry\|INTERNET" "$UNPACKED/AndroidManifest.xml"; then
    echo "The AAR still declares telemetry or network access" >&2
    exit 1
fi
mkdir -p "$PREBUILT/android/jni/arm64-v8a" "$PREBUILT/desktop"
cp "$UNPACKED/classes.jar" "$PREBUILT/android/onnxruntime-$ORT_VERSION-imago.jar"
cp "$UNPACKED/jni/arm64-v8a/"*.so "$PREBUILT/android/jni/arm64-v8a/"

# Desktop: the jar carries the Windows DLLs as resources, and the runtime extracts them itself.
cp "$WORK/build-windows/MinSizeRel/java/build/libs/onnxruntime.jar" "$PREBUILT/desktop/onnxruntime-$ORT_VERSION-imago.jar"

cp LICENSE "$PREBUILT/LICENSE"
cp ThirdPartyNotices.txt "$PREBUILT/ThirdPartyNotices.txt"
ls -l "$PREBUILT"/*/ "$PREBUILT/android/jni/arm64-v8a/"
