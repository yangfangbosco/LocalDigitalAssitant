#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#define LOG_TAG "InferenceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// whisper.cpp
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

// ============================================================
// llama.cpp
// ============================================================
#ifdef HAS_LLAMA
#include "llama.h"

static llama_model *llama_mdl = nullptr;
static const llama_vocab *llama_vcb = nullptr;
static std::string system_prompt_str;
static std::vector<std::pair<std::string, std::string>> conversation;

struct chat_session {
    llama_context *ctx;
    llama_sampler *sampler;
    std::string full_response;
    bool done;
};

extern "C" JNIEXPORT jboolean JNICALL
Java_com_digitalhuman_InferenceEngine_llmInit(JNIEnv *env, jclass, jstring modelPath, jstring systemPrompt) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    const char *prompt = env->GetStringUTFChars(systemPrompt, nullptr);
    LOGI("Loading LLM model: %s", path);

    llama_model_params mparams = llama_model_default_params();
    llama_mdl = llama_model_load_from_file(path, mparams);

    env->ReleaseStringUTFChars(modelPath, path);
    system_prompt_str = prompt;
    env->ReleaseStringUTFChars(systemPrompt, prompt);

    if (!llama_mdl) {
        LOGE("Failed to load LLM model");
        return JNI_FALSE;
    }

    llama_vcb = llama_model_get_vocab(llama_mdl);
    LOGI("LLM model loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_digitalhuman_InferenceEngine_llmStartChat(JNIEnv *env, jclass, jstring userMessage) {
    if (!llama_mdl) return 0;

    const char *msg = env->GetStringUTFChars(userMessage, nullptr);
    conversation.push_back({"user", std::string(msg)});
    env->ReleaseStringUTFChars(userMessage, msg);

    // Build ChatML prompt
    std::string prompt = "<|im_start|>system\n" + system_prompt_str + "<|im_end|>\n";
    for (auto &[role, content] : conversation) {
        prompt += "<|im_start|>" + role + "\n" + content + "<|im_end|>\n";
    }
    prompt += "<|im_start|>assistant\n";

    auto *session = new chat_session();

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 512;
    cparams.n_threads = 8;
    cparams.n_batch = 64;
    session->ctx = llama_init_from_model(llama_mdl, cparams);
    session->done = false;

    LOGI("LLM context created, tokenizing prompt (%zu chars)", prompt.length());

    // Tokenize
    int n_prompt = -llama_tokenize(llama_vcb, prompt.c_str(), prompt.length(),
                                    nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(llama_vcb, prompt.c_str(), prompt.length(),
                   tokens.data(), tokens.size(), true, true);

    LOGI("LLM prompt tokens: %d, decoding in batches...", n_prompt);

    // Evaluate prompt in batches to avoid long stalls
    int batch_size = 64;
    for (int i = 0; i < n_prompt; i += batch_size) {
        int n = std::min(batch_size, n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        int ret = llama_decode(session->ctx, batch);
        if (ret != 0) {
            LOGE("LLM decode failed at batch %d: %d", i, ret);
            delete session;
            return 0;
        }
    }

    LOGI("LLM prompt decoded, ready to generate");

    // Setup sampler
    session->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(0));

    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_digitalhuman_InferenceEngine_llmNext(JNIEnv *env, jclass, jlong sessionPtr) {
    auto *session = reinterpret_cast<chat_session *>(sessionPtr);
    if (!session || session->done) return nullptr;

    llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);

    if (llama_vocab_is_eog(llama_vcb, token)) {
        session->done = true;
        conversation.push_back({"assistant", session->full_response});
        return nullptr;
    }

    char buf[256];
    int n = llama_token_to_piece(llama_vcb, token, buf, sizeof(buf), 0, true);
    std::string piece(buf, n);
    session->full_response += piece;

    // Decode for next iteration
    llama_batch batch = llama_batch_get_one(&token, 1);
    llama_decode(session->ctx, batch);

    return env->NewStringUTF(piece.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_llmStopChat(JNIEnv *, jclass, jlong sessionPtr) {
    auto *session = reinterpret_cast<chat_session *>(sessionPtr);
    if (session) {
        if (session->ctx) llama_free(session->ctx);
        if (session->sampler) llama_sampler_free(session->sampler);
        delete session;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_llmClearHistory(JNIEnv *, jclass) {
    conversation.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_llmRelease(JNIEnv *, jclass) {
    if (llama_mdl) {
        llama_model_free(llama_mdl);
        llama_mdl = nullptr;
        llama_vcb = nullptr;
    }
    conversation.clear();
}

#else
extern "C" JNIEXPORT jboolean JNICALL
Java_com_digitalhuman_InferenceEngine_llmInit(JNIEnv *, jclass, jstring, jstring) {
    LOGE("llama.cpp not compiled"); return JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_digitalhuman_InferenceEngine_llmStartChat(JNIEnv *, jclass, jstring) { return 0; }
extern "C" JNIEXPORT jstring JNICALL
Java_com_digitalhuman_InferenceEngine_llmNext(JNIEnv *, jclass, jlong) { return nullptr; }
extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_llmStopChat(JNIEnv *, jclass, jlong) {}
extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_llmClearHistory(JNIEnv *, jclass) {}
extern "C" JNIEXPORT void JNICALL
Java_com_digitalhuman_InferenceEngine_llmRelease(JNIEnv *, jclass) {}
#endif

// TTS is handled by sherpa-onnx (Kokoro) in Java layer, no JNI needed.
