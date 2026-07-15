#include <jni.h>
#include <libraw/libraw.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

namespace {

std::string CfaPattern(LibRaw& processor, int row_offset = 0, int column_offset = 0) {
    static constexpr char kColor[] = "RGBG";
    std::string result;
    result.reserve(4);
    for (int row = 0; row < 2; ++row) {
        for (int column = 0; column < 2; ++column) {
            const int index = processor.COLOR(row + row_offset, column + column_offset);
            result.push_back(index >= 0 && index < 4 ? kColor[index] : '?');
        }
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_dblink_core_rawai_RawDomainDebugNative_extractVisibleBayerCrop(
    JNIEnv* env,
    jobject /* thiz */,
    jstring raw_path,
    jint crop_x,
    jint crop_y,
    jint crop_width,
    jint crop_height) {
    const char* path_chars = env->GetStringUTFChars(raw_path, nullptr);
    if (path_chars == nullptr) return nullptr;
    LibRaw processor;
    const int open_result = processor.open_file(path_chars);
    env->ReleaseStringUTFChars(raw_path, path_chars);
    if (open_result != LIBRAW_SUCCESS || processor.unpack() != LIBRAW_SUCCESS) {
        processor.recycle();
        return nullptr;
    }

    const auto& sizes = processor.imgdata.sizes;
    const int visible_width = sizes.width;
    const int visible_height = sizes.height;
    if (processor.imgdata.rawdata.raw_image == nullptr || crop_x < 0 || crop_y < 0 ||
        crop_width <= 0 || crop_height <= 0 || crop_x + crop_width > visible_width ||
        crop_y + crop_height > visible_height) {
        processor.recycle();
        return nullptr;
    }

    std::vector<jint> mosaic(static_cast<size_t>(crop_width) * crop_height);
    const auto* raw_image = processor.imgdata.rawdata.raw_image;
    for (int y = 0; y < crop_height; ++y) {
        const int source_y = sizes.top_margin + crop_y + y;
        for (int x = 0; x < crop_width; ++x) {
            const int source_x = sizes.left_margin + crop_x + x;
            mosaic[static_cast<size_t>(y) * crop_width + x] =
                static_cast<jint>(raw_image[static_cast<size_t>(source_y) * sizes.raw_width + source_x]);
        }
    }
    std::vector<jint> black(4);
    for (int channel = 0; channel < 4; ++channel) {
        black[channel] = static_cast<jint>(
            processor.imgdata.color.black + processor.imgdata.color.cblack[channel]);
    }
    std::vector<jdouble> matrix(12);
    for (int row = 0; row < 4; ++row) {
        for (int column = 0; column < 3; ++column) {
            matrix[static_cast<size_t>(row) * 3 + column] = processor.imgdata.color.cam_xyz[row][column];
        }
    }

    jintArray mosaic_array = env->NewIntArray(static_cast<jsize>(mosaic.size()));
    jintArray black_array = env->NewIntArray(4);
    jdoubleArray matrix_array = env->NewDoubleArray(12);
    if (mosaic_array == nullptr || black_array == nullptr || matrix_array == nullptr) {
        processor.recycle();
        return nullptr;
    }
    env->SetIntArrayRegion(mosaic_array, 0, static_cast<jsize>(mosaic.size()), mosaic.data());
    env->SetIntArrayRegion(black_array, 0, 4, black.data());
    env->SetDoubleArrayRegion(matrix_array, 0, 12, matrix.data());

    jclass result_class = env->FindClass("dev/dblink/core/rawai/RawBayerDebugFrame");
    jmethodID ctor = result_class == nullptr ? nullptr : env->GetMethodID(
        result_class,
        "<init>",
        "(IIII[IIIIIIIILjava/lang/String;[II[DLjava/lang/String;Ljava/lang/String;)V");
    if (ctor == nullptr) {
        processor.recycle();
        return nullptr;
    }
    const std::string cfa = CfaPattern(processor, sizes.top_margin, sizes.left_margin);
    jstring cfa_string = env->NewStringUTF(cfa.c_str());
    jstring model_string = env->NewStringUTF(processor.imgdata.idata.model);
    jstring version_string = env->NewStringUTF(LibRaw::version());
    jobject result = env->NewObject(
        result_class,
        ctor,
        crop_width,
        crop_height,
        crop_x,
        crop_y,
        mosaic_array,
        static_cast<jint>(sizes.raw_width),
        static_cast<jint>(sizes.raw_height),
        visible_width,
        visible_height,
        static_cast<jint>(sizes.top_margin),
        static_cast<jint>(sizes.left_margin),
        static_cast<jint>(sizes.flip),
        cfa_string,
        black_array,
        static_cast<jint>(processor.imgdata.color.maximum),
        matrix_array,
        model_string,
        version_string);
    processor.recycle();
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_dblink_core_rawai_RawDomainDebugNative_extractProcessedCrop(
    JNIEnv* env,
    jobject /* thiz */,
    jstring raw_path,
    jint crop_x,
    jint crop_y,
    jint crop_width,
    jint crop_height) {
    const char* path_chars = env->GetStringUTFChars(raw_path, nullptr);
    if (path_chars == nullptr) return nullptr;

    LibRaw processor;
    // These are intentionally identical to the existing production decodeOrf path.
    processor.imgdata.params.output_bps = 16;
    processor.imgdata.params.use_camera_wb = 1;
    processor.imgdata.params.no_auto_bright = 1;
    processor.imgdata.params.gamm[0] = 1.0f;
    processor.imgdata.params.gamm[1] = 1.0f;
    const int open_result = processor.open_file(path_chars);
    env->ReleaseStringUTFChars(raw_path, path_chars);
    if (open_result != LIBRAW_SUCCESS || processor.unpack() != LIBRAW_SUCCESS) {
        processor.recycle();
        return nullptr;
    }

    const int raw_width = processor.imgdata.sizes.raw_width;
    const int raw_height = processor.imgdata.sizes.raw_height;
    const int top_margin = processor.imgdata.sizes.top_margin;
    const int left_margin = processor.imgdata.sizes.left_margin;
    const int orientation_flip = processor.imgdata.sizes.flip;
    const std::string cfa = CfaPattern(processor);
    std::vector<float> black(4);
    std::vector<float> white_balance(4);
    for (int channel = 0; channel < 4; ++channel) {
        black[channel] = static_cast<float>(
            processor.imgdata.color.black + processor.imgdata.color.cblack[channel]);
        white_balance[channel] = processor.imgdata.color.cam_mul[channel];
    }
    const int white_level = processor.imgdata.color.maximum;

    if (processor.dcraw_process() != LIBRAW_SUCCESS) {
        processor.recycle();
        return nullptr;
    }
    int image_result = LIBRAW_SUCCESS;
    libraw_processed_image_t* image = processor.dcraw_make_mem_image(&image_result);
    if (image == nullptr || image_result != LIBRAW_SUCCESS || image->type != LIBRAW_IMAGE_BITMAP) {
        if (image != nullptr) LibRaw::dcraw_clear_mem(image);
        processor.recycle();
        return nullptr;
    }
    if (crop_x < 0 || crop_y < 0 || crop_width <= 0 || crop_height <= 0 ||
        crop_x + crop_width > static_cast<int>(image->width) ||
        crop_y + crop_height > static_cast<int>(image->height)) {
        LibRaw::dcraw_clear_mem(image);
        processor.recycle();
        return nullptr;
    }

    const int colors = std::max(3, static_cast<int>(image->colors));
    std::vector<float> tensor(static_cast<size_t>(crop_width) * crop_height * 3u);
    for (int y = 0; y < crop_height; ++y) {
        for (int x = 0; x < crop_width; ++x) {
            const size_t source_pixel = static_cast<size_t>(crop_y + y) * image->width + crop_x + x;
            const size_t destination = (static_cast<size_t>(y) * crop_width + x) * 3u;
            if (image->bits == 16) {
                const auto* source = reinterpret_cast<const uint16_t*>(image->data) + source_pixel * colors;
                for (int channel = 0; channel < 3; ++channel) tensor[destination + channel] = source[channel] / 65535.0f;
            } else {
                const auto* source = reinterpret_cast<const uint8_t*>(image->data) + source_pixel * colors;
                for (int channel = 0; channel < 3; ++channel) tensor[destination + channel] = source[channel] / 255.0f;
            }
        }
    }

    jfloatArray tensor_array = env->NewFloatArray(static_cast<jsize>(tensor.size()));
    jfloatArray black_array = env->NewFloatArray(4);
    jfloatArray wb_array = env->NewFloatArray(4);
    if (tensor_array == nullptr || black_array == nullptr || wb_array == nullptr) {
        LibRaw::dcraw_clear_mem(image);
        processor.recycle();
        return nullptr;
    }
    env->SetFloatArrayRegion(tensor_array, 0, static_cast<jsize>(tensor.size()), tensor.data());
    env->SetFloatArrayRegion(black_array, 0, 4, black.data());
    env->SetFloatArrayRegion(wb_array, 0, 4, white_balance.data());

    jclass result_class = env->FindClass("dev/dblink/core/rawai/RawDomainDebugTensor");
    jmethodID ctor = result_class == nullptr ? nullptr : env->GetMethodID(
        result_class,
        "<init>",
        "(IIII[FIIIIIIILjava/lang/String;[FI[FLjava/lang/String;Ljava/lang/String;)V");
    if (ctor == nullptr) {
        LibRaw::dcraw_clear_mem(image);
        processor.recycle();
        return nullptr;
    }
    jstring cfa_string = env->NewStringUTF(cfa.c_str());
    jstring stage_string = env->NewStringUTF("POST_LIBRAW_DCRAW_CAMERA_WB_LINEAR_SRGB_U16");
    jstring version_string = env->NewStringUTF(LibRaw::version());
    jobject result = env->NewObject(
        result_class,
        ctor,
        crop_width,
        crop_height,
        crop_x,
        crop_y,
        tensor_array,
        raw_width,
        raw_height,
        static_cast<jint>(image->width),
        static_cast<jint>(image->height),
        top_margin,
        left_margin,
        orientation_flip,
        cfa_string,
        black_array,
        white_level,
        wb_array,
        stage_string,
        version_string);

    LibRaw::dcraw_clear_mem(image);
    processor.recycle();
    return result;
}
