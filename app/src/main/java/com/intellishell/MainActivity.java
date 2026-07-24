package com.intellishell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.intellishell.ai.Agent;
import com.intellishell.ai.LlmClient;
import com.intellishell.ai.LocalModel;
import com.intellishell.bridge.ShellConnection;
import com.intellishell.bridge.TerminalController;
import com.intellishell.bridge.TermuxBridge;
import com.intellishell.data.Db;
import com.intellishell.data.Script;
import com.intellishell.ui.ConfigFragment;
import com.intellishell.ui.DashboardActivity;
import com.intellishell.ui.TermClients;
import com.termux.terminal.SocketTerminalSession;
import com.termux.view.TerminalView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;

/**
 * IntelliShell's home screen: an AI chat above a live terminal.
 * <p>
 * The terminal is a real interactive shell running inside the stock Termux app,
 * reached over a loopback pty socket. The AI shares that exact session — its
 * commands run where the user is working, visibly, and it can be stopped at any
 * time. Tapping the input grows the chat pane so the reply and what you're typing
 * both stay visible above the keyboard.
 */
public final class MainActivity extends AppCompatActivity implements Agent.Host {

    private static final int REQ_DASHBOARD = 101;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mAgentExecutor = Executors.newSingleThreadExecutor();

    private ScrollView mChatScroll;
    private LinearLayout mChatList;
    private EditText mInput;
    private TextView mStatus;
    private LinearLayout mTerminalContainer;
    private TerminalView mTerminalView;

    private ShellConnection mShell;
    private TerminalController mController;
    private Agent mAgent;

    private TextView mCurrentAssistantView;
    private volatile boolean mAgentRunning;

    // Answer channel for the confirm-destructive dialog, which blocks the agent thread.
    private final SynchronousQueue<Boolean> mConfirmAnswer = new SynchronousQueue<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mChatScroll = findViewById(R.id.chatScroll);
        mChatList = findViewById(R.id.chatList);
        mInput = findViewById(R.id.input);
        mStatus = findViewById(R.id.status);
        mTerminalContainer = findViewById(R.id.terminalContainer);

        Button stopBtn = findViewById(R.id.stopBtn);
        Button dashBtn = findViewById(R.id.dashBtn);
        Button sendBtn = findViewById(R.id.sendBtn);
        Button scriptsBtn = findViewById(R.id.scriptsBtn);

        stopBtn.setOnClickListener(v -> stopAgent());
        dashBtn.setOnClickListener(v -> openDashboard(DashboardActivity.TAB_CONFIG));
        sendBtn.setOnClickListener(v -> onSend());
        scriptsBtn.setOnClickListener(v -> showScriptPicker());

        mAgent = new Agent(this);

        setupTerminalView();
        setupKeyboardBehavior();
        connectShell();

