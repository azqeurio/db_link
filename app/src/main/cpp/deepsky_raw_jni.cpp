#include <jni.h>
#include <libraw/libraw.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

namespace {

struct ProcessedBuffers {
    int width = 0;
    int height = 0;
    std::vector<uint16_t> full_rgb48;
    int preview_width = 0;
    int preview_height = 0;
    std::vector<int32_t> preview_argb;
    int alignment_width = 0;
    int alignment_height = 0;
    std::vector<float> alignment_luma;
};

inline int ClampToByte(float value) {
    return std::max(0, std::min(255, static_cast<int>(std::lround(value))));
}

inline int DownsampleDimension(int source, int max_edge, int longest_edge) {
    if (source <= 0) return 0;
    if (longest_edge <= max_edge) return source;
    const float scale = static_cast<float>(longest_edge) / static_cast<float>(max_edge);
    return std::max(1, static_cast<int>(std::lround(source / scale)));
}

ProcessedBuffers BuildBuffers(const libraw_processed_image_t* image, int preview_max_edge, int alignment_max_edge) {
    ProcessedBuffers buffers;
    buffers.width = image->width;
    buffers.height = image->height;
    const int colors = std::max(3, static_cast<int>(image->colors));
    const int pixel_count = buffers.width * buffers.height;
    buffers.full_rgb48.resize(static_cast<size_t>(pixel_count) * 3u);

    if (image->bits == 16) {
        const auto* source = reinterpret_cast<const uint16_t*>(image->data);
        for (int pixel = 0; pixel < pixel_count; ++pixel) {
            const int src = pixel * colors;
            const int dst = pixel * 3;
            buffers.full_rgb48[dst] = source[src];
            buffers.full_rgb48[dst + 1] = source[src + 1];
            buffers.full_rgb48[dst + 2] = source[src + 2];
        }
    } else {
        const auto* source = reinterpret_cast<const uint8_t*>(image->data);
        for (int pixel = 0; pixel < pixel_count; ++pixel) {
            const int src = pixel * colors;
            const int dst = pixel * 3;
            buffers.full_rgb48[dst] = static_cast<uint16_t>(source[src] * 257u);
            buffers.full_rgb48[dst + 1] = static_cast<uint16_t>(source[src + 1] * 257u);
            buffers.full_rgb48[dst + 2] = static_cast<uint16_t>(source[src + 2] * 257u);
        }
    }

    const int longest_edge = std::max(buffers.width, buffers.height);
    buffers.preview_width = DownsampleDimension(buffers.width, preview_max_edge, longest_edge);
    buffers.preview_height = DownsampleDimension(buffers.height, preview_max_edge, longest_edge);
    buffers.preview_argb.resize(static_cast<size_t>(buffers.preview_width) * static_cast<size_t>(buffers.preview_height));

    buffers.alignment_width = DownsampleDimension(buffers.width, alignment_max_edge, longest_edge);
    buffers.alignment_height = DownsampleDimension(buffers.height, alignment_max_edge, longest_edge);
    buffers.alignment_luma.resize(static_cast<size_t>(buffers.alignment_width) * static_cast<size_t>(buffers.alignment_height));

    for (int y = 0; y < buffers.preview_height; ++y) {
        const int src_y = std::min(buffers.height - 1, static_cast<int>((static_cast<float>(y) / buffers.preview_height) * buffers.height));
        for (int x = 0; x < buffers.preview_width; ++x) {
            const int src_x = std::min(buffers.width - 1, static_cast<int>((static_cast<float>(x) / buffers.preview_width) * buffers.width));
            const int src = (src_y * buffers.width + src_x) * 3;
            const int r = static_cast<int>(buffers.full_rgb48[src] / 257u);
            const int g = static_cast<int>(buffers.full_rgb48[src + 1] / 257u);
            const int b = static_cast<int>(buffers.full_rgb48[src + 2] / 257u);
            buffers.preview_argb[y * buffers.preview_width + x] =
                (255 << 24) | (ClampToByte(static_cast<float>(r)) << 16) | (ClampToByte(static_cast<float>(g)) << 8) | ClampToByte(static_cast<float>(b));
        }
    }

    for (int y = 0; y < buffers.alignment_height; ++y) {
        const int src_y = std::min(buffers.height - 1, static_cast<int>((static_cast<float>(y) / buffers.alignment_height) * buffers.height));
        for (int x = 0; x < buffers.alignment_width; ++x) {
            const int src_x = std::min(buffers.width - 1, static_cast<int>((static_cast<float>(x) / buffers.alignment_width) * buffers.width));
            const int src = (src_y * buffers.width + src_x) * 3;
            const float r = static_cast<float>(buffers.full_rgb48[src]);
            const float g = static_cast<float>(buffers.full_rgb48[src + 1]);
            const float b = static_cast<float>(buffers.full_rgb48[src + 2]);
            buffers.alignment_luma[y * buffers.alignment_width + x] = (0.2126f * r) + (0.7152f * g) + (0.0722f * b);
        }
    }
    return buffers;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL Java_dev_pl36_cameralink_core_deepsky_DeepSkyNative_decodeOrf(
    JNIEnv* env,
    jobject /* thiz */,
    jstring raw_path,
    jint preview_max_edge,
    jint alignment_max_edge) {
    const char* path_chars = env->GetStringUTFChars(raw_path, nullptr);
    if (path_chars == nullptr) {
        return nullptr;
    }

    LibRaw processor;
    processor.imgdata.params.output_bps = 16;
    processor.imgdata.params.use_camera_wb = 1;
    processor.imgdata.params.no_auto_bright = 1;
    processor.imgdata.params.gamm[0] = 1.0f;
    processor.imgdata.params.gamm[1] = 1.0f;

    const int open_result = processor.open_file(path_chars);
    env->ReleaseStringUTFChars(raw_path, path_chars);
    if (open_result != LIBRAW_SUCCESS) {
        processor.recycle();
        return nullptr;
    }

    if (processor.unpack() != LIBRAW_SUCCESS) {
        processor.recycle();
        return nullptr;
    }
    if (processor.dcraw_process() != LIBRAW_SUCCESS) {
        processor.recycle();
        return nullptr;
    }

    int image_result = LIBRAW_SUCCESS;
    libraw_processed_image_t* image = processor.dcraw_make_mem_image(&image_result);
    if (image == nullptr || image_result != LIBRAW_SUCCESS) {
        if (image != nullptr) {
            LibRaw::dcraw_clear_mem(image);
        }
        processor.recycle();
        return nullptr;
    }

    ProcessedBuffers buffers = BuildBuffers(image, preview_max_edge, alignment_max_edge);

    jshortArray full_array = env->NewShortArray(static_cast<jsize>(buffers.full_rgb48.size()));
    jintArray preview_array = env->NewIntArray(static_cast<jsize>(buffers.preview_argb.size()));
    jfloatArray alignment_array = env->NewFloatArray(static_cast<jsize>(buffers.alignment_luma.size()));
    if (full_array == nullptr || preview_array == nullptr || alignment_array == nullptr) {
        LibRaw::dcraw_clear_mem(image);
        processor.recycle();
        return nullptr;
    }

    env->SetShortArrayRegion(
        full_array,
        0,
        static_cast<jsize>(buffers.full_rgb48.size()),
        reinterpret_cast<const jshort*>(buffers.full_rgb48.data()));
    env->SetIntArrayRegion(
        preview_array,
        0,
        static_cast<jsize>(buffers.preview_argb.size()),
        reinterpret_cast<const jint*>(buffers.preview_argb.data()));
    env->SetFloatArrayRegion(
        alignment_array,
        0,
        static_cast<jsize>(buffers.alignment_luma.size()),
        reinterpret_cast<const jfloat*>(buffers.alignment_luma.data()));

    jclass result_class = env->FindClass("dev/pl36/cameralink/core/deepsky/NativeRawDecodeResult");
    if (result_class == nullptr) {
        LibRaw::dcraw_clear_mem(image);
        processor.recycle();
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(result_class, "<init>", "(II[SII[III[F)V");
    if (ctor == nullptr) {
        LibRaw::dcraw_clear_mem(image);
        processor.recycle();
        return nullptr;
    }

    jobject result = env->NewObject(
        result_class,
        ctor,
        buffers.width,
        buffers.height,
        full_array,
        buffers.preview_width,
        buffers.preview_height,
        preview_array,
        buffers.alignment_width,
        buffers.alignment_height,
        alignment_array);

    LibRaw::dcraw_clear_mem(image);
    processor.recycle();
    return result;
}
