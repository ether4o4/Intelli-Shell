package com.intellishell;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shows a crash report instead of the app just disappearing.
 * <p>
 * Built entirely in code with no theme dependency: this screen has to survive the
 * exact conditions that killed the app, so it must not rely on resources or an
 * AppCompat theme that might itself be the problem.
 */
public final class CrashActivity extends Activity {

    public static final String EXTRA_REPORT = "report";

    private static final int BG = 0xff0d1117;
    private static final int SURFACE = 0xff161b22;
    private static final int TEXT = 0xffe6edf3;
    private static final int ACCENT = 0xff6aa9ff;
    private static final int RED = 0xffe5534b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String report = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_REPORT);
        if (report == null) report = CrashReporter.lastReport(this);
        if (report == null) report = "No crash report was saved.";
        final String finalReport = report;

        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("IntelliShell crashed");
        title.setTextColor(RED);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView blurb = new TextView(this);
        blurb.setText("Here's exactly what went wrong. Copy or share this — it says which "
            + "line failed, which is all that's needed to fix it.");
        blurb.setTextColor(TEXT);
        blurb.setTextSize(13);
        blurb.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(blurb);

        TextView trace = new TextView(this);
        trace.setText(finalReport);
        trace.setTextColor(TEXT);
        trace.setTextSize(11);
        trace.setTypeface(Typeface.MONOSPACE);
        trace.setTextIsSelectable(true);
        trace.setBackgroundColor(SURFACE);
        trace.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(trace);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, pad / 2, 0, 0);

        buttons.addView(button("Copy", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("IntelliShell crash", finalReport));
                Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show();
            }
        }));

        buttons.addView(button("Share", v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "IntelliShell crash report");
            share.putExtra(Intent.EXTRA_TEXT, finalReport);
            startActivity(Intent.createChooser(share, "Share crash report"));
        }));

        buttons.addView(button("Restart", v -> {
            CrashReporter.clear(this);
            Intent restart = new Intent(this, MainActivity.class);
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(restart);
            finish();
        }));

        root.addView(buttons);
        setContentView(root);
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(ACCENT);
        b.setBackgroundColor(SURFACE);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (8 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        return b;
    }
}
