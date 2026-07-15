#include "raw_ai_jni.h"
#include "../runtime/raw_ai_engine.h"
#include <string>

using namespace raw_ai;

static std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* utf = env->GetStringUTFChars(jstr, nullptr);
    std::string str(utf);
    env->ReleaseStringUTFChars(jstr, utf);
    return str;
}

static jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_dblink_core_rawai_RawAiController_createNativeEngine(
    JNIEnv* env,
    jobject thiz,
    jstring model_path,
    jstring manifest_json) {

    std::string modelPath = jstringToString(env, model_path);
    std::string manifestJson = jstringToString(env, manifest_json);

    std::string error;
    auto engine = RawAiEngine::create(modelPath, manifestJson, &error);
    if (!engine) {
        // Failed to initialize
        return 0L;
    }

    return reinterpret_cast<jlong>(engine.release());
}

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
    jint preferred_backend) {

    RawAiEngine* engine = reinterpret_cast<RawAiEngine*>(engine_ptr);
    jclass resultClass = env->FindClass("dev/dblink/core/rawai/RawAiResult");
    if (!resultClass) {
        return nullptr;
    }

    jmethodID initMethod = env->GetMethodID(
        resultClass,
        "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;DDJ)V"
    );
    if (!initMethod) {
        return nullptr;
    }

    if (!engine) {
        jstring errorMsg = env->NewStringUTF("Engine pointer is null");
        jstring emptyStr = env->NewStringUTF("");
        jstring errCode = env->NewStringUTF("NULL_ENGINE");
        return env->NewObject(resultClass, initMethod, false, emptyStr, emptyStr, errCode, errorMsg, 0.0, 0.0, 0L);
    }

    std::string inputPath = jstringToString(env, input_path);
    std::string outputPath = jstringToString(env, output_path);
    BackendType prefBackend = static_cast<BackendType>(preferred_backend);

    EngineDiagnostics diag;
    std::string error;
    bool success = engine->processSingleTile(inputPath, outputPath, iso, prefBackend, &diag, &error);

    jstring selectedBackendJ = stringToJstring(env, diag.selectedBackend);
    jstring fallbackReasonJ = stringToJstring(env, diag.fallbackReason);
    jstring errorCodeJ = success ? env->NewStringUTF("") : env->NewStringUTF("INFERENCE_FAILED");
    jstring errorMessageJ = stringToJstring(env, error);

    return env->NewObject(
        resultClass,
        initMethod,
        success,
        selectedBackendJ,
        fallbackReasonJ,
        errorCodeJ,
        errorMessageJ,
        diag.initializationMs,
        diag.processingMs,
        static_cast<jlong>(diag.peakMemoryBytes)
    );
}

JNIEXPORT void JNICALL
Java_dev_dblink_core_rawai_RawAiController_cancel(
    JNIEnv* env,
    jobject thiz,
    jlong engine_ptr) {

    RawAiEngine* engine = reinterpret_cast<RawAiEngine*>(engine_ptr);
    if (engine) {
        engine->cancel();
    }
}

JNIEXPORT void JNICALL
Java_dev_dblink_core_rawai_RawAiController_destroyEngine(
    JNIEnv* env,
    jobject thiz,
    jlong engine_ptr) {

    RawAiEngine* engine = reinterpret_cast<RawAiEngine*>(engine_ptr);
    if (engine) {
        delete engine;
    }
}

} // extern "C"
