package com.intellishell.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Bridge to the stock Termux app (package {@code com.termux}).
 * <p>
 * IntelliShell does not ship its own Linux userland. Instead it drives the real
 * Termux install, which keeps the whole {@code pkg} ecosystem — git, python,
 * openssh, clang, llama.cpp — working exactly as upstream intends.
 * <p>
 * Termux's {@code RUN_COMMAND} service only does one-shot execution, which is far
 * too weak for a real terminal. So we use it exactly once, to start a
 * <em>pty server</em> inside Termux:
 *
 * <pre>socat TCP-LISTEN:PORT,bind=127.0.0.1,reuseaddr,fork EXEC:'bash -li',pty,setsid,ctty,stderr,sane</pre>
 *
 * The app then holds a long-lived loopback socket to it (see
 * {@code SocketTerminalSession}). That yields a genuine interactive shell:
 * persistent {@code cd}, live SSH sessions, working Ctrl+C, arrows, tab
 * completion, and full-screen apps.
 * <p>
 * Requirements on the user's device:
 * <ul>
 *   <li>Stock Termux installed (F-Droid or GitHub build — not the dead Play build).</li>
 *   <li>{@code allow-external-apps=true} in {@code ~/.termux/termux.properties}.</li>
 *   <li>{@code socat} available; {@link #buildStartPtyServerScript} installs it if missing.</li>
 * </ul>
 */
public final class TermuxBridge {

    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";

    /** Termux's prefix; binaries there are what we invoke. */
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String BIN_SH = TERMUX_PREFIX + "/bin/sh";

    /** Loopback host the pty server binds to. */
    public static final String PTY_HOST = "127.0.0.1";

    private TermuxBridge() {}

    /** Whether the stock Termux app is installed. */
    public static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether we hold the permission needed to send commands to Termux. */
    public static boolean hasRunCommandPermission(Context context) {
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND")
            == PackageManager.PERMISSION_GRANTED;
    }

    /** Quick, exception-free check that something is accepting on host:port. */
    public static boolean probe(String host, int port, int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Pick a free loopback port for the pty server. */
    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            // Fall back to a fixed high port; collision is unlikely.
            return 8127;
        }
    }

    /**
     * Shell that starts the pty server on {@code port}.
     * <p>
     * It installs {@code socat} on demand, then binds a pty running an interactive
     * login bash to the loopback port. {@code fork} lets us reconnect after the app
     * is killed without restarting Termux.
     */
    public static String buildStartPtyServerScript(int port) {
        return
            // Don't stack servers on the same port across app restarts.
            "if pgrep -f 'TCP-LISTEN:" + port + ",' >/dev/null 2>&1; then exit 0; fi; "
            + "if ! command -v socat >/dev/null 2>&1; then "
            + "  yes | pkg install -y socat >/dev/null 2>&1 || "
            + "  apt-get install -y socat >/dev/null 2>&1; "
            + "fi; "
            + "command -v socat >/dev/null 2>&1 || exit 42; "
            + "exec socat TCP-LISTEN:" + port + ",bind=" + PTY_HOST + ",reuseaddr,fork "
            + "EXEC:'bash -li',pty,setsid,ctty,stderr,sane,echo=0";
    }

    /**
     * Ask Termux to run a shell snippet in the background (no visible session).
     * Returns false if Termux isn't installed or refused the intent.
     */
    public static boolean runInBackground(Context context, String shellScript) {
        return sendRunCommand(context, shellScript, true);
    }

    /** Ask Termux to run a snippet in a foreground Termux session (visible there). */
    public static boolean runInForeground(Context context, String shellScript) {
        return sendRunCommand(context, shellScript, false);
    }

    private static boolean sendRunCommand(Context context, String shellScript, boolean background) {
        if (!isTermuxInstalled(context)) return false;
        try {
            Intent intent = new Intent(ACTION_RUN_COMMAND);
            intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            intent.putExtra(EXTRA_COMMAND_PATH, BIN_SH);
            intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-c", shellScript});
            intent.putExtra(EXTRA_WORKDIR, TERMUX_HOME);
            intent.putExtra(EXTRA_BACKGROUND, background);
            intent.putExtra(EXTRA_SESSION_ACTION, "0");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (Exception e) {
            // Most often: Termux has allow-external-apps disabled, or it was killed.
            return false;
        }
    }

    /** Launch the stock Termux app itself (so the user can finish setup there). */
    public static boolean launchTermux(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return true;
    }

    /** Store-page intent for installing Termux (F-Droid preferred, it's the maintained build). */
    public static Intent termuxInstallIntent() {
        return new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://f-droid.org/en/packages/com.termux/"));
    }
}
