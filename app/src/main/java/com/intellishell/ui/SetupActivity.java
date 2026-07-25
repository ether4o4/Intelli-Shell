package com.intellishell.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.intellishell.MainActivity;
import com.intellishell.R;
import com.intellishell.bridge.BootstrapInstaller;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screen a brand new user sees: NeverSoft Shell unpacking its own Linux system.
 * <p>
 * The app ships Termux's userland inside its APK and installs it into its private
 * data directory on first launch. That takes tens of seconds and cannot be skipped,
 * so instead of hiding it behind a frozen chat screen we give it a screen of its own
 * that says what is happening in plain language and shows real progress.
 * <p>
 * The install starts the moment the screen appears — there is nothing for the user to
 * decide, so asking them to tap "Begin" would only be a speed bump. If it fails, the
 * screen keeps the exact error and offers Retry plus Copy, because the one thing a
 * stuck user can do for us is paste the details somewhere we can read them.
 */
public final class SetupActivity extends AppCompatActivity {

    /**
     * Sends {@code caller} to this screen when the Linux system is not installed yet.
     *
     * @return true if setup was launched, in which case the caller should
     *         {@code finish()} and do no further work.
     */
    public static boolean routeIfNeeded(Activity caller) {
        if (caller == null || BootstrapInstaller.isInstalled(caller)) return false;
        caller.startActivity(new Intent(caller, SetupActivity.class));
        return true;
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());

    /**
     * The install runs here rather than on the caller's thread. BootstrapInstaller may
     * well do its own threading, but unpacking an archive on the UI thread would freeze
     * the very progress bar that is meant to prove the app is alive — so we never give
     * it the chance.
     */
    private final ExecutorService mInstallExecutor = Executors.newSingleThreadExecutor();

    private ProgressBar mProgress;
    private TextView mPhase;
    private View mProgressCard;
    private View mErrorCard;
    private TextView mErrorText;

    /** Guards against a second install being kicked off by a double-tapped Retry. */
    private boolean mInstalling;

    /** Kept verbatim for the Copy button; null while things are going well. */
    @Nullable
    private String mLastError;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Reachable if the user backgrounded us mid-install and the work finished, or
        // if something else installed the system first. Nothing to show — move on.
        if (BootstrapInstaller.isInstalled(this)) {
            openMain();
            return;
        }

        setContentView(R.layout.activity_setup);

        // A screen that dims and locks halfway through a one-time install looks broken.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mProgress = findViewById(R.id.setupProgress);
        mPhase = findViewById(R.id.setupPhase);
        mProgressCard = findViewById(R.id.setupProgressCard);
        mErrorCard = findViewById(R.id.setupErrorCard);
        mErrorText = findViewById(R.id.setupError);

        Button retry = findViewById(R.id.setupRetry);
        Button copy = findViewById(R.id.setupCopy);
        retry.setOnClickListener(v -> startInstall());
        copy.setOnClickListener(v -> copyDetails());

        startInstall();
    }

    // ---- Install -----------------------------------------------------------

    private void startInstall() {
        if (mInstalling) return;
        mInstalling = true;
        mLastError = null;

        mErrorCard.setVisibility(View.GONE);
        mProgressCard.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(true);
        mPhase.setText(R.string.setup_phase_starting);

        mInstallExecutor.execute(() -> BootstrapInstaller.install(
            getApplicationContext(), new BootstrapInstaller.Progress() {

                @Override
                public void onPhase(String phase, int percent) {
                    post(() -> showPhase(phase, percent));
                }

                @Override
                public void onDone() {
                    post(() -> {
                        mInstalling = false;
                        mProgress.setIndeterminate(false);
                        mProgress.setProgress(100);
                        mPhase.setText(R.string.setup_phase_done);
                        openMain();
                    });
                }

                @Override
                public void onError(String message) {
                    post(() -> showError(message));
                }
            }));
    }

    private void showPhase(String phase, int percent) {
        String label = TextUtils.isEmpty(phase) ? getString(R.string.setup_phase_starting) : phase;
        if (percent < 0) {
            // The installer doesn't know how far along it is; a crawling bar is more
            // honest than a fake number.
            mProgress.setIndeterminate(true);
            mPhase.setText(label);
        } else {
            int clamped = Math.min(100, percent);
            mProgress.setIndeterminate(false);
            mProgress.setProgress(clamped);
            mPhase.setText(getString(R.string.setup_phase_percent, label, clamped));
        }
    }

    private void showError(String message) {
        mInstalling = false;
        mLastError = TextUtils.isEmpty(message) ? "(no details reported)" : message;
        mProgress.setIndeterminate(false);
        mPhase.setText(R.string.setup_failed_phase);
        mErrorText.setText(mLastError);
        mErrorCard.setVisibility(View.VISIBLE);
    }

    /**
     * Runs {@code r} on the main thread, but only while this screen is still real —
     * the installer's callbacks arrive on a worker and can outlive a rotation or a
     * user who walked away.
     */
    private void post(Runnable r) {
        mMain.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            r.run();
        });
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        // MainActivity is singleTask; clearing on top means we hand control back to the
        // existing instance rather than stacking a second chat screen behind this one.
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    // ---- Error details -----------------------------------------------------

    /**
     * Puts the failure plus the few device facts that explain most install failures
     * (Android version, CPU ABI, free-space-sensitive storage) on the clipboard.
     */
    private void copyDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("NeverSoft Shell setup failed\n");
        sb.append("app: ").append(appVersion()).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("abis: ").append(TextUtils.join(", ", Arrays.asList(Build.SUPPORTED_ABIS))).append('\n');
        sb.append("error: ").append(mLastError == null ? "(none recorded)" : mLastError).append('\n');

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, R.string.setup_failed_title, Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.setup_copy_label), sb.toString()));

        // Android 13+ shows its own "copied" popup; a Toast on top of it is noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.setup_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    // ---- Lifecycle ---------------------------------------------------------

    @Override
    @SuppressWarnings("deprecation") // onBackPressed still drives the gesture at compileSdk 34.
    public void onBackPressed() {
        if (mInstalling) {
            // Leaving now would abandon a half-unpacked system; it is only a moment.
            Toast.makeText(this, R.string.setup_back_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mInstallExecutor.shutdownNow();
    }
}
