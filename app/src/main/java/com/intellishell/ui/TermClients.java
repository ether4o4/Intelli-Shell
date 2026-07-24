package com.intellishell.ui;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * Minimal, sensible implementations of the two Termux client interfaces the
 * renderer needs. IntelliShell only needs a scrolling read/write terminal — no
 * extra-keys row, no session list, no clipboard plumbing — so most callbacks use
 * plain defaults and the hardware keyboard modifiers report "not held".
 */
final class TermClients {

    private static final String TAG = "IntelliShell.Term";

    private TermClients() {}

    /** Session-side callbacks: title/finish/logging. */
    static final class SessionClient implements TerminalSessionClient {
        interface Events {
            void onScreenUpdated();
            void onSessionEnded();
        }

        private final Events mEvents;

        SessionClient(Events events) {
            mEvents = events;
        }

        @Override public void onTextChanged(TerminalSession changedSession) { mEvents.onScreenUpdated(); }
        @Override public void onTitleChanged(TerminalSession changedSession) {}
        @Override public void onSessionFinished(TerminalSession finishedSession) { mEvents.onSessionEnded(); }
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
        @Override public Integer getTerminalCursorStyle() { return null; }

        @Override public void logError(String tag, String message) { Log.e(TAG, tag + ": " + message); }
        @Override public void logWarn(String tag, String message) { Log.w(TAG, tag + ": " + message); }
        @Override public void logInfo(String tag, String message) { Log.i(TAG, tag + ": " + message); }
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.w(TAG, tag + ": " + message, e); }
        @Override public void logStackTrace(String tag, Exception e) { Log.w(TAG, tag, e); }
    }

    /** View-side callbacks: input handling and hardware-modifier reads. */
    static final class ViewClient implements TerminalViewClient {
        private final TerminalView mView;

        ViewClient(TerminalView view) {
            mView = view;
        }

        @Override public float onScale(float scale) {
            // Pinch-to-zoom the font size, clamped to a readable range.
            if (scale < 0.9f || scale > 1.1f) {
                float newSize = mView.getTextSize() * scale;
                int clamped = (int) Math.max(18f, Math.min(72f, newSize));
                mView.setTextSize(clamped);
                return 1.0f;
            }
            return scale;
        }

        @Override public void onSingleTapUp(MotionEvent e) {
            // Focus the terminal and raise the soft keyboard so typing goes to the shell.
            mView.requestFocus();
        }

        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return true; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return true; }
        @Override public void copyModeChanged(boolean copyMode) {}

        @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
        @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }

        @Override public boolean readControlKey() { return false; }
        @Override public boolean readAltKey() { return false; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }

        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
        @Override public void onEmulatorSet() {}

        @Override public void logError(String tag, String message) { Log.e(TAG, tag + ": " + message); }
        @Override public void logWarn(String tag, String message) { Log.w(TAG, tag + ": " + message); }
        @Override public void logInfo(String tag, String message) { Log.i(TAG, tag + ": " + message); }
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.w(TAG, tag + ": " + message, e); }
        @Override public void logStackTrace(String tag, Exception e) { Log.w(TAG, tag, e); }
    }
}
