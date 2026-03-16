package com.digitalhuman;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class JSBridge {

    private static final String TAG = "JSBridge";
    private static final int SAMPLE_RATE = 16000;

    private final WebView webView;
    private final Handler mainHandler;
    private final ExecutorService executor;

    private AudioRecord audioRecord;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private float[] recordedAudio;

    // Sherpa-ONNX TTS (Kokoro)
    private OfflineTts offlineTts;
    private boolean ttsReady = false;

    public JSBridge(WebView webView, Context context) {
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void initTts(AssetManager assetManager, String modelDir) {
        try {
            String modelPath = modelDir + "/kokoro-model.onnx";
            String tokensPath = modelDir + "/kokoro-tokens.txt";
            String voicesPath = modelDir + "/kokoro-voices.bin";

            OfflineTtsKokoroModelConfig kokoroConfig = new OfflineTtsKokoroModelConfig();
            kokoroConfig.setModel(modelPath);
            kokoroConfig.setVoices(voicesPath);
            kokoroConfig.setTokens(tokensPath);
            kokoroConfig.setLengthScale(1.0f);

            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setKokoro(kokoroConfig);
            modelConfig.setNumThreads(4);
            modelConfig.setDebug(false);

            OfflineTtsConfig ttsConfig = new OfflineTtsConfig();
            ttsConfig.setModel(modelConfig);

            offlineTts = new OfflineTts(assetManager, ttsConfig);
            ttsReady = true;
            Log.i(TAG, "TTS: Kokoro initialized, sampleRate=" + offlineTts.sampleRate()
                    + ", numSpeakers=" + offlineTts.numSpeakers());
        } catch (Exception e) {
            Log.e(TAG, "TTS: Kokoro init failed", e);
        }
    }

    // ===== Recording =====

    @JavascriptInterface
    public void startRecording() {
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferSize * 4);

        isRecording.set(true);
        audioRecord.startRecording();

        executor.execute(() -> {
            float[] buffer = new float[bufferSize / 4];
            float[] allAudio = new float[SAMPLE_RATE * 30];
            int totalSamples = 0;

            while (isRecording.get() && totalSamples < allAudio.length) {
                int read = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    System.arraycopy(buffer, 0, allAudio, totalSamples, read);
                    totalSamples += read;
                }
            }

            recordedAudio = new float[totalSamples];
            System.arraycopy(allAudio, 0, recordedAudio, 0, totalSamples);
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        });
    }

    @JavascriptInterface
    public void stopRecordingAndProcess() {
        isRecording.set(false);
        executor.execute(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            if (recordedAudio == null || recordedAudio.length == 0) {
                callJS("onASRResult", "");
                return;
            }

            String text = InferenceEngine.asrTranscribe(recordedAudio);
            Log.d(TAG, "ASR result: " + text);
            callJS("onASRResult", escapeJS(text));

            if (text == null || text.trim().isEmpty()) return;
            runLLMAndTTS(text.trim());
        });
    }

    @JavascriptInterface
    public void sendText(String text) {
        Log.d(TAG, "sendText: " + text);
        executor.execute(() -> {
            callJS("onASRResult", escapeJS(text));
            runLLMAndTTS(text.trim());
        });
    }

    // ===== LLM + TTS Pipeline =====

    private void runLLMAndTTS(String userText) {
        long session = InferenceEngine.llmStartChat(userText);
        StringBuilder sentence = new StringBuilder();
        String token;

        while ((token = InferenceEngine.llmNext(session)) != null) {
            callJS("onLLMToken", escapeJS(token));
            sentence.append(token);

            if (token.matches(".*[.!?;。！？；\\n].*")) {
                String completeSentence = sentence.toString().trim();
                if (!completeSentence.isEmpty()) {
                    synthesizeAndPlay(completeSentence);
                }
                sentence.setLength(0);
            }
        }

        String remaining = sentence.toString().trim();
        if (!remaining.isEmpty()) {
            synthesizeAndPlay(remaining);
        }

        callJS("onLLMDone", "");
        callJS("onTTSDone", "");
    }

    // ===== TTS =====

    private void synthesizeAndPlay(String text) {
        if (!ttsReady || offlineTts == null) {
            Log.w(TAG, "TTS not ready, skipping: " + text);
            return;
        }

        Log.d(TAG, "TTS speaking: " + text);
        callJS("onTTSStart", "");

        GeneratedAudio audio = offlineTts.generate(text, 0, 1.0f);
        float[] samples = audio.getSamples();
        int sampleRate = audio.getSampleRate();

        if (samples == null || samples.length == 0) {
            Log.w(TAG, "TTS generated empty audio");
            return;
        }

        // Convert float samples to 16-bit PCM
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short val = (short) Math.max(-32768, Math.min(32767, samples[i] * 32767));
            pcm[i * 2] = (byte) (val & 0xff);
            pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
        }

        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                pcm.length, AudioTrack.MODE_STATIC);

        track.write(pcm, 0, pcm.length);
        track.play();

        float durationMs = (float) samples.length / sampleRate * 1000;
        try { Thread.sleep((long) durationMs + 50); } catch (InterruptedException ignored) {}

        track.release();
        Log.d(TAG, "TTS done: " + text);
    }

    // ===== Helpers =====

    private void callJS(String functionName, String arg) {
        mainHandler.post(() -> webView.evaluateJavascript(
                String.format("window.%s('%s')", functionName, arg), null));
    }

    private String escapeJS(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    public void release() {
        isRecording.set(false);
        executor.shutdown();
        if (offlineTts != null) offlineTts.release();
    }
}
