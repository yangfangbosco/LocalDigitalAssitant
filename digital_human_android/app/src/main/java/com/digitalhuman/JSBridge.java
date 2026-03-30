package com.digitalhuman;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class JSBridge {

    private static final String TAG = "JSBridge";
    private static final int SAMPLE_RATE = 16000;

    private final WebView webView;
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;

    private AudioRecord audioRecord;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private float[] recordedAudio;

    private OfflineTts offlineTts;
    private boolean ttsReady = false;

    public JSBridge(WebView webView, Context context) {
        this.webView = webView;
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void initTts(AssetManager assetManager, String modelDir) {
        try {
            // Copy espeak-ng-data from assets to files dir (sherpa-onnx requires filesystem path)
            String espeakDir = modelDir + "/espeak-ng-data";
            copyAssetDir(assetManager, "espeak-ng-data", espeakDir);

            // Piper VITS model (60MB, fast on ARM)
            OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig(
                "piper-amy-medium.onnx",  // model (read from assets via assetManager)
                "",                        // lexicon
                "piper-tokens.txt",       // tokens
                espeakDir,                 // dataDir (absolute path, filesystem)
                "",                        // dictDir
                0.667f,                    // noiseScale
                0.8f,                      // noiseScaleW
                1.0f                       // lengthScale
            );

            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setVits(vitsConfig);
            modelConfig.setNumThreads(4);
            modelConfig.setDebug(false);

            OfflineTtsConfig ttsConfig = new OfflineTtsConfig();
            ttsConfig.setModel(modelConfig);

            offlineTts = new OfflineTts(assetManager, ttsConfig);
            ttsReady = true;
            Log.i(TAG, "TTS: Kokoro initialized, sampleRate=" + offlineTts.sampleRate());
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

    // ===== LLM + RAG + TTS Pipeline =====

    private static final String TTS_POISON = "__END__";
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();

    private void runLLMAndTTS(String userText) {
        // RAG: search FAQ database for relevant answers
        String ragContext = buildRagContext(userText);
        String queryWithContext = userText;
        if (!ragContext.isEmpty()) {
            queryWithContext = "Use the following knowledge to answer. " +
                    "If the answer is in the knowledge base, use it directly.\n\n" +
                    "Knowledge:\n" + ragContext + "\n\nUser question: " + userText;
            Log.d(TAG, "RAG context found, augmented query");
        }

        // TTS queue: LLM puts sentences in, TTS thread consumes and plays
        BlockingQueue<String> ttsQueue = new LinkedBlockingQueue<>();

        // Start TTS consumer thread
        ttsExecutor.execute(() -> {
            boolean first = true;
            while (true) {
                try {
                    String text = ttsQueue.take();
                    if (text.equals(TTS_POISON)) break;
                    if (first) { callJS("onTTSStart", ""); first = false; }
                    synthesizeAndPlay(text);
                } catch (InterruptedException e) {
                    break;
                }
            }
            callJS("onTTSDone", "");
        });

        // LLM producer: generates tokens and sends complete sentences to TTS queue
        long session = InferenceEngine.llmStartChat(queryWithContext);
        StringBuilder sentence = new StringBuilder();
        String token;

        while ((token = InferenceEngine.llmNext(session)) != null) {
            callJS("onLLMToken", escapeJS(token));
            sentence.append(token);

            if (token.matches(".*[.!?;。！？；\\n].*")) {
                String completeSentence = sentence.toString().trim();
                if (!completeSentence.isEmpty()) {
                    ttsQueue.offer(completeSentence);
                }
                sentence.setLength(0);
            }
        }

        String remaining = sentence.toString().trim();
        if (!remaining.isEmpty()) {
            ttsQueue.offer(remaining);
        }

        callJS("onLLMDone", "");
        ttsQueue.offer(TTS_POISON); // Signal TTS thread to finish
    }

    private String buildRagContext(String query) {
        FaqDatabase db = FaqDatabase.getInstance(context);
        List<FaqDatabase.FaqItem> results = db.search(query, 3);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (FaqDatabase.FaqItem faq : results) {
            sb.append("Q: ").append(faq.question).append("\n");
            sb.append("A: ").append(faq.answer).append("\n\n");
        }
        return sb.toString().trim();
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

        if (samples == null || samples.length == 0) return;

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

        // Send volume levels to JS for lip sync while playing
        int chunkSize = sampleRate / 15; // ~15fps updates
        int totalChunks = samples.length / chunkSize;
        for (int c = 0; c < totalChunks; c++) {
            float rms = 0;
            int start = c * chunkSize;
            for (int i = start; i < start + chunkSize && i < samples.length; i++) {
                rms += samples[i] * samples[i];
            }
            rms = (float) Math.sqrt(rms / chunkSize);
            float mouthOpen = Math.min(1.0f, rms * 5.0f);
            callJS("onMouthUpdate", String.format("%.2f", mouthOpen));
            try { Thread.sleep(1000 / 15); } catch (InterruptedException ignored) {}
        }

        // Wait for playback to finish
        float playedMs = totalChunks * (1000f / 15);
        float remainMs = (float) samples.length / sampleRate * 1000 - playedMs;
        if (remainMs > 0) {
            try { Thread.sleep((long) remainMs + 50); } catch (InterruptedException ignored) {}
        }
        callJS("onMouthUpdate", "0");
        track.release();
    }

    // ===== Helpers =====

    private void copyAssetDir(AssetManager am, String assetDir, String destDir) {
        File dest = new File(destDir);
        if (dest.exists()) return; // already copied
        dest.mkdirs();
        try {
            String[] files = am.list(assetDir);
            if (files == null) return;
            for (String f : files) {
                String assetPath = assetDir + "/" + f;
                String destPath = destDir + "/" + f;
                try {
                    InputStream in = am.open(assetPath);
                    OutputStream out = new FileOutputStream(destPath);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    out.close();
                    in.close();
                } catch (Exception e) {
                    // Might be a directory, recurse
                    copyAssetDir(am, assetPath, destPath);
                }
            }
            Log.d(TAG, "Copied asset dir: " + assetDir);
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy asset dir: " + assetDir, e);
        }
    }

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
