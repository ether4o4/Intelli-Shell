package com.intellishell.ai;

import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Streaming client for any OpenAI-compatible chat endpoint.
 * <p>
 * The same wire format covers everything IntelliShell talks to: OpenAI,
 * OpenRouter, DeepSeek, Groq, and — importantly — a local {@code llama-server}
 * running inside Termux. So cloud and on-device models share one code path;
 * only the base URL and key differ.
 */
public final class LlmClient {

    /** Receives streamed output. All callbacks arrive on the calling thread. */
    public interface Listener {
        void onDelta(String text);
        void onDone(String fullText);
        void onError(String message);
    }

    /** One chat turn. */
    public static final class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private final String mBaseUrl;
    private final String mApiKey;
    private final String mModel;

    private volatile boolean mAborted;
    private volatile HttpURLConnection mConnection;

    public LlmClient(String baseUrl, String apiKey, String model) {
        mBaseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        mApiKey = apiKey == null ? "" : apiKey.trim();
        mModel = model == null ? "" : model.trim();
    }

    /** Abort an in-flight request; the listener gets no further deltas. */
    public void abort() {
        mAborted = true;
        HttpURLConnection c = mConnection;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // Already tearing down.
            }
        }
    }

    /**
     * Stream a completion. Blocks until the response finishes, so call it off the
     * main thread.
     */
    public void stream(List<Message> messages, Listener listener) {
        mAborted = false;
        HttpURLConnection conn = null;
        try {
            JSONArray msgs = new JSONArray();
            for (Message m : messages) {
                msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            JSONObject body = new JSONObject()
                .put("model", mModel)
                .put("messages", msgs)
                .put("stream", true);

            conn = (HttpURLConnection) new URL(mBaseUrl + "/chat/completions").openConnection();
            mConnection = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            if (!mApiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code < 200 || code > 299) {
                listener.onError("HTTP " + code + " " + readError(conn));
                return;
            }

            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (mAborted) return;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    String delta = parseDelta(data);
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        listener.onDelta(delta);
                    }
                }
            }
            if (!mAborted) listener.onDone(full.toString());
        } catch (Exception e) {
            if (!mAborted) {
                listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        } finally {
            mConnection = null;
            if (conn != null) conn.disconnect();
        }
    }

    /** Pull {@code choices[0].delta.content} out of one SSE payload. */
    private static String parseDelta(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject choice = choices.getJSONObject(0);
            JSONObject delta = choice.optJSONObject("delta");
            if (delta != null) return delta.optString("content", "");
            // Some servers stream non-delta chunks; fall back to the message body.
            JSONObject message = choice.optJSONObject("message");
            return message == null ? null : message.optString("content", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String readError(HttpURLConnection conn) {
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 600) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
