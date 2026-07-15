#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_dev_dblink_core_rawai_RawAiController_createNativeEngine(
    JNIEnv* env,
    jobject thiz,
    jstring model_path,
    jstring manifest_json);

JNIEXPORT jobject JNICALL
Java_dev_dblink_core_rawai_RawAiController_process(
    JNIEnv* env,
    jobject thiz,
    jlong engine_ptr,
    jstring input_path,
    jstring output_path,
    jint width,
    jint height,
    jfloat iso,
    jint preprocess_profile,
    jint preferred_backend);

JNIEXPORT void JNICALL
Java_dev_dblink_core_rawai_RawAiController_cancel(
    JNIEnv* env,
    jobject thiz,
    jlong engine_ptr);

JNIEXPORT void JNICALL
Java_dev_dblink_core_rawai_RawAiController_destroyEngine(
    JNIEnv* env,
    jobject thiz,
    jlong engine_ptr);

#ifdef __cplusplus
}
#endif
