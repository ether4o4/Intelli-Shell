package com.intellishell;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Catches anything that would otherwise kill the app silently and turns it into a
 * readable report.
 * <p>
 * A force-close with no visible cause is nearly impossible to diagnose from a
 * phone — you can't reach logcat without a computer. So every uncaught exception
 * is written to {@code crash.txt} in the app's files dir and shown in
 * {@link CrashActivity}, where it can be copied or shared in one tap.
 */
public final class CrashReporter {

    private static final String CRASH_FILE = "crash.txt";

    private CrashReporter() {}

    /** Install the handler. Call once, from {@link Application#onCreate}. */
    public static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            String report = buildReport(thread, error);
            try {
                write(context, report);
            } catch (Throwable ignored) {
                // Reporting must never mask the original failure.
            }
            try {
                Intent intent = new Intent(context, CrashActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                intent.putExtra(CrashActivity.EXTRA_REPORT, report);
                context.startActivity(intent);
            } catch (Throwable ignored) {
                // If we can't even show the screen, the file still holds the trace.
            }

            // Give the crash screen a moment to launch in its own process task,
            // then let the process die so it can't linger in a broken state.
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /** A full report: the stack trace plus the device facts that usually matter. */
    public static String buildReport(Thread thread, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println("IntelliShell crash report");
        pw.println("time:    " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(new Date()));
        pw.println("thread:  " + (thread == null ? "?" : thread.getName()));
        pw.println("device:  " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("abi:     " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        pw.println();

        if (error != null) {
            error.printStackTrace(pw);
            Throwable cause = error.getCause();
            int depth = 0;
            while (cause != null && depth++ < 5) {
                pw.println();
                pw.println("caused by:");
                cause.printStackTrace(pw);
                cause = cause.getCause();
            }
        } else {
            pw.println("(no throwable)");
        }

        pw.flush();
        return sw.toString();
    }

    private static void write(Context context, String report) throws Exception {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        try (PrintWriter out = new PrintWriter(file, StandardCharsets.UTF_8.name())) {
            out.print(report);
        }
    }

    /** The last saved report, or null when the app has never crashed. */
    public static String lastReport(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) return null;
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = in.read(bytes);
                if (read <= 0) return null;
                return new String(bytes, 0, read, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Forget the stored report once the user has seen it. */
    public static void clear(Context context) {
        new File(context.getFilesDir(), CRASH_FILE).delete();
    }
}
