package com.intellishell.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.intellishell.R;
import com.intellishell.ai.LlamaManager;
import com.intellishell.ai.LocalModel;
import com.intellishell.bridge.TermuxBridge;

import java.net.URI;

/**
 * The AI Config tab: which brain the assistant uses, and how to reach it.
 * <p>
 * Two answers are supported. A cloud endpoint (any OpenAI-shaped API — base URL, model
 * name, key), or a llama.cpp server running inside Termux on loopback. The local path
 * is what the buttons here manage: install the package, fetch a GGUF, start and stop
 * the server. None of that runs in this process — every action is a shell snippet from
 * {@link LlamaManager} handed to {@link TermuxBridge}.
 * <p>
 * Settings live in the {@code intellishell} preference file, which the AI client reads
 * directly. Values are flushed on every action and in {@code onPause}, so a user who
 * types a port and immediately taps "Start server" gets the port they typed.
 */
public class ConfigFragment extends Fragment {

    public static final String PREFS = "intellishell";

    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_CLOUD_BASE_URL = "cloud.baseUrl";
    public static final String KEY_CLOUD_MODEL = "cloud.model";
    public static final String KEY_CLOUD_KEY = "cloud.key";
    public static final String KEY_HF_TOKEN = "hf.token";
    public static final String KEY_LOCAL_MODEL_ID = "local.modelId";
    public static final String KEY_LOCAL_PORT = "local.port";
    public static final String KEY_LOCAL_CTX = "local.ctx";
    public static final String KEY_LOCAL_EXTERNAL = "local.externalServer";
    public static final String KEY_LOCAL_SERVER_URL = "local.serverUrl";

    public static final String PROVIDER_CLOUD = "cloud";
    public static final String PROVIDER_LOCAL = "local";

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_CTX = 4096;

    /** llama.cpp needs a moment to bind before a probe means anything. */
    private static final long PROBE_DELAY_MS = 4000L;

    private SharedPreferences prefs;

    private ScrollView root;
    private TextView termuxStatus;
    private Button termuxAction;
    private RadioButton providerCloud;
    private RadioButton providerLocal;
    private EditText baseUrlField;
    private EditText modelField;
    private EditText keyField;
    private EditText hfTokenField;
    private EditText portField;
    private EditText ctxField;
    private EditText serverUrlField;
    private SwitchCompat externalSwitch;
    private TextView serverStatus;
    private LinearLayout modelList;

    /** Catalog id of the selected model, or empty. Mirrors {@link #KEY_LOCAL_MODEL_ID}. */
    private String selectedModelId = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        root = (ScrollView) inflater.inflate(R.layout.fragment_config, container, false);

        termuxStatus = root.findViewById(R.id.config_termux_status);
        termuxAction = root.findViewById(R.id.config_termux_action);
        providerCloud = root.findViewById(R.id.config_provider_cloud);
        providerLocal = root.findViewById(R.id.config_provider_local);
        baseUrlField = root.findViewById(R.id.config_base_url);
        modelField = root.findViewById(R.id.config_model);
        keyField = root.findViewById(R.id.config_key);
        hfTokenField = root.findViewById(R.id.config_hf_token);
        portField = root.findViewById(R.id.config_port);
        ctxField = root.findViewById(R.id.config_ctx);
        serverUrlField = root.findViewById(R.id.config_server_url);
        externalSwitch = root.findViewById(R.id.config_external_switch);
        serverStatus = root.findViewById(R.id.config_server_status);
        modelList = root.findViewById(R.id.config_model_list);

        load();
        renderCatalog();

        externalSwitch.setOnCheckedChangeListener((button, checked) -> {
            serverUrlField.setEnabled(checked);
            persist();
            refreshServerStatus();
        });

        termuxAction.setOnClickListener(v -> openTermux());

