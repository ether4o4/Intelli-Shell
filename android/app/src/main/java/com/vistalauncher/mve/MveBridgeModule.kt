package com.vistalauncher.mve

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * MveBridgeModule — the native endpoint of the JS `Bridge`.
 *
 * Responsibilities:
 *   • Shell — delegated to [SandboxManager] (proot+Alpine, or toybox fallback).
 *   • Models — download / list / delete GGUF files for on-device inference.
 *   • Settings — persist small key/values in SharedPreferences.
 *   • Emergency stop — killAll() force-kills the running shell process.
 *
 * Progress is streamed to JS via DeviceEventEmitter: `mve_sandbox` for setup and
 * `mve_download` for model downloads.
 */
class MveBridgeModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  private val io = Executors.newFixedThreadPool(2)
  private val prefs = reactContext.getSharedPreferences("mve_engine", Context.MODE_PRIVATE)
  private val sandbox = SandboxManager(reactContext)
  private val termux = TermuxBridge(reactContext)

  override fun getName(): String = "MveBridge"

  private fun emit(event: String, params: WritableMap) {
    try {
      reactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
          .emit(event, params)
    } catch (_: Exception) {}
  }

  // Required so NativeEventEmitter doesn't warn on the JS side.
  @ReactMethod fun addListener(eventName: String) {}
  @ReactMethod fun removeListeners(count: Int) {}

  // ---- Shell ---------------------------------------------------------------

  @ReactMethod
  fun run(command: String, promise: Promise) = io.execute {
    try {
      // Commands now run in the user's real Termux, not the homemade sandbox.
      promise.resolve(termux.exec(command))
    } catch (e: Exception) {
      promise.resolve("error: ${e.message}")
    }
  }

  /** Whether the real Termux app is installed, so the UI can guide setup. */
  @ReactMethod
  fun termuxStatus(promise: Promise) = io.execute {
    promise.resolve(
        Arguments.createMap().apply {
          putBoolean("installed", termux.isInstalled())
        },
    )
  }

  @ReactMethod
  fun setupSandbox(promise: Promise) = io.execute {
    try {
      sandbox.setup { phase, pct ->
        emit(
            "mve_sandbox",
            Arguments.createMap().apply {
              putString("phase", phase)
              putInt("pct", pct)
            },
        )
      }
      promise.resolve(sandbox.statusText())
    } catch (e: Exception) {
      emit(
          "mve_sandbox",
          Arguments.createMap().apply {
            putString("phase", "error: ${e.message}")
            putInt("pct", 0)
          },
      )
      promise.reject("sandbox_error", e)
    }
  }

  @ReactMethod
  fun sandboxStatus(promise: Promise) = io.execute {
    val ready = sandbox.prootAvailable().let { sandbox.alpineReady() || !it }
    promise.resolve(
        Arguments.createMap().apply {
          putBoolean("installed", ready)
          putBoolean("ready", ready)
          putBoolean("working", ready)
          putBoolean("proot", sandbox.prootAvailable())
          putBoolean("alpine", sandbox.alpineReady())
          putString("statusText", sandbox.statusText())
        },
    )
  }

  /** Emergency stop: kill any running shell process this instant. */
  @ReactMethod
  fun killAll(promise: Promise) {
    sandbox.kill()
    promise.resolve(null)
  }

  // ---- Models --------------------------------------------------------------

  private fun modelsDir(): File = File(reactContext.filesDir, "models").apply { if (!exists()) mkdirs() }
  private fun modelFile(id: String): File = File(modelsDir(), "$id.gguf")

  @ReactMethod
  fun modelPath(id: String, promise: Promise) {
    val f = modelFile(id)
    promise.resolve(if (f.exists() && f.length() > 0) f.absolutePath else "")
  }

  @ReactMethod
  fun listDownloadedModels(promise: Promise) {
    val arr = Arguments.createArray()
    modelsDir().listFiles()?.forEach { f ->
      if (f.name.endsWith(".gguf") && f.length() > 0) {
        arr.pushString(f.name.removeSuffix(".gguf"))
      }
    }
    promise.resolve(arr)
  }

  @ReactMethod
  fun deleteModel(id: String, promise: Promise) {
    try {
      modelFile(id).delete()
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("delete_error", e)
    }
  }

  @ReactMethod
  fun downloadModel(id: String, url: String, promise: Promise) = io.execute {
    val dest = modelFile(id)
    val part = File(dest.absolutePath + ".part")
    fun progress(pct: Int, done: Boolean, error: String?) {
      emit(
          "mve_download",
          Arguments.createMap().apply {
            putString("id", id)
            putInt("pct", pct)
            putBoolean("done", done)
            if (error != null) putString("error", error)
          },
      )
    }
    try {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30000
        readTimeout = 60000
        instanceFollowRedirects = true
      }
      val code = conn.responseCode
      if (code !in 200..299) {
        throw RuntimeException("HTTP $code")
      }
      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        part.outputStream().use { output ->
          val buf = ByteArray(1 shl 16)
          var read: Int
          var doneBytes = 0L
          var lastPct = -1
          while (input.read(buf).also { read = it } >= 0) {
            output.write(buf, 0, read)
            doneBytes += read
            if (total > 0) {
              val pct = ((doneBytes * 100) / total).toInt()
              if (pct != lastPct) {
                lastPct = pct
                progress(pct, false, null)
              }
            }
          }
        }
      }
      if (dest.exists()) dest.delete()
      part.renameTo(dest)
      progress(100, true, null)
      promise.resolve(dest.absolutePath)
    } catch (e: Exception) {
      part.delete()
      progress(0, false, e.message ?: "download failed")
      promise.reject("download_error", e)
    }
  }

  // ---- Settings ------------------------------------------------------------

  @ReactMethod
  fun getPref(key: String, promise: Promise) = io.execute {
    promise.resolve(prefs.getString("pref_$key", "") ?: "")
  }

  @ReactMethod
  fun setPref(key: String, value: String, promise: Promise) = io.execute {
    prefs.edit().putString("pref_$key", value).apply()
    promise.resolve(null)
  }
}
