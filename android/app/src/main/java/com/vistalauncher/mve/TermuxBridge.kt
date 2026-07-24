package com.vistalauncher.mve

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TermuxBridge — runs shell commands inside a real, user-installed Termux
 * (from F-Droid) via its RUN_COMMAND intent API, and returns the combined
 * output back to the caller.
 *
 * This replaces the old homemade proot/Alpine sandbox: instead of shipping and
 * maintaining our own Linux userland, we drive Termux — which already has apt,
 * pkg, git, python, node, compilers, everything. Our app becomes the AI brain
 * that pilots it.
 *
 * How a command runs:
 *   1. We start com.termux.app.RunCommandService with action com.termux.RUN_COMMAND,
 *      asking it to run `bash -c "<command>"` in the background.
 *   2. We hand Termux a mutable PendingIntent wrapping a private broadcast.
 *   3. When the command finishes, Termux fills that PendingIntent with a "result"
 *      bundle (stdout / stderr / exitCode) and fires it.
 *   4. Our receiver unblocks exec() with the combined output.
 *
 * Prerequisites the user must satisfy once (the UI walks them through it):
 *   • Install Termux from F-Droid (NOT the dead Play Store build).
 *   • Set `allow-external-apps=true` in ~/.termux/termux.properties.
 *   • Grant our app the com.termux.permission.RUN_COMMAND permission.
 */
class TermuxBridge(private val context: Context) {

  companion object {
    const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    // RUN_COMMAND intent extras (literal keys Termux expects).
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    // Result bundle Termux fills into our PendingIntent when done.
    private const val RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERRMSG = "errmsg"

    // Termux's fixed filesystem layout.
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val BASH = "$TERMUX_PREFIX/bin/bash"

    private const val RESULT_ACTION = "com.vistalauncher.mve.TERMUX_RESULT"
    private val requestSeq = AtomicInteger(1000)
  }

  /** Is Termux installed at all? */
  fun isInstalled(): Boolean =
      try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
      } catch (_: Exception) {
        false
      }

  /**
   * Run `command` in Termux and return its combined output (stdout, then stderr
   * if any). Blocks up to [timeoutMs] for the result. Safe to call off the main
   * thread (the React bridge already runs us on an IO executor).
   */
  fun exec(command: String, timeoutMs: Long = 180_000): String {
    if (!isInstalled()) {
      return "Termux isn't installed. Open IntelliShell settings and tap \"Set up Termux\" " +
          "to install it from F-Droid — that's what runs your commands."
    }

    val requestCode = requestSeq.incrementAndGet()
    val latch = CountDownLatch(1)
    val output = StringBuilder()
    @Volatile var received = false

    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(ctx: Context, intent: Intent) {
            received = true
            val bundle: Bundle? = intent.getBundleExtra(RESULT_BUNDLE)
            if (bundle != null) {
              val out = bundle.getString(RESULT_STDOUT, "") ?: ""
              val err = bundle.getString(RESULT_STDERR, "") ?: ""
              val errmsg = bundle.getString(RESULT_ERRMSG, "") ?: ""
              output.append(out)
              if (err.isNotEmpty()) {
                if (output.isNotEmpty()) output.append('\n')
                output.append(err)
              }
              if (errmsg.isNotBlank()) {
                if (output.isNotEmpty()) output.append('\n')
                output.append(errmsg)
              }
            }
            latch.countDown()
          }
        }

    val filter = IntentFilter(RESULT_ACTION)
    // API 33+ requires an explicit export flag for runtime-registered receivers.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    }

    try {
      val resultIntent =
          Intent(RESULT_ACTION).apply {
            setPackage(context.packageName)
            // Unique per call so overlapping commands don't collide.
            addCategory("cmd_$requestCode")
          }
      var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        piFlags = piFlags or PendingIntent.FLAG_MUTABLE
      }
      val pendingIntent =
          PendingIntent.getBroadcast(context, requestCode, resultIntent, piFlags)

      val runIntent =
          Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_COMMAND_LABEL, "IntelliShell")
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
          }

      try {
        ContextCompat.startForegroundService(context, runIntent)
      } catch (e: SecurityException) {
        return "Termux blocked the command. In Termux, add \"allow-external-apps=true\" to " +
            "~/.termux/termux.properties (then run: termux-reload-settings), and make sure " +
            "IntelliShell has the RUN_COMMAND permission. Details: ${e.message}"
      } catch (e: Exception) {
        return "Couldn't reach Termux: ${e.message}"
      }

      val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
      return when {
        !finished -> "Command timed out after ${timeoutMs / 1000}s with no response from Termux."
        !received -> "(no output)"
        else -> output.toString()
      }
    } finally {
      try {
        context.unregisterReceiver(receiver)
      } catch (_: Exception) {}
    }
  }
}
