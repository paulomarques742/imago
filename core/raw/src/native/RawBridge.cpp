#include <jni.h>
#include <libraw/libraw.h>
#include <limits>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>

namespace {
// Runs fn(i) for every i below count on every core. The workers never touch the JVM: what needs JNI
// happens between calls, on the caller's thread.
template <class Fn> void parallelFor(int count, Fn fn) {
    const int workers = std::min(static_cast<int>(std::max(1u, std::thread::hardware_concurrency())), count);
    if (workers <= 1) {
        for (int i = 0; i < count; ++i) fn(i);
        return;
    }
    std::atomic<int> next{0};
    auto run = [&]() {
        for (int i = next.fetch_add(1); i < count; i = next.fetch_add(1)) fn(i);
    };
    std::vector<std::thread> pool;
    pool.reserve(static_cast<size_t>(workers - 1));
    for (int w = 1; w < workers; ++w) pool.emplace_back(run);
    run();
    for (auto &thread : pool) thread.join();
}

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

// The file's lens corrections (EmbeddedLensCorrection.table): radial curves in fractions of the
// half-diagonal. Each channel is sampled at its own radius, which is what takes the lateral
// chromatic aberration away, and the vignetting gain is that of the point sampled.
struct LensCorrection {
    int knots = 0;
    float knot[16] = {};
    float distortionKnot[16] = {};
    float factor[3][16] = {};
    float gain[16] = {};
    // Red and blue against green, before the zoom: the aberration alone, for the sensor's own sites.
    float aberration[2][16] = {};

    static float interpolate(const float *x, const float *y, int n, float at) {
        if (at < x[0]) return y[0];
        for (int i = 1; i < n; ++i)
            if (at <= x[i]) return y[i - 1] + (at - x[i - 1]) * (y[i] - y[i - 1]) / (x[i] - x[i - 1]);
        return y[n - 1];
    }

    // False when the table is not one EmbeddedLensCorrection writes.
    bool read(JNIEnv *env, jfloatArray table, unsigned width, unsigned height) {
        const jsize size = env->GetArrayLength(table);
        if (size < 1) return false;
        float count = 0.f;
        env->GetFloatArrayRegion(table, 0, 1, &count);
        knots = static_cast<int>(count);
        if (knots < 2 || knots > 16 || size != 1 + knots * 5) return false;
        std::vector<float> values(static_cast<size_t>(size));
        env->GetFloatArrayRegion(table, 0, size, values.data());
        for (int i = 0; i < knots; ++i) {
            knot[i] = values[1 + i];
            for (int c = 0; c < 3; ++c) factor[c][i] = values[1 + knots * (1 + c) + i];
            gain[i] = std::max(values[1 + knots * 4 + i], 1e-4f);
            if (!(factor[1][i] > 0.f)) return false;
            aberration[0][i] = factor[0][i] / factor[1][i];
            aberration[1][i] = factor[2][i] / factor[1][i];
        }
        for (int i = 1; i < knots; ++i) if (!(knot[i] > knot[i - 1])) return false;
        // The zoom that keeps the frame filled, as EmbeddedLensCorrection.scale computes it.
        const float shortSide = std::min(width, height) / std::hypot(static_cast<float>(width), static_cast<float>(height));
        float scale = 0.f;
        for (int i = 0; i < 200; ++i) {
            const float radius = shortSide + (1.f - shortSide) * i / 199.f;
            for (int c = 0; c < 3; ++c) scale = std::max(scale, interpolate(knot, factor[c], knots, radius));
        }
        if (!(scale > 0.5f && scale < 2.f)) return false;
        for (int i = 0; i < knots; ++i) {
            distortionKnot[i] = knot[i] * scale;
            for (int c = 0; c < 3; ++c) factor[c][i] /= scale;
        }
        return true;
    }

