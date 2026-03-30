package com.digitalhuman;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible LLM client with streaming support.
 * Works with OpenAI, DeepSeek, Ollama, or any compatible API.
 */
public class LlmClient {

    private static final String TAG = "LlmClient";
    private static final String PREFS = "llm_prefs";

    private final Context context;
    private final List<JSONObject> conversationHistory = new ArrayList<>();

    // Configurable via Admin panel
    private String apiKey;
    private String baseUrl;
    private String model;
    private String systemPrompt;

    public interface TokenCallback {
        void onToken(String token);
    }

    public LlmClient(Context context) {
        this.context = context;
        loadConfig();
    }

    private void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Load defaults from assets config file on first run
        if (!prefs.contains("api_key")) {
            try {
                java.io.InputStream is = context.getAssets().open("llm_config.json");
                byte[] buf = new byte[is.available()];
                is.read(buf);
                is.close();
                org.json.JSONObject cfg = new org.json.JSONObject(new String(buf));
                prefs.edit()
                    .putString("api_key", cfg.optString("api_key", ""))
                    .putString("base_url", cfg.optString("base_url", "https://api.openai.com/v1"))
                    .putString("model", cfg.optString("model", "gpt-4o"))
                    .putString("system_prompt", cfg.optString("system_prompt", "You are a helpful digital human assistant."))
                    .apply();
            } catch (Exception e) {
                Log.w(TAG, "No llm_config.json in assets");
            }
        }

        apiKey = prefs.getString("api_key", "");
        baseUrl = prefs.getString("base_url", "https://api.openai.com/v1");
        model = prefs.getString("model", "gpt-4o");
        systemPrompt = prefs.getString("system_prompt",
                "You are a helpful digital human assistant. Keep your responses concise and conversational.");
    }

    public void saveConfig(String apiKey, String baseUrl, String model, String systemPrompt) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("api_key", apiKey)
                .putString("base_url", baseUrl)
                .putString("model", model)
                .putString("system_prompt", systemPrompt)
                .apply();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }
    public boolean isConfigured() { return !apiKey.isEmpty(); }

    public void clearHistory() {
        conversationHistory.clear();
    }

    /**
     * Stream chat completion. Calls onToken for each token received.
     * Returns the full response when done.
     */
    public String streamChat(String userMessage, TokenCallback callback) throws Exception {
        // Build messages
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        for (JSONObject msg : conversationHistory) {
            messages.put(msg);
        }
        messages.put(new JSONObject().put("role", "user").put("content", userMessage));

        // Add to history
        conversationHistory.add(new JSONObject().put("role", "user").put("content", userMessage));

        // Build request
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);

        String urlStr = baseUrl + "/chat/completions";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();

        if (conn.getResponseCode() != 200) {
            String error = new BufferedReader(new InputStreamReader(conn.getErrorStream()))
                    .lines().reduce("", (a, b) -> a + b);
            throw new Exception("API error " + conn.getResponseCode() + ": " + error);
        }

        // Read SSE stream
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder fullResponse = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data: ")) continue;
            String data = line.substring(6).trim();
            if (data.equals("[DONE]")) break;

            try {
                JSONObject chunk = new JSONObject(data);
                JSONObject delta = chunk.getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("delta");
                if (delta.has("content")) {
                    String token = delta.getString("content");
                    fullResponse.append(token);
                    callback.onToken(token);
                }
            } catch (Exception e) {
                // Skip malformed chunks
            }
        }

        reader.close();
        conn.disconnect();

        // Add assistant response to history
        conversationHistory.add(new JSONObject()
                .put("role", "assistant").put("content", fullResponse.toString()));

        return fullResponse.toString();
    }
}
