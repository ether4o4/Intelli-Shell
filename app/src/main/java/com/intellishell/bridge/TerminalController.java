package com.intellishell.bridge;

import com.termux.terminal.SocketTerminalSession;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs commands in the user's live terminal on the agent's behalf and returns
 * what they printed.
 * <p>
 * Because the far end is a real interactive shell rather than a one-shot process,
 * there is no exit event to wait on. So each command is followed by a printed
 * sentinel; we tap the raw byte stream and treat everything up to that sentinel
 * as the command's output. This keeps the user's session untouched — they see the
 * command run exactly as if it had been typed.
 */
public final class TerminalController {

    /** Guards against a hung command blocking the agent forever. */
    private static final long DEFAULT_TIMEOUT_MS = 180_000L;

    private final SocketTerminalSession mSession;
    private final AtomicLong mSequence = new AtomicLong();

    private final Object mLock = new Object();
    private StringBuilder mCapture;
    private String mMarker;
    private volatile boolean mCancelled;

    public TerminalController(SocketTerminalSession session) {
        mSession = session;
        mSession.setOutputTap((data, offset, length) -> {
            synchronized (mLock) {
                if (mCapture == null) return;
                mCapture.append(new String(data, offset, length, StandardCharsets.UTF_8));
                if (mMarker != null && mCapture.indexOf(mMarker) >= 0) {
                    mLock.notifyAll();
                }
            }
        });
    }

    /** Type text into the terminal exactly as the user would. */
    public void type(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        mSession.write(bytes, 0, bytes.length);
    }

    /** Send Ctrl+C to interrupt whatever is running. */
    public void interrupt() {
        mSession.write(new byte[]{0x03}, 0, 1);
    }

    /**
     * Abandon the command currently being waited on. The shell keeps running —
     * this only releases the agent, and sends an interrupt so the user regains a
     * prompt.
     */
    public void cancel() {
        synchronized (mLock) {
            mCancelled = true;
            mLock.notifyAll();
        }
        interrupt();
    }

    /**
     * Run {@code command} in the live shell and block until it finishes, returning
     * its output with terminal escape sequences stripped.
     */
    public String run(String command) throws InterruptedException {
        return run(command, DEFAULT_TIMEOUT_MS);
    }

    public String run(String command, long timeoutMs) throws InterruptedException {
        String marker = "__ISH_DONE_" + mSequence.incrementAndGet() + "__";

        synchronized (mLock) {
            mCancelled = false;
            mCapture = new StringBuilder();
            mMarker = marker;
        }

        // Feed the command, then a sentinel line. Sending them as separate lines lets
        // multi-line input (heredocs, loops) be consumed by the shell normally.
        type(command.endsWith("\n") ? command : command + "\n");
        type("printf '\\n" + marker + "%s\\n' \"$?\"\n");

        long deadline = System.currentTimeMillis() + timeoutMs;
        String raw;
        synchronized (mLock) {
            while (mCapture.indexOf(marker) < 0 && !mCancelled) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                mLock.wait(Math.min(remaining, 250));
            }
            raw = mCapture.toString();
            mCapture = null;
            mMarker = null;
        }

        return cleanOutput(raw, marker, command);
    }

    /**
     * Turn raw pty bytes into something worth showing a model: escape sequences
     * removed, the echoed command and sentinel machinery dropped.
     */
    private static String cleanOutput(String raw, String marker, String command) {
        String text = stripAnsi(raw);

        // Everything before the sentinel is the command's output.
        int end = text.indexOf(marker);
        if (end >= 0) text = text.substring(0, end);

        // Drop the echo of what we typed, including the sentinel printf itself.
        StringBuilder out = new StringBuilder();
        String[] commandLines = command.split("\\r?\\n");
        int commandLineIndex = 0;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("printf '\\n__ISH_DONE_")) continue;
            if (commandLineIndex < commandLines.length
                && trimmed.endsWith(commandLines[commandLineIndex].trim())
                && !commandLines[commandLineIndex].trim().isEmpty()) {
                commandLineIndex++;
                continue;
            }
            out.append(line).append('\n');
        }

        return out.toString().trim();
    }

    /** Remove CSI/OSC escape sequences and carriage returns. */
    public static String stripAnsi(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 0x1b && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '[') {
                    // CSI: ESC [ params letter
                    i += 2;
                    while (i < s.length() && !Character.isLetter(s.charAt(i))) i++;
                    i++;
                    continue;
                } else if (next == ']') {
                    // OSC: ESC ] ... BEL or ESC \
                    i += 2;
                    while (i < s.length() && s.charAt(i) != 0x07) {
                        if (s.charAt(i) == 0x1b && i + 1 < s.length() && s.charAt(i + 1) == '\\') {
                            i++;
                            break;
                        }
                        i++;
                    }
                    i++;
                    continue;
                } else {
                    i += 2;
                    continue;
                }
            }
            if (c == '\r') {
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
