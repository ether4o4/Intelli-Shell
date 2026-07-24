package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A {@link TerminalSession} whose bytes travel over a TCP socket instead of a
 * locally-forked process.
 * <p>
 * IntelliShell uses this to drive a real pty that lives inside the stock Termux
 * app: Termux runs {@code socat} binding a pty (running {@code bash -li}) to a
 * loopback port, and this session connects to it. Because the far end is a true
 * pty, everything interactive works — {@code cd} persists, Ctrl+C interrupts,
 * arrows and tab-completion work, and full-screen programs like {@code top} and
 * {@code nano} render correctly.
 * <p>
 * Window-size changes cannot travel in-band over a raw pty stream, so the remote
 * side runs a tiny helper that watches a size file; see {@link #setResizeCommand}.
 */
public class SocketTerminalSession extends TerminalSession {

    /** Sentinel pid: positive so the base class treats the session as live. */
    private static final int REMOTE_PID = Integer.MAX_VALUE;

    private final String mHost;
    private final int mPort;
    private final int mConnectTimeoutMs;

    private volatile Socket mSocket;
    /** Optional shell snippet, with %d placeholders for rows then columns, sent on resize. */
    private volatile String mResizeCommand;
    private volatile ByteTap mOutputTap;

    /** Observer for raw bytes arriving from the remote pty. */
    public interface ByteTap {
        void onBytes(byte[] data, int offset, int length);
    }

    /**
     * Observe everything the remote shell prints, before the emulator renders it.
     * IntelliShell uses this to capture the output of commands the AI runs, without
     * having to scrape the rendered screen.
     */
    public void setOutputTap(ByteTap tap) {
        mOutputTap = tap;
    }

    public SocketTerminalSession(String host, int port, int connectTimeoutMs, Integer transcriptRows,
                                 TerminalSessionClient client) {
        // The shell path/args/env are unused: the far end already runs the shell.
        super("/system/bin/sh", "/", new String[]{"sh"}, new String[0], transcriptRows, client);
        mHost = host;
        mPort = port;
        mConnectTimeoutMs = connectTimeoutMs;
    }

    /**
     * Set a command template used to propagate terminal resizes to the remote pty,
     * e.g. {@code "stty rows %d cols %d"}. Sent as a normal keystroke line, so it
     * only fires when the remote shell is at a prompt.
     */
    public void setResizeCommand(String resizeCommand) {
        mResizeCommand = resizeCommand;
    }

    public boolean isConnected() {
        Socket s = mSocket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    @Override
    protected boolean isTransportOpen() {
        return isConnected();
    }

    @Override
    protected void connectTransport(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        // Connect off the main thread; the emulator already exists and simply shows
        // nothing until bytes arrive.
        new Thread("SocketTermConnect[" + mHost + ":" + mPort + "]") {
            @Override
            public void run() {
                try {
                    Socket socket = new Socket();
                    socket.setTcpNoDelay(true);
                    socket.setKeepAlive(true);
                    socket.connect(new InetSocketAddress(mHost, mPort), mConnectTimeoutMs);
                    mSocket = socket;

                    mShellPid = REMOTE_PID;

                    final InputStream raw = socket.getInputStream();
                    final OutputStream out = socket.getOutputStream();
                    // Tee the stream so callers can capture command output verbatim.
                    InputStream in = new InputStream() {
                        @Override
                        public int read() throws IOException {
                            byte[] one = new byte[1];
                            int n = read(one, 0, 1);
                            return n == -1 ? -1 : (one[0] & 0xff);
                        }

                        @Override
                        public int read(byte[] b, int off, int len) throws IOException {
                            int n = raw.read(b, off, len);
                            if (n > 0) {
                                ByteTap tap = mOutputTap;
                                if (tap != null) tap.onBytes(b, off, n);
                            }
                            return n;
                        }

                        @Override
                        public void close() throws IOException {
                            raw.close();
                        }
                    };
                    startIoThreads(in, out, mHost + ":" + mPort);

                    // Watch for the far end hanging up so the UI can report it.
                    new Thread("SocketTermWaiter[" + mHost + ":" + mPort + "]") {
                        @Override
                        public void run() {
                            try {
                                while (isConnected()) {
                                    Thread.sleep(500);
                                }
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            notifyTransportExited(0);
                        }
                    }.start();
                } catch (IOException e) {
                    notifyTransportExited(1);
                }
            }
        }.start();
    }

    @Override
    protected void resizeTransport(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        String template = mResizeCommand;
        if (template == null || !isConnected()) return;
        try {
            String cmd = String.format(template, rows, columns) + "\n";
            byte[] bytes = cmd.getBytes("UTF-8");
            mTerminalToProcessIOQueue.write(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            // Resize is best-effort.
        }
    }

    @Override
    protected void terminateTransport() {
        closeTransport();
    }

    @Override
    protected void closeTransport() {
        Socket s = mSocket;
        mSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }
}
