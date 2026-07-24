package com.intellishell.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A single GGUF model IntelliShell knows how to fetch and serve locally.
 * <p>
 * The catalog is deliberately hard-coded rather than fetched from the network.
 * A curated, offline-available list means the model picker still works on a
 * device with no connectivity, and it keeps us from silently recommending a
 * repository that has since been re-quantised or pulled.
 * <p>
 * Entries are ordered by usefulness for an agentic shell assistant: tool-callers
 * first, then general chat, then the small/fast fallbacks that still run on a
 * mid-range phone.
 */
public final class LocalModel {

    /** Stable identifier persisted in preferences; never localise or reformat it. */
    public final String id;
    /** Human-readable name for the picker. */
    public final String name;
    /** Parameter count, e.g. {@code "3B"}. */
    public final String params;
    /** Approximate on-disk size of the quantised file, e.g. {@code "~1.9 GB"}. */
    public final String size;
    /** One-line reason a user would pick this model. */
    public final String note;
    /** Direct download URL of the {@code .gguf} file (a resolve/ link, not blob/). */
    public final String url;

    public LocalModel(String id, String name, String params, String size, String note, String url) {
        this.id = id;
        this.name = name;
        this.params = params;
        this.size = size;
        this.note = note;
        this.url = url;
    }

    /** The curated download catalog, in presentation order. */
    public static final List<LocalModel> CATALOG = Collections.unmodifiableList(
        new ArrayList<>(Arrays.asList(
            new LocalModel(
                "xlam2-3b-fc-q4",
                "xLAM-2 3B Tool-Caller",
                "3B",
                "~1.9 GB",
                "Best-in-class function calling (Salesforce)",
                "https://huggingface.co/Salesforce/xLAM-2-3b-fc-r-gguf/resolve/main/"
                    + "xLAM-2-3B-fc-r-Q4_K_M.gguf"),
            new LocalModel(
                "refinedtoolcall-v5-3b-q6",
                "RefinedToolCall V5 3B",
                "3B",
                "~2.5 GB",
                "Tool-calling & agentic (Qwen2)",
                "https://huggingface.co/RefinedNeuro/RefinedToolCallV5-3b/resolve/main/"
                    + "RefinedToolCallV5-3b-Q6_K.gguf"),
            new LocalModel(
                "hermes3-llama32-3b-abliterated",
                "Hermes 3 Llama-3.2 3B (abliterated)",
                "3B",
                "~2.0 GB",
                "Uncensored, strong instruction following",
                "https://huggingface.co/mradermacher/Hermes-3-Llama-3.2-3B-abliterated-GGUF/"
                    + "resolve/main/Hermes-3-Llama-3.2-3B-abliterated.Q4_K_M.gguf"),
            new LocalModel(
                "vibethinker-3b",
                "VibeThinker 3B",
                "3B",
                "~2.0 GB",
                "Reasoning-focused",
                "https://huggingface.co/RefinedNeuro/VibeThinker-3B-Hermes-GGUF/resolve/main/"
                    + "VibeThinker-3B-Hermes-Q4_K_M.gguf"),
            new LocalModel(
                "nidum-gemma3-4b-uncensored",
                "Nidum Gemma-3 4B (uncensored)",
                "4B",
                "~2.5 GB",
                "Uncensored Gemma 3",
                "https://huggingface.co/nidum/Nidum-Gemma-3-4B-it-Uncensored-GGUF/resolve/main/"
                    + "Nidum-Gemma-3-4B-it-Uncensored-Q4_K_M.gguf"),
            new LocalModel(
                "qwen2.5-3b-instruct-q4",
                "Qwen2.5 3B Instruct",
                "3B",
                "~1.9 GB",
                "Balanced, multilingual",
                "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/"
                    + "qwen2.5-3b-instruct-q4_k_m.gguf"),
            new LocalModel(
                "llama3.2-3b-q4",
                "Llama 3.2 3B",
                "3B",
                "~2.0 GB",
                "Strong general chat",
                "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/"
                    + "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            new LocalModel(
                "gemma2-2b-q4",
                "Gemma 2 2B",
                "2B",
                "~1.6 GB",
                "Lightest, fastest",
                "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/"
                    + "gemma-2-2b-it-Q4_K_M.gguf"),
            new LocalModel(
                "phi3.5-mini-q4",
                "Phi-3.5 Mini",
                "3.8B",
                "~2.2 GB",
                "Great at reasoning",
                "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/"
                    + "Phi-3.5-mini-instruct-Q4_K_M.gguf")
        )));

    /** Catalog lookup by {@link #id}; {@code null} when unknown (e.g. a stale preference). */
    public static LocalModel byId(String id) {
        if (id == null) return null;
        for (LocalModel m : CATALOG) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    /**
     * Builds a one-off catalog entry from a user-supplied GGUF URL.
     * <p>
     * People habitually copy the Hugging Face <em>page</em> link, which serves HTML
     * rather than weights, so a {@code /blob/} segment is rewritten to
     * {@code /resolve/} before validation. Anything that isn't an {@code https}
     * link ending in {@code .gguf} is rejected with {@code null} — downloading
     * arbitrary URLs into the model directory would only fail later, more
     * confusingly, inside llama.cpp.
     *
     * @return a custom model, or {@code null} if the URL is unusable
     */
    public static LocalModel fromUrl(String ggufUrl) {
        if (ggufUrl == null) return null;
        String url = ggufUrl.trim();
        if (url.isEmpty()) return null;

        int blob = url.indexOf("/blob/");
        if (blob >= 0) {
            url = url.substring(0, blob) + "/resolve/" + url.substring(blob + "/blob/".length());
        }

        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("https://")) return null;

        // Interior whitespace and control characters mean this was pasted wrong (or
        // is two URLs run together); curl would reject it as malformed anyway.
        for (int i = 0; i < url.length(); i++) {
            if (Character.isWhitespace(url.charAt(i)) || url.charAt(i) < 0x20) return null;
        }

        // Ignore any ?download=true / #fragment tail when checking the extension.
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        if (!path.toLowerCase(Locale.US).endsWith(".gguf")) return null;

        String file = path.substring(path.lastIndexOf('/') + 1);
        if (file.length() <= ".gguf".length()) return null;
        String base = file.substring(0, file.length() - ".gguf".length());

        return new LocalModel(
            "custom:" + file.toLowerCase(Locale.US),
            base,
            "?",
            "?",
            "Custom GGUF URL",
            url);
    }

    /** Equality follows {@link #id} so the picker can compare against a saved selection. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocalModel)) return false;
        return id.equals(((LocalModel) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name + " (" + params + ", " + size + ")";
    }
}