    float sampled(int channel, float radius) const { return interpolate(distortionKnot, factor[channel], knots, radius); }
    float gainAt(float radius) const { return interpolate(knot, gain, knots, radius); }
    float aberrationAt(bool blue, float radius) const { return interpolate(knot, aberration[blue ? 1 : 0], knots, radius); }
};

// Rows worked on at a time: enough for every core, and few enough to answer a cancellation in time.
constexpr unsigned ROW_BLOCK = 128;

// Takes the lateral chromatic aberration out of a Bayer sensor before demosaicing: each red and blue
// site takes its value from where its colour landed, read from the sites of that colour. After
// demosaicing it is too late: the interpolation borrows the detail of red and blue from green, so the
// edges are half aligned already and moving the channels then overcorrects the detail. False when the
// sensor is not a 2×2 Bayer pattern, and nothing is changed.
bool correctBayerAberration(LibRaw &decoder, const LensCorrection &lens, Cancellation &cancel) {
    auto &sizes = decoder.imgdata.sizes;
    auto *raw = decoder.imgdata.rawdata.raw_image;
    const auto filters = decoder.imgdata.idata.filters;
    if (!raw || filters < 1000 || sizes.width < 4 || sizes.height < 4) return false;
    int colour[2][2];
    for (int y = 0; y < 2; ++y)
        for (int x = 0; x < 2; ++x) {
            const int c = decoder.COLOR(y, x);
            if (c < 0 || c > 3) return false;
            const char name = decoder.imgdata.idata.cdesc[c];
            colour[y][x] = name == 'R' ? 0 : name == 'B' ? 2 : 1;
            for (int dy = 0; dy < 8; dy += 2)
                for (int dx = 0; dx < 8; dx += 2)
                    if (decoder.COLOR(y + dy, x + dx) != c) return false;
        }
    const unsigned width = sizes.width, height = sizes.height;
    const size_t pitch = sizes.raw_pitch / 2;
    unsigned short *visible = raw + static_cast<size_t>(sizes.top_margin) * pitch + sizes.left_margin;
    std::vector<unsigned short> original(static_cast<size_t>(width) * height);
    for (unsigned y = 0; y < height; ++y)
        std::memcpy(&original[static_cast<size_t>(y) * width], visible + y * pitch, width * sizeof(unsigned short));
    const float centreX = width / 2.f, centreY = height / 2.f;
    const float inverseDiagonal = 1.f / std::hypot(centreX, centreY);
    auto correctRow = [&](unsigned y) {
        const unsigned py = y & 1;
        for (unsigned x = 0; x < width; ++x) {
            const unsigned px = x & 1;
            const int c = colour[py][px];
            if (c == 1) continue;
            const float dx = x + .5f - centreX, dy = y + .5f - centreY;
            const float ratio = lens.aberrationAt(c == 2, std::hypot(dx, dy) * inverseDiagonal);
            // The same colour's lattice, every other site in each direction.
            const float u = std::clamp((centreX + dx * ratio - .5f - px) / 2.f, 0.f, (width - 1 - px) / 2.f);
            const float v = std::clamp((centreY + dy * ratio - .5f - py) / 2.f, 0.f, (height - 1 - py) / 2.f);
            const auto i0 = static_cast<unsigned>(u), j0 = static_cast<unsigned>(v);
            const unsigned i1 = std::min(i0 + 1, (width - 1 - px) / 2), j1 = std::min(j0 + 1, (height - 1 - py) / 2);
            const float fu = u - i0, fv = v - j0;
            auto at = [&](unsigned i, unsigned j) {
                return static_cast<float>(original[static_cast<size_t>(py + 2 * j) * width + px + 2 * i]);
            };
            const float value = (at(i0, j0) * (1.f - fu) + at(i1, j0) * fu) * (1.f - fv) + (at(i0, j1) * (1.f - fu) + at(i1, j1) * fu) * fv;
            visible[y * pitch + x] = static_cast<unsigned short>(std::clamp(value + .5f, 0.f, 65535.f));
        }
    };
    for (unsigned from = 0; from < height; from += ROW_BLOCK) {
        if (progress(&cancel, LIBRAW_PROGRESS_RAW2_IMAGE, from, height)) return false;
        const int rows = static_cast<int>(std::min(ROW_BLOCK, height - from));
        parallelFor(rows, [&](int i) { correctRow(from + static_cast<unsigned>(i)); });
    }
    return true;
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
    jboolean baselineExposure, jint highlight, jfloatArray lensTable, jboolean fileLensCorrection) {
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
        // highlight=1 normalizes gains by their maximum, avoiding clipping during white balance, but
        // leaves a channel that clipped above the others: a pink or violet sky. Engine 2 asks for 2,
        // which blends the clipped channels towards white; the mode is the recipe's engine, never a default.
        params.output_color = 0;
        params.highlight = std::clamp<int>(highlight, 0, 9);
        params.gamm[0] = params.gamm[1] = 1.0;
        params.no_auto_bright = 1;
        params.use_camera_wb = 1;
        params.adjust_maximum_thr = 0;
        params.half_size = halfSize ? 1 : 0;
        code = decoder.unpack();
        LensCorrection lens;
        bool corrected = false;
        bool bayerCorrected = false;
        if (code == LIBRAW_SUCCESS && lensTable) {
            corrected = lens.read(env, lensTable, decoder.imgdata.sizes.width, decoder.imgdata.sizes.height);
            if (env->ExceptionCheck()) return nullptr;
            // At half size there is no demosaicing: each channel comes straight from its own sites, and
            // moving the channels as they are copied out is exact, for a fraction of the work.
            if (corrected && !halfSize) bayerCorrected = correctBayerAberration(decoder, lens, cancel);
            if (progress(&cancel, LIBRAW_PROGRESS_RAW2_IMAGE, 1, 1)) { failure(env, LIBRAW_CANCELLED_BY_CALLBACK); return nullptr; }
        }
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
        const auto source = reinterpret_cast<const unsigned short *>(image->data);
        auto channel = [&](unsigned x, unsigned y, unsigned c) {
            const auto pixel = source + (static_cast<size_t>(y) * image->width + x) * image->colors;
            if (image->colors == 1) return pixel[0] * exposure / 65535.f;
            float value = 0.f;
            for (unsigned cameraChannel = 0; cameraChannel < image->colors; ++cameraChannel)
                value += color.rgb_cam[c][cameraChannel] * pixel[cameraChannel];
            return value * normalization;
        };
        // Sample scene-linear channels before FP16 storage, never an embedded JPEG.
        auto bilinear = [&](float centreX, float centreY, unsigned c) {
            const float sx = std::clamp(centreX - .5f, 0.f, image->width - 1.f);
            const float sy = std::clamp(centreY - .5f, 0.f, image->height - 1.f);
            const auto x0 = static_cast<unsigned>(sx), y0 = static_cast<unsigned>(sy);
            const auto x1 = std::min(x0 + 1, image->width - 1u), y1 = std::min(y0 + 1, image->height - 1u);
            const float fx = sx - x0, fy = sy - y0;
            const float top = channel(x0, y0, c) * (1.f - fx) + channel(x1, y0, c) * fx;
            const float bottom = channel(x0, y1, c) * (1.f - fx) + channel(x1, y1, c) * fx;
            return top * (1.f - fy) + bottom * fy;
        };
        // The lens bent the camera's own channels, so they are corrected before the matrix to sRGB
        // mixes them: done after it, the blue that the matrix builds from camera blue minus green
        // moves by more than the camera's blue did, and the aberration stays.
        auto cameraBilinear = [&](float centreX, float centreY, unsigned k) {
            const float sx = std::clamp(centreX - .5f, 0.f, image->width - 1.f);
            const float sy = std::clamp(centreY - .5f, 0.f, image->height - 1.f);
            const auto x0 = static_cast<unsigned>(sx), y0 = static_cast<unsigned>(sy);
            const auto x1 = std::min(x0 + 1, image->width - 1u), y1 = std::min(y0 + 1, image->height - 1u);
            const float fx = sx - x0, fy = sy - y0;
            auto at = [&](unsigned x, unsigned y) {
                return static_cast<float>(source[(static_cast<size_t>(y) * image->width + x) * image->colors + k]);
            };
            const float top = at(x0, y0) * (1.f - fx) + at(x1, y0) * fx;
            const float bottom = at(x0, y1) * (1.f - fx) + at(x1, y1) * fx;
            return top * (1.f - fy) + bottom * fy;
        };
        // Which of the lens's curves each camera channel follows: red and blue their own, the rest green's,
        // and all of them green's once the sensor's sites were corrected.
        int lensChannel[4] = {1, 1, 1, 1};
        for (unsigned k = 0; k < image->colors && image->colors > 1 && !bayerCorrected; ++k) {
            const char name = decoder.imgdata.idata.cdesc[k];
            lensChannel[k] = name == 'R' ? 0 : name == 'B' ? 2 : 1;
        }
        const float halfWidth = image->width / 2.f, halfHeight = image->height / 2.f;
        const float inverseDiagonal = 1.f / std::hypot(halfWidth, halfHeight);
        auto convertRow = [&](unsigned y, jshort *row) {
            float camera[4] = {};
            for (unsigned x = 0; x < width; ++x) {
                const float px = (x + .5f) / scale, py = (y + .5f) / scale;
                if (corrected) {
                    // A point of the corrected frame comes from the original along its radius, each
                    // channel at its own distance; the gain is the vignetting at the point it came from.
                    const float dx = px - halfWidth, dy = py - halfHeight;
                    const float radius = std::hypot(dx, dy) * inverseDiagonal;
                    if (bayerCorrected || image->colors == 1) {
                        // One position for every channel: the weights and the gain are worked out once.
                        const float f = lens.sampled(1, radius);
                        const float gain = lens.gainAt(radius * f);
                        const float sx = std::clamp(halfWidth + dx * f - .5f, 0.f, image->width - 1.f);
                        const float sy = std::clamp(halfHeight + dy * f - .5f, 0.f, image->height - 1.f);
                        const auto x0 = static_cast<unsigned>(sx), y0 = static_cast<unsigned>(sy);
                        const auto x1 = std::min(x0 + 1, image->width - 1u), y1 = std::min(y0 + 1, image->height - 1u);
                        const float fx = sx - x0, fy = sy - y0;
                        const float w00 = (1.f - fx) * (1.f - fy) / gain, w10 = fx * (1.f - fy) / gain;
                        const float w01 = (1.f - fx) * fy / gain, w11 = fx * fy / gain;
                        const auto p00 = source + (static_cast<size_t>(y0) * image->width + x0) * image->colors;
                        const auto p10 = source + (static_cast<size_t>(y0) * image->width + x1) * image->colors;
                        const auto p01 = source + (static_cast<size_t>(y1) * image->width + x0) * image->colors;
                        const auto p11 = source + (static_cast<size_t>(y1) * image->width + x1) * image->colors;
                        for (unsigned k = 0; k < image->colors; ++k)
                            camera[k] = p00[k] * w00 + p10[k] * w10 + p01[k] * w01 + p11[k] * w11;
                    } else {
                        for (unsigned k = 0; k < image->colors; ++k) {
                            const float f = lens.sampled(lensChannel[k], radius);
                            camera[k] = cameraBilinear(halfWidth + dx * f, halfHeight + dy * f, k) / lens.gainAt(radius * f);
                        }
                    }
                    for (unsigned c = 0; c < 3; ++c) {
                        float value;
                        if (image->colors == 1) {
                            value = camera[0] * exposure / 65535.f;
                        } else {
                            value = 0.f;
                            for (unsigned k = 0; k < image->colors; ++k) value += color.rgb_cam[c][k] * camera[k];
                            value *= normalization;
                        }
                        row[x * 3 + c] = static_cast<jshort>(halfFloat(value));
                    }
                    continue;
                }
                for (unsigned c = 0; c < 3; ++c) {
                    const float value = scale == 1.f ? channel(x, y, c) : bilinear(px, py, c);
                    row[x * 3 + c] = static_cast<jshort>(halfFloat(value));
                }
            }
        };
        // A block of rows on every core, then into the array and a look at the cancellation, on this thread.
        const size_t rowSize = static_cast<size_t>(width) * 3;
        std::vector<jshort> block(rowSize * ROW_BLOCK);
        for (unsigned from = 0; from < height; from += ROW_BLOCK) {
            if (progress(&cancel, LIBRAW_PROGRESS_CONVERT_RGB, from, height)) {
                failure(env, LIBRAW_CANCELLED_BY_CALLBACK); return nullptr;
            }
            const unsigned rows = std::min(ROW_BLOCK, height - from);
            parallelFor(static_cast<int>(rows), [&](int i) { convertRow(from + static_cast<unsigned>(i), block.data() + rowSize * static_cast<size_t>(i)); });
            env->SetShortArrayRegion(pixels, static_cast<jsize>(from * rowSize), static_cast<jsize>(rows * rowSize), block.data());
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
        // The full development's size, oriented as this one: a half-size decode is half of it.
        const auto &sizes = decoder.imgdata.sizes;
        const bool turned = (sizes.flip & 4) != 0;
        const jint fullWidth = static_cast<jint>(turned ? sizes.height : sizes.width);
        const jint fullHeight = static_cast<jint>(turned ? sizes.width : sizes.height);
        auto ctor = env->GetMethodID(cls, "<init>", "(II[SLjava/lang/String;Ljava/lang/String;F[FZII)V");
        return env->NewObject(cls, ctor, static_cast<jint>(width), static_cast<jint>(height), pixels,
            env->NewStringUTF(decoder.imgdata.idata.make), env->NewStringUTF(decoder.imgdata.idata.model),
            static_cast<jfloat>(foundBaseline), xyzToDeveloped, fileLensCorrection, fullWidth, fullHeight);
    } catch (const std::bad_alloc &) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Not enough memory to decode the RAW image.");
    } catch (...) {
        failure(env, LIBRAW_UNSPECIFIED_ERROR);
    }
    return nullptr;
}
