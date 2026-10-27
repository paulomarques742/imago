#include <jni.h>
#include <libraw/libraw.h>
#include <limits>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace {
unsigned short halfFloat(float value) {
    uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    unsigned sign = (bits >> 16) & 32768;
    int exponent = static_cast<int>((bits >> 23) & 255) - 127;
    unsigned mantissa = bits & 8388607;
    if (std::isnan(value)) return 0;
    if (exponent > 15) return static_cast<unsigned short>(sign | 31743);
    if (exponent < -25) return static_cast<unsigned short>(sign);
    unsigned shift = exponent < -14 ? static_cast<unsigned>(-exponent - 1) : 13;
    unsigned significand = exponent < -14 ? mantissa | 8388608 : mantissa;
    unsigned rounded = significand >> shift;
    unsigned remainder = significand & ((1u << shift) - 1);
    unsigned halfway = 1u << (shift - 1);
    if (remainder > halfway || (remainder == halfway && (rounded & 1))) ++rounded;
    unsigned magnitude = exponent < -14 ? rounded : (static_cast<unsigned>(exponent + 15) << 10) + rounded;
    return static_cast<unsigned short>(sign | std::min(magnitude, 31743u));
}

struct Cancellation {
    JNIEnv *env;
    jobject thread;
    jmethodID interrupted;
};

int progress(void *context, LibRaw_progress, int, int) {
    auto *state = static_cast<Cancellation *>(context);
    return state->env->CallBooleanMethod(state->thread, state->interrupted) ? 1 : 0;
}

void failure(JNIEnv *env, int code) {
    if (env->ExceptionCheck()) return;
    if (code == LIBRAW_CANCELLED_BY_CALLBACK) {
        env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"), "RAW decoding interrupted.");
        return;
    }
    auto cls = env->FindClass("eu/studio742/imago/core/raw/RawDecodeException");
    auto ctor = env->GetMethodID(cls, "<init>", "(I)V");
    env->Throw(static_cast<jthrowable>(env->NewObject(cls, ctor, code)));
}

// XYZ to camera: LibRaw's table for a camera it knows, or, in a DNG, the file's own ColorMatrix (times its
// CameraCalibration), preferring the daylight one as LibRaw does — it leaves cam_xyz empty for DNGs.
void cameraToXyz(const libraw_colordata_t &color, int colors, float out[4][3]) {
    bool fromTable = false;
    for (int c = 0; c < colors; ++c)
        for (int j = 0; j < 3; ++j) {
            out[c][j] = color.cam_xyz[c][j];
            fromTable = fromTable || out[c][j] != 0.f;
        }
    if (fromTable) return;
    int chosen = -1;
    for (int pass = 0; pass < 3 && chosen < 0; ++pass)
        for (int i = 0; i < 2 && chosen < 0; ++i) {
            const auto &dng = color.dng_color[i];
            bool present = false;
            for (int c = 0; c < colors; ++c) for (int j = 0; j < 3; ++j) present = present || dng.colormatrix[c][j] != 0.f;
            if (!present) continue;
            const int light = dng.illuminant;
            const bool daylight = light == LIBRAW_WBI_Daylight || light == LIBRAW_WBI_D55 || light == LIBRAW_WBI_D75 ||
                light == LIBRAW_WBI_D50 || light == LIBRAW_WBI_Flash;
            if ((pass == 0 && light == LIBRAW_WBI_D65) || (pass == 1 && daylight) || pass == 2) chosen = i;
        }
    if (chosen < 0) return;
    const auto &dng = color.dng_color[chosen];
    bool calibrated = false;
    for (int c = 0; c < colors; ++c) calibrated = calibrated || dng.calibration[c][c] != 0.f;
    for (int c = 0; c < colors; ++c)
        for (int j = 0; j < 3; ++j) {
            float value = 0.f;
            for (int k = 0; k < colors; ++k)
                value += (calibrated ? dng.calibration[c][k] : (c == k ? 1.f : 0.f)) * dng.colormatrix[k][j];
            out[c][j] = value;
        }
}

