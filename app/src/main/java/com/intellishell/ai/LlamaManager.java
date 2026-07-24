package com.intellishell.ai;

import com.intellishell.bridge.TermuxBridge;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Drives llama.cpp <em>inside stock Termux</em>.
 * <p>
 * IntelliShell never links libllama itself. Termux already ships a maintained
 * {@code llama-cpp} package built for the device's ABI, so the app's job is
 * limited to composing shell snippets and handing them to
 * {@link TermuxBridge#runInBackground} / {@link TermuxBridge#runInForeground}.
 * That keeps the APK small, sidesteps NDK build complexity, and means a
 * {@code pkg upgrade} picks up new llama.cpp releases for free.
 * <p>
 * Everything here is stateless and static: the authoritative state (which models
 * exist, whether a server is listening) lives in Termux's filesystem and on the
 * loopback port, not in this process, which may be killed at any time.
 * <p>
 * Every builder returns a snippet safe to pass as a single {@code sh -c} argument:
 * no embedded newlines, every path and URL single-quoted.
 */
public final class LlamaManager {

    /** Exit code convention shared by the scripts, surfaced in Termux logs. */
    private static final int EXIT_NO_BINARY = 43;
    private static final int EXIT_NO_MODEL = 44;

    /** How long to wait on the loopback probe. Local, so anything slower is "down". */
    private static final int PROBE_TIMEOUT_MS = 300;

    private LlamaManager() {}

    /** Directory (a Termux path) holding every downloaded {@code .gguf}. */
    public static String modelsDir() {
        return TermuxBridge.TERMUX_HOME + "/intellishell/models";
    }

    /** Directory (a Termux path) for server logs, so failures are diagnosable in Termux. */
    public static String logsDir() {
        return TermuxBridge.TERMUX_HOME + "/intellishell/logs";
    }

    /**
     * On-disk file name for a model, taken from the last path segment of its URL.
     * Callers use this to test whether a catalog entry is already downloaded.
     */
    public static String modelFileName(LocalModel m) {
        String path = m.url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        String file = path.substring(path.lastIndexOf('/') + 1);
        // Defensive: a name with a slash or quote would escape our quoting below.
        return file.replace('/', '_').replace('\'', '_');
    }

    /** Full Termux path to a model's weights file. */
    public static String modelPath(LocalModel m) {
        return modelsDir() + "/" + modelFileName(m);
    }

    /**
     * Installs llama.cpp and its download prerequisite. Idempotent — running it
     * when {@code llama-server} already exists is a no-op, so callers may fire it
     * before every model download without cost.
     * <p>
     * {@code pkg} is preferred because it configures Termux's mirrors; the raw
     * {@code apt-get} path covers installs where {@code pkg} is missing or wedged.
     */
    public static String buildInstallLlamaScript() {
        return "mkdir -p " + sq(modelsDir()) + " " + sq(logsDir()) + " >/dev/null 2>&1; "
            + "if ! command -v curl >/dev/null 2>&1; then "
            + "yes | pkg install -y curl >/dev/null 2>&1 || "
            + "apt-get install -y curl >/dev/null 2>&1; fi; "
            + "if " + haveServerBinary() + "; then exit 0; fi; "
            + "yes | pkg install -y llama-cpp >/dev/null 2>&1 || "
            + "{ apt-get update -y >/dev/null 2>&1; "
            + "apt-get install -y llama-cpp >/dev/null 2>&1; }; "
            + haveServerBinary() + " || exit " + EXIT_NO_BINARY;
    }

    /**
     * Downloads {@code m} into {@link #modelsDir()}.
     * <p>
     * The transfer lands in a {@code .part} sibling and is only renamed into place
     * once curl reports success, so an interrupted multi-gigabyte download can
     * never masquerade as a usable model; a surviving {@code .part} is resumed
     * with {@code -C -} on the next attempt.
     * <p>
     * A Hugging Face token is applied only for {@code huggingface.co} hosts — gated
     * repositories need it, and leaking a bearer token to an unrelated mirror would
     * be worse than a failed download. The token is passed through a
     * {@code chmod 600} curl config file rather than the command line, keeping it
     * out of {@code ps} output, process listings and stdout.
     *
     * @param hfTokenOrNull Hugging Face access token, or {@code null} for anonymous
     */
    public static String buildDownloadModelScript(LocalModel m, String hfTokenOrNull) {
        String file = modelFileName(m);
        String token = sanitizeToken(hfTokenOrNull);
        boolean useToken = token != null && isHuggingFace(m.url);

        StringBuilder s = new StringBuilder();
        s.append("d=").append(sq(modelsDir())).append("; ");
        s.append("mkdir -p \"$d\" || exit 1; ");
        s.append("f=\"$d/").append(escapeInDq(file)).append("\"; ");
        // Already present: treat as success so the UI can call this idempotently.
        s.append("if [ -s \"$f\" ]; then exit 0; fi; ");
        s.append("command -v curl >/dev/null 2>&1 || exit ").append(EXIT_NO_BINARY).append("; ");
        s.append("p=\"$f.part\"; ");
        s.append("if [ -s \"$p\" ]; then r='-C -'; else r=''; fi; ");

        if (useToken) {
            s.append("c=\"$d/.hdr.$$\"; ");
            s.append("( umask 077; : > \"$c\" ) || exit 1; ");
            // printf is a shell builtin, so the token never becomes an argv of its own process.
            s.append("printf '%s\\n' 'header = \"Authorization: Bearer ")
                .append(token).append("\"' >> \"$c\"; ");
            s.append("curl -fL --retry 3 --retry-delay 2 --progress-bar $r -K \"$c\" ")
                .append("-o \"$p\" ").append(sq(m.url)).append("; rc=$?; ");
            s.append("rm -f \"$c\"; ");
        } else {
            s.append("curl -fL --retry 3 --retry-delay 2 --progress-bar $r ")
                .append("-o \"$p\" ").append(sq(m.url)).append("; rc=$?; ");
        }

        // Keep a partial file for resume, but never leave a zero-byte husk behind.
        s.append("if [ \"$rc\" -ne 0 ]; then [ -s \"$p\" ] || rm -f \"$p\"; exit \"$rc\"; fi; ");
        s.append("mv -f \"$p\" \"$f\" || exit 1; ");
        s.append("exit 0");
        return s.toString();
    }

    /**
     * Starts {@code llama-server} for {@code m} on loopback {@code port}.
     * <p>
     * Any server already holding that port is killed first: llama.cpp would
     * otherwise exit on bind failure and leave the previous model serving, which
     * looks to the user like the model switch silently did nothing.
     * <p>
     * The server is detached with {@code setsid}/{@code nohup} so it outlives the
     * short-lived Termux {@code RUN_COMMAND} session that launched it.
     */
    public static String buildStartServerScript(LocalModel m, int port, int contextSize) {
        int p = clampPort(port);
        int ctx = clampContext(contextSize);
        return "m=" + sq(modelPath(m)) + "; "
            + "[ -s \"$m\" ] || exit " + EXIT_NO_MODEL + "; "
            + "b=$(command -v llama-server 2>/dev/null || "
            + "command -v llama-cpp-server 2>/dev/null); "
            + "[ -n \"$b\" ] || exit " + EXIT_NO_BINARY + "; "
            + killOnPort(p)
            + "sleep 1; "
            + "l=" + sq(logsDir()) + "; mkdir -p \"$l\" || exit 1; "
            + "t=$(nproc 2>/dev/null || echo 4); "
            + "if command -v setsid >/dev/null 2>&1; then s=setsid; else s=''; fi; "
            + "$s nohup \"$b\" -m \"$m\" --host " + TermuxBridge.PTY_HOST
            + " --port " + p + " -c " + ctx + " -t \"$t\" "
            + ">\"$l/llama-" + p + ".log\" 2>&1 & "
            + "exit 0";
    }

    /** Stops whatever llama.cpp server is bound to {@code port}. Always exits 0. */
    public static String buildStopServerScript(int port) {
        return killOnPort(clampPort(port)) + "exit 0";
    }

    /**
     * Prints one {@code .gguf} basename per line. Glob iteration rather than
     * {@code ls} parsing, so names containing spaces survive intact.
     */
    public static String buildListDownloadedScript() {
        return "d=" + sq(modelsDir()) + "; "
            + "[ -d \"$d\" ] || exit 0; "
            + "for f in \"$d\"/*.gguf; do [ -e \"$f\" ] && basename \"$f\"; done; "
            + "exit 0";
    }

    /**
     * Cheap liveness probe: can we open a TCP connection to the server?
     * <p>
     * Deliberately not an HTTP request — this runs on UI-driven polling paths and
     * only needs to answer "is something listening". Never throws; any failure,
     * including a bad host, simply means "down".
     */
    public static boolean isServerUp(String host, int port) {
        if (host == null || host.isEmpty()) return false;
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Test for either upstream binary name; llama.cpp renamed it mid-2024. */
    private static String haveServerBinary() {
        return "{ command -v llama-server >/dev/null 2>&1 || "
            + "command -v llama-cpp-server >/dev/null 2>&1; }";
    }

    /**
     * Kills whichever llama.cpp server owns {@code port}, by walking {@code /proc}.
     * <p>
     * A {@code pkill -f 'llama-server.*--port N'} is the obvious implementation and
     * is actively dangerous here: this snippet reaches Termux as {@code sh -c
     * <script>}, so the script text itself — which necessarily mentions both
     * {@code llama-server} and {@code --port N} — appears in that shell's own
     * command line and matches the pattern. The shell kills itself before it can
     * launch anything, and the model switch silently does nothing.
     * <p>
     * Instead each PID is confirmed two ways: {@code argv[0]}'s basename must be a
     * llama server binary (which excludes our shell and the pipeline helpers), and
     * {@code --port} must actually carry this port as its value (so port 8080 never
     * matches a server on 8081 or a {@code -c 8080} context size).
     */
    private static String killOnPort(int port) {
        return "for e in /proc/[0-9]*; do "
            + "q=${e#/proc/}; "
            + "[ \"$q\" = \"$$\" ] && continue; "
            + "a=$(tr '\\0' '\\n' < \"$e/cmdline\" 2>/dev/null) || continue; "
            + "[ -n \"$a\" ] || continue; "
            + "printf '%s\\n' \"$a\" | awk -v p=" + port + " '"
            // Basename of any argument, so a packaged wrapper script (argv[0] is the
            // interpreter, argv[1] the script) is still recognised.
            + "{b=$0; sub(/^.*\\//,\"\",b); "
            + "if (b==\"llama-server\" || b==\"llama-cpp-server\") n=1; "
            + "if ($0==\"--port=\" p) f=1; "
            + "if (v==\"--port\" && $0==p) f=1; v=$0} "
            + "END{exit !(n && f)}"
            + "' || continue; "
            + "kill \"$q\" >/dev/null 2>&1; "
            + "done; ";
    }

    /**
     * Strips everything outside {@code [A-Za-z0-9_.-]} from a token. Real Hugging
     * Face tokens are already in that set, and the restriction guarantees the value
     * cannot break out of the quoting in the generated curl config file.
     *
     * @return the sanitised token, or {@code null} if nothing usable remains
     */
    private static String sanitizeToken(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        StringBuilder out = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (ok) out.append(c);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * Whether the URL points at Hugging Face, the only host we will authenticate to.
     * <p>
     * The host is extracted by hand rather than via {@code java.net.URI}, which
     * rejects perfectly downloadable URLs containing a space and would then have us
     * quietly omit the token from a gated repository — surfacing as an opaque 401.
     * Userinfo is stripped first so {@code https://huggingface.co@evil.example/x}
     * is correctly read as {@code evil.example} and gets no token.
     */
    private static boolean isHuggingFace(String url) {
        if (url == null) return false;
        String s = url.toLowerCase(Locale.US);
        if (!s.startsWith("https://")) return false;
        String authority = s.substring("https://".length());

        int end = authority.length();
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        authority = authority.substring(0, end);

        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);

        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);

        return authority.equals("huggingface.co") || authority.endsWith(".huggingface.co");
    }

    /** Wraps a value in single quotes for {@code sh}, escaping any embedded quote. */
    private static String sq(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Neutralises characters that would be expanded inside a double-quoted word. */
    private static String escapeInDq(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`");
    }

    private static int clampPort(int port) {
        return (port < 1 || port > 65535) ? 8080 : port;
    }

    /** Guard against a context so large llama.cpp is OOM-killed on a phone. */
    private static int clampContext(int ctx) {
        if (ctx < 512) return 512;
        if (ctx > 32768) return 32768;
        return ctx;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Probe result is already decided; a failed close tells us nothing.
        }
    }
}
