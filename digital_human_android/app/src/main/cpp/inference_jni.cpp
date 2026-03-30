#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "InferenceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// whisper.cpp (ASR)
// ============================================================
#ifdef HAS_WHISPER
#include "whisper.h"

static struct whisper_context *whisper_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_digitalhuman_InferenceEngine_asrInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading whisper model: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    whisper_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!whisper_ctx) {
        LOGE("Failed to load whisper model");
        return JNI_FALSE;
    }
    LOGI("Whisper model loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_digitalhuman_InferenceEngine_asrTranscribe(JNIEnv *env, jclass, jfloatArray audioData) {
    if (!whisper_ctx) return env->NewStringUTF("");

    jsize len = env->GetArrayLength(audioData);
    jfloat *audio = env->GetFloatArrayElements(audioData, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.n_threads = 4;
    params.no_timestamps = true;
    params.initial_prompt = "Envisionware";

    int ret = whisper_full(whisper_ctx, params, audio, len);
    env->ReleaseFloatArrayElements(audioData, audio, 0);

    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
        return env->NewStringUTF("");
    }

    std::string result;
    int n_segments = whisper_full_n_segments(whisper_ctx);
    for (int i = 0; i < n_segments; i++) {
        result += whisper_full_get_segment_text(whisper_ctx, i);
    }

    LOGI("ASR result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_asrRelease(JNIEnv *, jclass) {
    if (whisper_ctx) {
        whisper_free(whisper_ctx);
        whisper_ctx = nullptr;
    }
}

#else
extern "C" JNIEXPORT jboolean JNICALL
Java_com_digitalhuman_InferenceEngine_asrInit(JNIEnv *, jclass, jstring) {
    LOGE("whisper.cpp not compiled"); return JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_digitalhuman_InferenceEngine_asrTranscribe(JNIEnv *env, jclass, jfloatArray) {
    return env->NewStringUTF("");
}
extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_asrRelease(JNIEnv *, jclass) {}
#endif
