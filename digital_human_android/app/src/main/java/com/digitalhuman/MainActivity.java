package com.digitalhuman;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.PermissionRequest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DigitalHuman";
    private static final int PERMISSION_REQUEST_CODE = 1;

    private WebView webView;
    private JSBridge jsBridge;
    private AdminServer adminServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        }

        WebView.setWebContentsDebuggingEnabled(true); // TODO: set false for production
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.d("WebConsole", msg.message());
                return true;
            }
        });

        jsBridge = new JSBridge(webView, this);
        webView.addJavascriptInterface(jsBridge, "NativeBridge");

        initModels();
    }

    private void initModels() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            String modelDir = getFilesDir().getAbsolutePath() + "/models";
            new File(modelDir).mkdirs();

            // Copy all models from assets on first run
            copyAssetIfNeeded("whisper-tiny.en.bin", modelDir);
            copyAssetIfNeeded("qwen2.5-0.5b-q4.gguf", modelDir);
            copyAssetIfNeeded("kokoro-model.onnx", modelDir);
            copyAssetIfNeeded("kokoro-tokens.txt", modelDir);
            copyAssetIfNeeded("kokoro-voices.bin", modelDir);

            boolean asrOk = InferenceEngine.asrInit(modelDir + "/whisper-tiny.en.bin");
            Log.d(TAG, "ASR init: " + asrOk);

            boolean llmOk = InferenceEngine.llmInit(
                    modelDir + "/qwen2.5-0.5b-q4.gguf",
                    "You are a helpful digital human assistant. Keep your responses concise and conversational."
            );
            Log.d(TAG, "LLM init: " + llmOk);

            jsBridge.initTts(getAssets(), modelDir);
            Log.d(TAG, "TTS init done");

            // Start admin server on port 8080
            try {
                adminServer = new AdminServer(MainActivity.this, 8080);
                adminServer.start();
                Log.d(TAG, "Admin server started at http://" + adminServer.getDeviceIp() + ":8080");
            } catch (Exception e) {
                Log.e(TAG, "Admin server failed to start", e);
            }

            runOnUiThread(() -> webView.loadUrl("file:///android_asset/index.html"));
        });
    }

    private void copyAssetIfNeeded(String filename, String destDir) {
        File destFile = new File(destDir, filename);
        if (destFile.exists()) return;

        try {
            InputStream in = getAssets().open(filename);
            OutputStream out = new FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.close();
            in.close();
            Log.d(TAG, "Copied asset: " + filename);
        } catch (Exception e) {
            Log.w(TAG, "Asset not found: " + filename);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (jsBridge != null) jsBridge.release();
        if (adminServer != null) adminServer.stop();
        InferenceEngine.asrRelease();
        InferenceEngine.llmRelease();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        }
    }
}