        root.<Button>findViewById(R.id.config_install_llama)
            .setOnClickListener(v -> installLlama());
        root.<Button>findViewById(R.id.config_start_server)
            .setOnClickListener(v -> startServer());
        root.<Button>findViewById(R.id.config_stop_server)
            .setOnClickListener(v -> stopServer());
        root.<Button>findViewById(R.id.config_check_server)
            .setOnClickListener(v -> refreshServerStatus());
        root.<Button>findViewById(R.id.config_save)
            .setOnClickListener(v -> {
                persist();
                toast(R.string.dash_config_saved);
                refreshServerStatus();
            });

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Termux can be installed, or the server started by hand, while we were away.
        refreshTermuxStatus();
        refreshServerStatus();
    }

    @Override
    public void onPause() {
        // The user may be leaving for the chat screen, which reads these immediately.
        persist();
        super.onPause();
    }

    // -------------------------------------------------------------- persistence

    private void load() {
        String provider = prefs.getString(KEY_PROVIDER, PROVIDER_CLOUD);
        providerLocal.setChecked(PROVIDER_LOCAL.equals(provider));
        providerCloud.setChecked(!PROVIDER_LOCAL.equals(provider));

        baseUrlField.setText(prefs.getString(KEY_CLOUD_BASE_URL, ""));
        modelField.setText(prefs.getString(KEY_CLOUD_MODEL, ""));
        keyField.setText(prefs.getString(KEY_CLOUD_KEY, ""));
        hfTokenField.setText(prefs.getString(KEY_HF_TOKEN, ""));

        selectedModelId = prefs.getString(KEY_LOCAL_MODEL_ID, "");

        portField.setText(String.valueOf(prefs.getInt(KEY_LOCAL_PORT, DEFAULT_PORT)));
        ctxField.setText(String.valueOf(prefs.getInt(KEY_LOCAL_CTX, DEFAULT_CTX)));

        boolean external = prefs.getBoolean(KEY_LOCAL_EXTERNAL, false);
        externalSwitch.setChecked(external);
        serverUrlField.setText(prefs.getString(KEY_LOCAL_SERVER_URL, ""));
        serverUrlField.setEnabled(external);
    }

    private void persist() {
        if (prefs == null) return;
        prefs.edit()
            .putString(KEY_PROVIDER, providerLocal.isChecked() ? PROVIDER_LOCAL : PROVIDER_CLOUD)
            .putString(KEY_CLOUD_BASE_URL, text(baseUrlField))
            .putString(KEY_CLOUD_MODEL, text(modelField))
            .putString(KEY_CLOUD_KEY, text(keyField))
            .putString(KEY_HF_TOKEN, text(hfTokenField))
            .putString(KEY_LOCAL_MODEL_ID, selectedModelId)
            .putInt(KEY_LOCAL_PORT, port())
            .putInt(KEY_LOCAL_CTX, contextSize())
            .putBoolean(KEY_LOCAL_EXTERNAL, externalSwitch.isChecked())
            .putString(KEY_LOCAL_SERVER_URL, text(serverUrlField))
            .apply();
    }

    // ------------------------------------------------------------- model catalog

    private void renderCatalog() {
        modelList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        for (final LocalModel model : LocalModel.CATALOG) {
            View row = inflater.inflate(R.layout.row_model, modelList, false);

            TextView name = row.findViewById(R.id.row_model_name);
            name.setText(model.name);

            TextView meta = row.findViewById(R.id.row_model_meta);
            meta.setText(getString(R.string.dash_config_model_meta,
                model.params, model.size, model.note));

            RadioButton select = row.findViewById(R.id.row_model_select);
            select.setChecked(model.id.equals(selectedModelId));

            // Selection is driven from the row, not the RadioButton: these rows are
            // added dynamically, so nothing else would clear the previous choice.
            row.setOnClickListener(v -> {
                selectedModelId = model.id;
                persist();
                renderCatalog();
            });

            Button download = row.findViewById(R.id.row_model_download);
            download.setOnClickListener(v -> download(model));

            modelList.addView(row);
        }
    }

    private void download(LocalModel model) {
        if (!requireTermux()) return;
        persist();

        String token = text(hfTokenField);
        boolean sent = TermuxBridge.runInBackground(requireContext(),
            LlamaManager.buildDownloadModelScript(model, token.isEmpty() ? null : token));

        if (sent) {
            Toast.makeText(requireContext(),
                getString(R.string.dash_config_downloading, model.name),
                Toast.LENGTH_LONG).show();
        } else {
            toastRefused();
        }
    }

    // ------------------------------------------------------------ server control

    private void installLlama() {
        if (!requireTermux()) return;
        persist();
        send(LlamaManager.buildInstallLlamaScript());
    }

    private void startServer() {
        if (!requireTermux()) return;
        persist();

        LocalModel model = LocalModel.byId(selectedModelId);
        if (model == null) {
            toast(R.string.dash_config_need_model);
            return;
        }

        if (send(LlamaManager.buildStartServerScript(model, port(), contextSize()))) {
            scheduleProbe();
        }
    }

    private void stopServer() {
        if (!requireTermux()) return;
        persist();
        if (send(LlamaManager.buildStopServerScript(port()))) {
            scheduleProbe();
        }
    }

    private boolean send(String script) {
        boolean sent = TermuxBridge.runInBackground(requireContext(), script);
        if (sent) {
            toast(R.string.dash_config_sent);
        } else {
            toastRefused();
        }
        return sent;
    }

    private void scheduleProbe() {
        serverStatus.setText(R.string.dash_config_server_checking);
        // Posted on the view so it dies with the fragment rather than firing into a
        // detached one.
        serverStatus.postDelayed(this::refreshServerStatus, PROBE_DELAY_MS);
    }

    // -------------------------------------------------------------------- status

    private void refreshTermuxStatus() {
        if (!isAdded()) return;
        Context context = requireContext();

        boolean installed = TermuxBridge.isTermuxInstalled(context);
        boolean permitted = installed && TermuxBridge.hasRunCommandPermission(context);

        StringBuilder text = new StringBuilder();
        if (!installed) {
            text.append(getString(R.string.dash_config_termux_missing));
        } else {
            text.append(getString(R.string.dash_config_termux_ok)).append('\n');
            text.append(getString(permitted
                ? R.string.dash_config_termux_perm_ok
                : R.string.dash_config_termux_perm_missing));
        }

        termuxStatus.setText(text.toString());
        termuxStatus.setTextColor(ContextCompat.getColor(context,
            installed && permitted ? R.color.dash_ok : R.color.dash_danger));

        termuxAction.setText(installed
            ? R.string.dash_config_termux_open
            : R.string.dash_config_termux_install);
    }

    private void refreshServerStatus() {
        if (!isAdded()) return;

        final String host = serverHost();
        final int probePort = serverPort();
        serverStatus.setText(R.string.dash_config_server_checking);

        // A socket connect on the UI thread is a NetworkOnMainThreadException.
        DashboardActivity.io(() -> {
            final boolean up = LlamaManager.isServerUp(host, probePort);
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                serverStatus.setText(getString(up
                        ? R.string.dash_config_server_up
                        : R.string.dash_config_server_down,
                    host, probePort));
                serverStatus.setTextColor(ContextCompat.getColor(requireContext(),
                    up ? R.color.dash_ok : R.color.dash_muted));
            });
        });
    }

    /** Host to probe: whatever the user pointed us at, else Termux on loopback. */
    private String serverHost() {
        if (externalSwitch.isChecked()) {
            String host = hostOf(text(serverUrlField));
            if (host != null) return host;
        }
        return TermuxBridge.PTY_HOST;
    }

    private int serverPort() {
        if (externalSwitch.isChecked()) {
            int fromUrl = portOf(text(serverUrlField));
            if (fromUrl > 0) return fromUrl;
        }
        return port();
    }

    /**
     * Host component of a URL, or null when it cannot be read. Parsed with
     * {@link URI} rather than by hand so a typo produces "unknown", not a probe
     * against something unintended.
     */
    @Nullable
    private static String hostOf(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null || host.isEmpty() ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Explicit port from a URL, or its scheme default; {@code -1} when unreadable. */
    private static int portOf(String url) {
        if (url == null || url.trim().isEmpty()) return -1;
        try {
            URI uri = URI.create(url.trim());
            if (uri.getPort() > 0) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------- Termux

    private void openTermux() {
        Context context = requireContext();
        if (TermuxBridge.isTermuxInstalled(context)) {
            TermuxBridge.launchTermux(context);
            return;
        }
        try {
            startActivity(TermuxBridge.termuxInstallIntent());
        } catch (ActivityNotFoundException e) {
            // A device with no browser at all. Nothing to fall back to.
            toast(R.string.dash_config_no_store);
        }
    }

    private boolean requireTermux() {
        if (TermuxBridge.isTermuxInstalled(requireContext())) return true;
        toast(R.string.dash_config_need_termux);
        refreshTermuxStatus();
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private int port() {
        return clamp(parseInt(text(portField), DEFAULT_PORT), 1, 65535, DEFAULT_PORT);
    }

    private int contextSize() {
        return clamp(parseInt(text(ctxField), DEFAULT_CTX), 512, 32768, DEFAULT_CTX);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) return fallback;
        return value;
    }

    private static String text(EditText field) {
        return field.getText().toString().trim();
    }

    private void toast(int messageRes) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show();
    }

    private void toastRefused() {
        Toast.makeText(requireContext(), R.string.dash_config_refused, Toast.LENGTH_LONG).show();
    }
}
