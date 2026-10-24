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

extern "C" JNIEXPORT jobject JNICALL
Java_eu_studio742_imago_core_raw_NativeRaw_decode(JNIEnv *env, jobject, jstring path, jboolean halfSize) {
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
        const size_t count = pixelCount * 3;
        if (image->type != LIBRAW_IMAGE_BITMAP || image->bits != 16 ||
            (image->colors < 1 || image->colors > 4 || image->colors == 2) ||
            count > static_cast<size_t>(std::numeric_limits<jsize>::max()) || image->data_size != pixelCount * image->colors * 2) {
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
        const float normalization = 1.f / (65535.f * minGain);
        std::vector<jshort> row(static_cast<size_t>(image->width) * 3);
        const auto source = reinterpret_cast<const unsigned short *>(image->data);
        for (unsigned y = 0; y < image->height; ++y) {
            if (progress(&cancel, LIBRAW_PROGRESS_CONVERT_RGB, y, image->height)) {
                failure(env, LIBRAW_CANCELLED_BY_CALLBACK); return nullptr;
            }
            for (unsigned x = 0; x < image->width; ++x) {
                const auto pixel = source + (static_cast<size_t>(y) * image->width + x) * image->colors;
                if (image->colors == 1) {
                    row[x * 3] = row[x * 3 + 1] = row[x * 3 + 2] = static_cast<jshort>(halfFloat(pixel[0] / 65535.f));
                } else for (unsigned c = 0; c < 3; ++c) {
                    float value = 0.f;
                    for (unsigned channel = 0; channel < image->colors; ++channel)
                        value += color.rgb_cam[c][channel] * pixel[channel];
                    row[x * 3 + c] = static_cast<jshort>(halfFloat(value * normalization));
                }
            }
            env->SetShortArrayRegion(pixels, static_cast<jsize>(y * row.size()), static_cast<jsize>(row.size()), row.data());
            if (env->ExceptionCheck()) return nullptr;
        }
        auto cls = env->FindClass("eu/studio742/imago/core/raw/RawImage");
        auto ctor = env->GetMethodID(cls, "<init>", "(II[SLjava/lang/String;Ljava/lang/String;)V");
        return env->NewObject(cls, ctor, static_cast<jint>(image->width), static_cast<jint>(image->height), pixels,
            env->NewStringUTF(decoder.imgdata.idata.make), env->NewStringUTF(decoder.imgdata.idata.model));
    } catch (const std::bad_alloc &) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Not enough memory to decode the RAW image.");
    } catch (...) {
        failure(env, LIBRAW_UNSPECIFIED_ERROR);
    }
    return nullptr;
}