        addSystemBubble("IntelliShell — your AI shares the live Termux terminal below. "
            + "Tell it what to build. Open ▦ for models, scripts, notes and memory.");
    }

    // ---- Terminal ----------------------------------------------------------

    private void setupTerminalView() {
        mTerminalView = new TerminalView(this, null);
        mTerminalView.setTextSize(dpToPx(13));
        mTerminalView.setTypeface(Typeface.MONOSPACE);
        mTerminalView.setBackgroundColor(0xff000000);
        mTerminalView.setTerminalViewClient(new TermClients.ViewClient(mTerminalView));
        mTerminalContainer.addView(mTerminalView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private void connectShell() {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            addSystemBubble("Stock Termux isn't installed. IntelliShell uses it as the Linux backend. "
                + "Install Termux (F-Droid build), then reopen this app.");
            offerInstallTermux();
            return;
        }

        TermClients.SessionClient sessionClient = new TermClients.SessionClient(
            new TermClients.SessionClient.Events() {
                @Override public void onScreenUpdated() {
                    if (mTerminalView != null) mTerminalView.onScreenUpdated();
                }
                @Override public void onSessionEnded() {
                    setStatus("shell closed");
                }
            });

        mShell = new ShellConnection(this);
        setStatus("connecting…");
        mShell.connect(sessionClient, 2000, new ShellConnection.Callback() {
            @Override public void onConnecting(String message) { setStatus("connecting…"); }

            @Override public void onConnected(SocketTerminalSession session, TerminalController controller) {
                mController = controller;
                mTerminalView.attachSession(session);
                mTerminalView.onScreenUpdated();
                setStatus("shell ready");
            }

            @Override public void onFailed(String reason) {
                setStatus("shell offline");
                addSystemBubble(reason);
            }
        });
    }

    private void offerInstallTermux() {
        try {
            startActivity(TermuxBridge.termuxInstallIntent());
        } catch (Exception ignored) {
            // No browser; the message already told them what to do.
        }
    }

    // ---- Keyboard-aware split ---------------------------------------------

    /**
     * When the soft keyboard is up, grow the chat to ~3/4 of the panes so the
     * reply and the text field stay visible; restore the even split when it hides.
     */
    private void setupKeyboardBehavior() {
        final View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int screenHeight = root.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;
            boolean keyboardUp = keypadHeight > screenHeight * 0.15;
            applySplit(keyboardUp ? 74 : 54, keyboardUp ? 26 : 46);
        });
    }

    private void applySplit(int chatWeight, int terminalWeight) {
        LinearLayout.LayoutParams chatLp = (LinearLayout.LayoutParams) mChatScroll.getLayoutParams();
        LinearLayout.LayoutParams termLp = (LinearLayout.LayoutParams) mTerminalContainer.getLayoutParams();
        if (chatLp.weight == chatWeight && termLp.weight == terminalWeight) return;
        chatLp.weight = chatWeight;
        termLp.weight = terminalWeight;
        mChatScroll.setLayoutParams(chatLp);
        mTerminalContainer.setLayoutParams(termLp);
    }

    // ---- Chat --------------------------------------------------------------

    private void onSend() {
        String text = mInput.getText().toString().trim();
        if (text.isEmpty() || mAgentRunning) return;

        if (!hasModelConfigured()) {
            addSystemBubble("No model set yet. Open ▦ → AI Config to add a cloud API key or download a local model.");
            openDashboard(DashboardActivity.TAB_CONFIG);
            return;
        }

        mInput.setText("");
        addUserBubble(text);
        mCurrentAssistantView = null;
        mAgentRunning = true;

        mAgentExecutor.execute(() -> mAgent.send(text));
    }

    private void stopAgent() {
        mAgent.stop();
        if (mController != null) mController.cancel();
        mAgentRunning = false;
        setStatus("stopped");
        // Release the confirm dialog if it happened to be waiting.
        mConfirmAnswer.offer(Boolean.FALSE);
    }

    private void showScriptPicker() {
        List<Script> scripts = Db.get(this).listScripts();
        if (scripts.isEmpty()) {
            Toast.makeText(this, "No saved scripts yet — add some in ▦ → Scripts", Toast.LENGTH_SHORT).show();
            openDashboard(DashboardActivity.TAB_SCRIPTS);
            return;
        }
        final String[] names = new String[scripts.size()];
        for (int i = 0; i < scripts.size(); i++) {
            Script s = scripts.get(i);
            names[i] = s.name + "  ·  " + (s.language == null ? "" : s.language);
        }
        new AlertDialog.Builder(this)
            .setTitle("Pull a script into chat")
            .setItems(names, (dialog, which) -> {
                Script s = scripts.get(which);
                addSystemBubble("Loaded script into context: " + s.name);
                mAgent.addContext("[saved script \"" + s.name + "\" (" + s.language + ")]\n"
                    + "```\n" + s.body + "\n```\n"
                    + "Keep this available; the user may ask you to run, explain, or modify it.");
                mInput.requestFocus();
            })
            .setNeutralButton("Run one", (dialog, which) -> showScriptRunPicker(scripts, names))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showScriptRunPicker(List<Script> scripts, String[] names) {
        new AlertDialog.Builder(this)
            .setTitle("Run a script in the terminal")
            .setItems(names, (dialog, which) -> runScriptNow(scripts.get(which)))
            .show();
    }

    private void runScriptNow(Script s) {
        if (mController == null) {
            Toast.makeText(this, "Shell isn't connected yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String cmd = s.effectiveCommand();
        addSystemBubble("Running script: " + s.name);
        mAgentExecutor.execute(() -> {
            try {
                String out = mController.run(cmd);
                mMain.post(() -> addSystemBubble(out.isEmpty() ? "(done)" : out));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void openDashboard(int tab) {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.putExtra(DashboardActivity.EXTRA_TAB, tab);
        startActivityForResult(intent, REQ_DASHBOARD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DASHBOARD && resultCode == Activity.RESULT_OK && data != null) {
            String script = data.getStringExtra(DashboardActivity.EXTRA_RUN_SCRIPT);
            if (!TextUtils.isEmpty(script) && mController != null) {
                addSystemBubble("Running from dashboard…");
                final String cmd = script;
                mAgentExecutor.execute(() -> {
                    try {
                        String out = mController.run(cmd);
                        mMain.post(() -> addSystemBubble(out.isEmpty() ? "(done)" : out));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }

    // ---- Agent.Host --------------------------------------------------------

    @Override
    public void streamModel(List<LlmClient.Message> messages, LlmClient.Listener listener) {
        LlmClient client = buildLlmClient();
        if (client == null) {
            listener.onError("No model configured.");
            return;
        }
        // We're already on the agent worker thread; stream synchronously.
        client.stream(messages, listener);
    }

    @Override
    public String runInTerminal(String command) throws InterruptedException {
        if (mController == null) return "[shell not connected]";
        return mController.run(command);
    }

    @Override
    public boolean confirmDangerous(String command, String reason) throws InterruptedException {
        mMain.post(() -> new AlertDialog.Builder(this)
            .setTitle("Run this command?")
            .setMessage(reason + "\n\n" + command)
            .setCancelable(false)
            .setPositiveButton("Run", (d, w) -> mConfirmAnswer.offer(Boolean.TRUE))
            .setNegativeButton("Skip", (d, w) -> mConfirmAnswer.offer(Boolean.FALSE))
            .show());
        // Block the agent thread until the user (or STOP) answers.
        return mConfirmAnswer.take();
    }

    @Override
    public String terminalSnapshot() {
        try {
            if (mTerminalView != null && mTerminalView.mEmulator != null) {
                String text = mTerminalView.mEmulator.getScreen().getTranscriptText();
                if (text != null && text.length() > 2000) {
                    return text.substring(text.length() - 2000);
                }
                return text;
            }
        } catch (Exception ignored) {
            // Snapshot is best-effort context.
        }
        return "";
    }

    @Override
    public String memoryBlock() {
        return Db.get(this).buildMemoryPrompt();
    }

    @Override
    public void rememberFact(String category, String content) {
        Db.get(this).rememberFact(category, content);
    }

    @Override public void onAssistantDelta(String text) {
        mMain.post(() -> appendAssistantText(text));
    }

    @Override public void onAssistantMessageComplete(String text) {
        mMain.post(() -> mCurrentAssistantView = null);
    }

    @Override public void onCommandStarted(String command) {
        mMain.post(() -> setStatus("running: " + firstLine(command)));
    }

    @Override public void onCommandOutput(String output) {
        // The user sees output live in the terminal pane; nothing to add to chat.
    }

    @Override public void onStatus(String status) {
        mMain.post(() -> setStatus(status));
    }

    @Override public void onError(String message) {
        mMain.post(() -> addSystemBubble("⚠ " + message));
    }

    @Override public void onFinished() {
        mMain.post(() -> {
            mAgentRunning = false;
            setStatus("idle");
        });
    }

    // ---- Chat rendering ----------------------------------------------------

    private void appendAssistantText(String delta) {
        if (mCurrentAssistantView == null) {
            mCurrentAssistantView = makeBubble("", 0xffe6edf3, Gravity.START, 0xff161b22);
            mChatList.addView(mCurrentAssistantView);
        }
        mCurrentAssistantView.setText(mCurrentAssistantView.getText() + delta);
        scrollChatToBottom();
    }

    private void addUserBubble(String text) {
        mChatList.addView(makeBubble(text, 0xffe6edf3, Gravity.END, 0xff1b3a5c));
        scrollChatToBottom();
    }

    private void addSystemBubble(String text) {
        mChatList.addView(makeBubble(text, 0xff9aa4b0, Gravity.START, 0xff11151c));
        scrollChatToBottom();
    }

    private TextView makeBubble(String text, int textColor, int gravity, int bg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(14.5f);
        tv.setTextIsSelectable(true);
        tv.setBackgroundColor(bg);
        int pad = dpToPx(10);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.setMargins(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void scrollChatToBottom() {
        mChatScroll.post(() -> mChatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String s) {
        mStatus.setText(s);
    }

    // ---- Model config ------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(ConfigFragment.PREFS, Context.MODE_PRIVATE);
    }

    private boolean hasModelConfigured() {
        return buildLlmClient() != null;
    }

    /** Build the client for the currently-selected provider, or null if unset. */
    private LlmClient buildLlmClient() {
        SharedPreferences p = prefs();
        String provider = p.getString(ConfigFragment.KEY_PROVIDER, ConfigFragment.PROVIDER_CLOUD);

        if (ConfigFragment.PROVIDER_LOCAL.equals(provider)) {
            String baseUrl;
            if (p.getBoolean(ConfigFragment.KEY_LOCAL_EXTERNAL, false)) {
                baseUrl = p.getString(ConfigFragment.KEY_LOCAL_SERVER_URL, "");
                if (TextUtils.isEmpty(baseUrl)) return null;
            } else {
                int port = p.getInt(ConfigFragment.KEY_LOCAL_PORT, ConfigFragment.DEFAULT_PORT);
                baseUrl = "http://" + TermuxBridge.PTY_HOST + ":" + port + "/v1";
            }
            String modelId = p.getString(ConfigFragment.KEY_LOCAL_MODEL_ID, "");
            LocalModel m = LocalModel.byId(modelId);
            String modelName = m != null ? m.name : "local-model";
            // llama-server ignores the model field, so any label works.
            return new LlmClient(baseUrl, "", modelName);
        }

        String key = p.getString(ConfigFragment.KEY_CLOUD_KEY, "");
        String baseUrl = p.getString(ConfigFragment.KEY_CLOUD_BASE_URL, "https://api.openai.com/v1");
        String model = p.getString(ConfigFragment.KEY_CLOUD_MODEL, "gpt-4o-mini");
        if (TextUtils.isEmpty(key)) return null;
        return new LlmClient(baseUrl, key, model);
    }

    // ---- misc --------------------------------------------------------------

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > 40 ? line.substring(0, 40) + "…" : line;
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unused")
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAgentExecutor.shutdownNow();
    }
}