int openPath(JNIEnv *env, LibRaw &decoder, jstring path) {
#ifdef _WIN32
    const auto chars = env->GetStringChars(path, nullptr);
    if (!chars) return LIBRAW_IO_ERROR;
    std::wstring filename(reinterpret_cast<const wchar_t *>(chars), env->GetStringLength(path));
    env->ReleaseStringChars(path, chars);
    return decoder.open_file(filename.c_str());
#else
    auto stringClass = env->FindClass("java/lang/String");
    auto utf8 = env->NewStringUTF("UTF-8");
    auto encoded = static_cast<jbyteArray>(env->CallObjectMethod(path,
        env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B"), utf8));
    if (env->ExceptionCheck()) return LIBRAW_IO_ERROR;
    std::vector<char> filename(static_cast<size_t>(env->GetArrayLength(encoded)) + 1, '\0');
    env->GetByteArrayRegion(encoded, 0, env->GetArrayLength(encoded), reinterpret_cast<jbyte *>(filename.data()));
    return decoder.open_file(filename.data());
#endif
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_studio742_imago_core_raw_NativeRaw_version(JNIEnv *env, jobject) {
    return env->NewStringUTF(LibRaw::version());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_eu_studio742_imago_core_raw_NativeRaw_cameras(JNIEnv *env, jobject) {
    const auto cameras = LibRaw::cameraList();
    const auto count = LibRaw::cameraCount();
    auto result = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    if (!result) return nullptr;
    for (int i = 0; i < count; ++i) {
        auto name = env->NewStringUTF(cameras[i]);
        env->SetObjectArrayElement(result, i, name);
        env->DeleteLocalRef(name);
    }
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_studio742_imago_core_raw_NativeRaw_identify(JNIEnv *env, jobject, jstring path) {
    try {
        LibRaw decoder;
        decoder.imgdata.rawparams.max_raw_memory_mb = 512;
        int code = openPath(env, decoder, path);
        if (env->ExceptionCheck()) return nullptr;
        if (code == LIBRAW_FILE_UNSUPPORTED || code == LIBRAW_NOT_IMPLEMENTED) return nullptr;
        if (code != LIBRAW_SUCCESS) { failure(env, code); return nullptr; }
        const auto &sizes = decoder.imgdata.sizes;
        auto cls = env->FindClass("eu/studio742/imago/core/raw/RawInfo");
        auto ctor = env->GetMethodID(cls, "<init>", "(IILjava/lang/String;Ljava/lang/String;)V");
        bool rotated = (sizes.flip & 4) != 0;
        return env->NewObject(cls, ctor, static_cast<jint>(rotated ? sizes.height : sizes.width),
            static_cast<jint>(rotated ? sizes.width : sizes.height), env->NewStringUTF(decoder.imgdata.idata.make),
            env->NewStringUTF(decoder.imgdata.idata.model));
    } catch (const std::bad_alloc &) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Not enough memory to identify the RAW image.");
    } catch (...) { failure(env, LIBRAW_UNSPECIFIED_ERROR); }
    return nullptr;
}

// The JPEG the camera rendered and stored in the file, as it is; null when there is none, or when the
// file stores it as a bitmap or above the size bound.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_eu_studio742_imago_core_raw_NativeRaw_embeddedJpeg(JNIEnv *env, jobject, jstring path) {
    try {
        LibRaw decoder;
        decoder.imgdata.rawparams.max_raw_memory_mb = 512;
        int code = openPath(env, decoder, path);
        if (env->ExceptionCheck() || code != LIBRAW_SUCCESS) return nullptr;
        if (decoder.unpack_thumb() != LIBRAW_SUCCESS) return nullptr;
        std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> thumb(
            decoder.dcraw_make_mem_thumb(&code), LibRaw::dcraw_clear_mem);
        if (!thumb || code != LIBRAW_SUCCESS || thumb->type != LIBRAW_IMAGE_JPEG) return nullptr;
        if (thumb->data_size == 0 || thumb->data_size > 64u * 1024u * 1024u) return nullptr;
        auto bytes = env->NewByteArray(static_cast<jsize>(thumb->data_size));
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(thumb->data_size), reinterpret_cast<const jbyte *>(thumb->data));
        return bytes;
    } catch (const std::bad_alloc &) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_studio742_imago_core_raw_NativeRaw_decode(JNIEnv *env, jobject, jstring path, jboolean halfSize, jint maxSide,
    jboolean baselineExposure) {
    try {
        LibRaw decoder;
        auto threadClass = env->FindClass("java/lang/Thread");
        auto thread = env->CallStaticObjectMethod(threadClass, env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;"));
        Cancellation cancel{env, thread, env->GetMethodID(threadClass, "isInterrupted", "()Z")};
        decoder.set_progress_handler(progress, &cancel);
        decoder.imgdata.rawparams.max_raw_memory_mb = 512;
        int code = openPath(env, decoder, path);
        if (env->ExceptionCheck()) return nullptr;
        if (code == LIBRAW_FILE_UNSUPPORTED || code == LIBRAW_NOT_IMPLEMENTED) return nullptr;
        if (code != LIBRAW_SUCCESS) { failure(env, code); return nullptr; }
        auto &params = decoder.imgdata.params;
        params.output_bps = 16;
        // Keep balanced camera channels until the final matrix can run in floating point.
        // highlight=1 normalizes gains by their maximum, avoiding clipping during white balance.
        params.output_color = 0;
        params.highlight = 1;
        params.gamm[0] = params.gamm[1] = 1.0;
        params.no_auto_bright = 1;
        params.use_camera_wb = 1;
        params.adjust_maximum_thr = 0;
        params.half_size = halfSize ? 1 : 0;
        code = decoder.unpack();
        if (code == LIBRAW_SUCCESS) code = decoder.dcraw_process();
        if (code != LIBRAW_SUCCESS) { failure(env, code); return nullptr; }
        std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
            decoder.dcraw_make_mem_image(&code), LibRaw::dcraw_clear_mem);
        if (!image || code != LIBRAW_SUCCESS) { failure(env, code); return nullptr; }
        const size_t pixelCount = static_cast<size_t>(image->width) * image->height;
        if (image->type != LIBRAW_IMAGE_BITMAP || image->bits != 16 ||
            (image->colors < 1 || image->colors > 4 || image->colors == 2) ||
            pixelCount == 0 || image->data_size != pixelCount * image->colors * 2) {
            failure(env, LIBRAW_DATA_ERROR); return nullptr;
        }
        const float scale = maxSide > 0 ? std::min(1.f, static_cast<float>(maxSide) / std::max(image->width, image->height)) : 1.f;
        const unsigned width = std::max(1l, std::lround(image->width * scale));
        const unsigned height = std::max(1l, std::lround(image->height * scale));
        const size_t count = static_cast<size_t>(width) * height * 3;
        if (count > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            failure(env, LIBRAW_DATA_ERROR); return nullptr;
        }
        if (progress(&cancel, LIBRAW_PROGRESS_LOAD_RAW, 0, 1)) {
            failure(env, LIBRAW_CANCELLED_BY_CALLBACK); return nullptr;
        }
        auto pixels = env->NewShortArray(static_cast<jsize>(count));
        if (!pixels) return nullptr;
        const auto &color = decoder.imgdata.color;
        float minGain = 1.f;
        for (unsigned c = 0; c < image->colors; ++c)
            if (color.pre_mul[c] > 0.f && std::isfinite(color.pre_mul[c])) minGain = std::min(minGain, color.pre_mul[c]);
        // LibRaw reads a DNG's BaselineExposure without applying it; -999 means the file has none. The
        // bound keeps a corrupt tag from overflowing binary16.
        const float baseline = color.dng_levels.baseline_exposure;
        const float foundBaseline = std::isfinite(baseline) && baseline > -100.f ? std::clamp(baseline, -10.f, 10.f) : 0.f;
        const float exposure = baselineExposure ? std::exp2(foundBaseline) : 1.f;
        const float normalization = exposure / (65535.f * minGain);
        std::vector<jshort> row(static_cast<size_t>(width) * 3);
        const auto source = reinterpret_cast<const unsigned short *>(image->data);
        auto channel = [&](unsigned x, unsigned y, unsigned c) {
            const auto pixel = source + (static_cast<size_t>(y) * image->width + x) * image->colors;
            if (image->colors == 1) return pixel[0] * exposure / 65535.f;
            float value = 0.f;
            for (unsigned cameraChannel = 0; cameraChannel < image->colors; ++cameraChannel)
                value += color.rgb_cam[c][cameraChannel] * pixel[cameraChannel];
            return value * normalization;
        };
        for (unsigned y = 0; y < height; ++y) {
            if (progress(&cancel, LIBRAW_PROGRESS_CONVERT_RGB, y, height)) {
                failure(env, LIBRAW_CANCELLED_BY_CALLBACK); return nullptr;
            }
            for (unsigned x = 0; x < width; ++x) {
                for (unsigned c = 0; c < 3; ++c) {
                    float value;
                    if (scale == 1.f) {
                        value = channel(x, y, c);
                    } else {
                        // Sample scene-linear channels before FP16 storage, never an embedded JPEG.
                        const float sx = std::clamp((x + .5f) / scale - .5f, 0.f, image->width - 1.f);
                        const float sy = std::clamp((y + .5f) / scale - .5f, 0.f, image->height - 1.f);
                        const auto x0 = static_cast<unsigned>(sx), y0 = static_cast<unsigned>(sy);
                        const auto x1 = std::min(x0 + 1, image->width - 1u), y1 = std::min(y0 + 1, image->height - 1u);
                        const float fx = sx - x0, fy = sy - y0;
                        const float top = channel(x0, y0, c) * (1.f - fx) + channel(x1, y0, c) * fx;
                        const float bottom = channel(x0, y1, c) * (1.f - fx) + channel(x1, y1, c) * fx;
                        value = top * (1.f - fy) + bottom * fy;
                    }
                    row[x * 3 + c] = static_cast<jshort>(halfFloat(value));
                }
            }
            env->SetShortArrayRegion(pixels, static_cast<jsize>(y * row.size()), static_cast<jsize>(row.size()), row.data());
            if (env->ExceptionCheck()) return nullptr;
        }
        // A light's XYZ to the colour it takes in this development: the camera's response (cam_xyz),
        // the white balance LibRaw applied (pre_mul, the as-shot multipliers once processed) and the
        // matrix to sRGB. White balance in kelvin is read through it; up to a scale, which it ignores.
        jfloatArray xyzToDeveloped = nullptr;
        const int cameraColors = decoder.imgdata.idata.colors;
        float camXyz[4][3] = {};
        if (cameraColors >= 3 && cameraColors <= 4) cameraToXyz(color, cameraColors, camXyz);
        if (cameraColors >= 3 && cameraColors <= 4) {
            float matrix[9] = {};
            bool known = false;
            for (int row = 0; row < 3; ++row)
                for (int column = 0; column < 3; ++column) {
                    float value = 0.f;
                    for (int c = 0; c < cameraColors; ++c)
                        value += color.rgb_cam[row][c] * color.pre_mul[c] * camXyz[c][column];
                    matrix[row * 3 + column] = value;
                    known = known || (std::isfinite(value) && value != 0.f);
                }
            const float determinant =
                matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
                matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
                matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6]);
            if (known && std::isfinite(determinant) && std::fabs(determinant) > 1e-12f) {
                xyzToDeveloped = env->NewFloatArray(9);
                if (xyzToDeveloped) env->SetFloatArrayRegion(xyzToDeveloped, 0, 9, matrix);
            }
        }
        auto cls = env->FindClass("eu/studio742/imago/core/raw/RawImage");
        auto ctor = env->GetMethodID(cls, "<init>", "(II[SLjava/lang/String;Ljava/lang/String;F[F)V");
        return env->NewObject(cls, ctor, static_cast<jint>(width), static_cast<jint>(height), pixels,
            env->NewStringUTF(decoder.imgdata.idata.make), env->NewStringUTF(decoder.imgdata.idata.model),
            static_cast<jfloat>(foundBaseline), xyzToDeveloped);
    } catch (const std::bad_alloc &) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Not enough memory to decode the RAW image.");
    } catch (...) {
        failure(env, LIBRAW_UNSPECIFIED_ERROR);
    }
    return nullptr;
}
