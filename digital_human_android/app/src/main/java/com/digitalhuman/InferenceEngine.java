package com.digitalhuman;

/**
 * JNI bridge to native C++ inference engines.
 * - ASR: whisper.cpp
 * - LLM: via OpenAI-compatible API (see LlmClient.java)
 * - TTS: via sherpa-onnx (see JSBridge.java)
 */
public class InferenceEngine {

    static {
        System.loadLibrary("inference");
    }

    // ===== ASR (whisper.cpp) =====

    public static native boolean asrInit(String modelPath);

    public static native String asrTranscribe(float[] audioData);

    public static native void asrRelease();
}
