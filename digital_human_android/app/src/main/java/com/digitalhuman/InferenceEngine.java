package com.digitalhuman;

/**
 * JNI bridge to native C++ inference engines.
 * - ASR: whisper.cpp
 * - LLM: llama.cpp
 * - TTS: handled by sherpa-onnx (Kokoro) in JSBridge, not via JNI
 */
public class InferenceEngine {

    static {
        System.loadLibrary("inference");
    }

    // ===== ASR (whisper.cpp) =====

    public static native boolean asrInit(String modelPath);

    public static native String asrTranscribe(float[] audioData);

    public static native void asrRelease();

    // ===== LLM (llama.cpp) =====

    public static native boolean llmInit(String modelPath, String systemPrompt);

    public static native long llmStartChat(String userMessage);

    public static native String llmNext(long session);

    public static native void llmStopChat(long session);

    public static native void llmClearHistory();

    public static native void llmRelease();
}
