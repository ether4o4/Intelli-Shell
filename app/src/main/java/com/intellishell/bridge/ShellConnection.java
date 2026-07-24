package com.intellishell.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.terminal.SocketTerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * Owns the lifecycle of the live shell: it asks stock Termux to start a pty
 * server, connects a {@link SocketTerminalSession} to it, and reports readiness.
 * <p>
 * The sequence is deliberately forgiving of Termux cold-starts: we fire the
 * RUN_COMMAND to launch the server, then poll the loopback port until it accepts
 * a connection (Termux may need a moment to spawn, install socat, and bind).
 */
public final class ShellConnection {

    /** Readiness / failure callbacks, delivered on the main thread. */
    public interface Callback {
        void onConnecting(String message);
        void onConnected(SocketTerminalSession session, TerminalController controller);
        void onFailed(String reason);
    }

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int POLL_INTERVAL_MS = 700;
    private static final int MAX_POLLS = 30; // ~21s for Termux to come up + install socat

    private final Context mAppContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private int mPort;
    private SocketTerminalSession mSession;
    private TerminalController mController;

    public ShellConnection(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public int port() {
        return mPort;
    }

    public SocketTerminalSession session() {
        return mSession;
    }

    public TerminalController controller() {
        return mController;
    }

    /**
     * Start (if needed) and connect to the live shell. Safe to call from the main
     * thread; all the waiting happens on a worker.
     */
    public void connect(TerminalSessionClient client, Integer transcriptRows, Callback callback) {
        if (!TermuxBridge.isTermuxInstalled(mAppContext)) {
            callback.onFailed("Termux is not installed.");
            return;
        }

        mPort = TermuxBridge.findFreePort();
        callback.onConnecting("Starting Termux shell…");

        new Thread("ShellConnect") {
            @Override
            public void run() {
                boolean launched = TermuxBridge.runInBackground(
                    mAppContext, TermuxBridge.buildStartPtyServerScript(mPort));
                if (!launched) {
                    post(() -> callback.onFailed(
                        "Couldn't reach Termux. In Termux run:\n"
                            + "  echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n"
                            + "then fully restart Termux, and grant IntelliShell the RUN_COMMAND permission."));
                    return;
                }

                // Wait for the server to accept a connection.
                boolean up = false;
                for (int i = 0; i < MAX_POLLS; i++) {
                    if (TermuxBridge.probe(TermuxBridge.PTY_HOST, mPort, 400)) {
                        up = true;
                        break;
                    }
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (!up) {
                    post(() -> callback.onFailed(
                        "The Termux shell server didn't come up. Make sure socat can install "
                            + "(pkg install socat) and that allow-external-apps=true is set."));
                    return;
                }

                post(() -> {
                    SocketTerminalSession session = new SocketTerminalSession(
                        TermuxBridge.PTY_HOST, mPort, CONNECT_TIMEOUT_MS, transcriptRows, client);
                    // Keep the remote pty's size in step with the on-screen emulator.
                    session.setResizeCommand("stty rows %d cols %d 2>/dev/null");
                    mSession = session;
                    mController = new TerminalController(session);
                    callback.onConnected(session, mController);
                });
            }
        }.start();
    }

    private void post(Runnable r) {
        mMain.post(r);
    }
}
