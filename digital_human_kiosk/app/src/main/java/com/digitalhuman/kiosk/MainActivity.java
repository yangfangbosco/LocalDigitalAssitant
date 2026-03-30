package com.digitalhuman.kiosk;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Kiosk";
    private static final String PREFS = "kiosk_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final int PERMISSION_REQUEST = 1;

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request mic permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
        }

        // Setup WebView
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

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

        // Long press to open settings
        webView.setOnLongClickListener(v -> {
            showSettingsDialog();
            return true;
        });

        // Load saved URL or show settings
        String savedUrl = getServerUrl();
        if (savedUrl.isEmpty()) {
            showSettingsDialog();
        } else {
            loadServer(savedUrl);
        }
    }

    private void showSettingsDialog() {
        String currentUrl = getServerUrl();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 20);

        EditText input = new EditText(this);
        input.setHint("e.g. 192.168.1.50");
        input.setText(currentUrl.replace("http://", "").replace(":8000", ""));
        input.setTextSize(18);
        input.setSingleLine(true);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Server IP Address")
                .setMessage("Enter the IP of your Digital Human server:")
                .setView(layout)
                .setCancelable(!currentUrl.isEmpty())
                .setPositiveButton("Connect", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (ip.isEmpty()) {
                        Toast.makeText(this, "Please enter an IP address", Toast.LENGTH_SHORT).show();
                        showSettingsDialog();
                        return;
                    }
                    // Build URL
                    if (!ip.startsWith("http")) ip = "http://" + ip;
                    if (!ip.contains(":")) ip += ":8000";

                    saveServerUrl(ip);
                    loadServer(ip);
                })
                .setNeutralButton("Refresh", (dialog, which) -> {
                    String url = getServerUrl();
                    if (!url.isEmpty()) loadServer(url);
                })
                .show();
    }

    private void loadServer(String url) {
        Log.d(TAG, "Loading: " + url);
        Toast.makeText(this, "Connecting to " + url, Toast.LENGTH_SHORT).show();
        webView.loadUrl(url);
    }

    private String getServerUrl() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SERVER_URL, "");
    }

    private void saveServerUrl(String url) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_SERVER_URL, url).apply();
    }

    @Override
    public void onBackPressed() {
        // Long press to open settings, back button does nothing (kiosk mode)
    }
}
