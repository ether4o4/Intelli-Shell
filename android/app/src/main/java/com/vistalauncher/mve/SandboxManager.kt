package com.vistalauncher.mve

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * The shell the agent drives.
 *
 * Two modes, chosen automatically:
 *   • proot + Alpine — a real Linux userland (apk, coreutils, busybox). Used when
 *     a `libproot.so` binary is shipped in the APK's native lib dir and an Alpine
 *     rootfs has been installed. Commands run as fake-root inside the rootfs.
 *   • toybox fallback — Android's own `/system/bin/sh` rooted in a private dir.
 *     Always available, works today: ls, cat, grep, find, sed, awk, echo, ps…
 *
 * The same command flows through whichever mode is active, so the agent and UI
 * never change; installing Alpine just upgrades what the shell can do.
 */
class SandboxManager(private val ctx: Context) {

  @Volatile private var current: Process? = null

  private fun sandboxRoot(): File = File(ctx.filesDir, "mve-sandbox").apply { if (!exists()) mkdirs() }
  private fun homeDir(): File = File(sandboxRoot(), "home").apply { if (!exists()) mkdirs() }
  private fun alpineDir(): File = File(sandboxRoot(), "alpine")
  // v2: re-extract rootfs for installs whose extractor dropped exec bits.
  private fun installedMarker(): File = File(alpineDir(), ".installed-v2")
  private fun cacheTmp(): File = File(ctx.cacheDir, "proot-tmp").apply { if (!exists()) mkdirs() }

  private fun prootBinary(): File? {
    val f = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
    return if (f.exists()) f else null
  }
  private fun prootLoader(): File? {
    val f = File(ctx.applicationInfo.nativeLibraryDir, "libproot_loader.so")
    return if (f.exists()) f else null
  }

  fun prootAvailable(): Boolean = prootBinary() != null
  fun alpineReady(): Boolean = prootAvailable() && installedMarker().exists()

  fun statusText(): String =
      when {
        alpineReady() -> "Alpine Linux ready (proot)"
        prootAvailable() -> "Tap Setup to install Alpine Linux"
        else -> "toybox shell ready"
      }

  // ---- setup ---------------------------------------------------------------

  /**
   * Prepare the shell. In toybox mode this just ensures the home dir. When proot
   * is present it downloads and extracts an Alpine minirootfs, reporting progress
   * through [onProgress] as ("phase", pct 0..100).
   */
  fun setup(onProgress: (String, Int) -> Unit) {
    homeDir()
    File(homeDir(), "welcome.txt").writeText("MVE shell. Ask the agent to run something.\n")

    if (!prootAvailable()) {
      onProgress("toybox ready", 100)
      return
    }
    if (alpineReady()) {
      onProgress("Alpine already installed", 100)
      return
    }

    // A rootfs without a current marker is stale or broken — start clean.
    if (alpineDir().exists() && !installedMarker().exists()) alpineDir().deleteRecursively()
    val root = alpineDir().apply { if (!exists()) mkdirs() }
    val abi = alpineArch()
    val url = ALPINE_ROOTFS.replace("{arch}", abi)

    onProgress("downloading Alpine ($abi)", 0)
    val tarGz = File(ctx.cacheDir, "alpine-$abi.tar.gz")
    download(url, tarGz) { pct -> onProgress("downloading Alpine", pct) }

    onProgress("extracting rootfs", 0)
    extractTarGz(tarGz, root) { pct -> onProgress("extracting rootfs", pct) }
    tarGz.delete()

    // Minimal network + user config so apk and DNS work inside the rootfs.
    File(root, "etc").mkdirs()
    File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    File(root, "etc/hosts").writeText("127.0.0.1 localhost\n")
    File(root, "root").mkdirs()

    installedMarker().writeText("ok")
    onProgress("Alpine ready", 100)
  }

  // ---- exec ----------------------------------------------------------------

  fun exec(command: String): String {
    val pb =
        if (alpineReady()) prootCommand(command)
        else ProcessBuilder("/system/bin/sh", "-c", command).directory(homeDir())
    pb.redirectErrorStream(true)
    val proc = pb.start()
    current = proc
    val out =
        try {
          proc.inputStream.bufferedReader().use { it.readText() }
        } finally {
          try {
            proc.waitFor()
          } catch (_: Exception) {}
          current = null
        }
    return out.trimEnd()
  }

  private fun prootCommand(command: String): ProcessBuilder {
    val proot = prootBinary()!!
    val root = alpineDir().absolutePath
    val args =
        mutableListOf(
            proot.absolutePath,
            "-r", root,
            "-0", // fake root — apk and package installs need uid 0
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "/bin/sh",
            "-c",
            command,
        )
    val pb = ProcessBuilder(args)
    pb.environment()["PROOT_TMP_DIR"] = cacheTmp().absolutePath
    prootLoader()?.let { pb.environment()["PROOT_LOADER"] = it.absolutePath }
    return pb
  }

  fun kill() {
    try {
      current?.destroyForcibly()
    } catch (_: Exception) {}
    current = null
  }

  // ---- helpers -------------------------------------------------------------

  private fun alpineArch(): String {
    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    return when {
      abi.startsWith("arm64") -> "aarch64"
      abi.startsWith("armeabi") -> "armhf"
      abi.startsWith("x86_64") -> "x86_64"
      abi.startsWith("x86") -> "x86"
      else -> "aarch64"
    }
  }

  private fun download(urlStr: String, dest: File, onPct: (Int) -> Unit) {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
      connectTimeout = 30000
      readTimeout = 60000
      instanceFollowRedirects = true
    }
    conn.inputStream.use { input ->
      val total = conn.contentLengthLong
      dest.outputStream().use { output ->
        val buf = ByteArray(1 shl 16)
        var read: Int
        var done = 0L
        var lastPct = -1
        while (input.read(buf).also { read = it } >= 0) {
          output.write(buf, 0, read)
          done += read
          if (total > 0) {
            val pct = ((done * 100) / total).toInt()
            if (pct != lastPct) {
              lastPct = pct
              onPct(pct)
            }
          }
        }
      }
    }
  }

  /** Minimal streaming tar (ustar/GNU/pax) extractor — enough for Alpine rootfs. */
  private fun extractTarGz(tarGz: File, destRoot: File, onPct: (Int) -> Unit) {
    val totalBytes = tarGz.length().coerceAtLeast(1)
    var seen = 0L
    var lastPct = -1
    GZIPInputStream(tarGz.inputStream().buffered()).use { gz ->
      val header = ByteArray(512)
      var longName: String? = null
      while (true) {
        if (!readFully(gz, header)) break
        if (header.all { it.toInt() == 0 }) break // end of archive

        var name = cString(header, 0, 100)
        val sizeStr = cString(header, 124, 12).trim()
        val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
        val type = header[156].toInt().toChar()
        val linkName = cString(header, 157, 100)
        if (longName != null) {
          name = longName!!
          longName = null
        }

        when (type) {
          'L' -> { // GNU long name
            longName = readEntry(gz, size).toString(Charsets.UTF_8).trim(' ', '\n', '\u0000')
          }
          'x', 'g' -> { // pax header — pull a path= override if present
            val pax = readEntry(gz, size).toString(Charsets.UTF_8)
            Regex("""\d+ path=([^\n]+)\n""").find(pax)?.groupValues?.get(1)?.let { longName = it }
          }
          '5' -> { // directory
            File(destRoot, name).mkdirs()
            skipPadding(gz, size)
          }
          '2' -> { // symlink
            val target = File(destRoot, name)
            target.parentFile?.mkdirs()
            try {
              if (target.exists()) target.delete()
              Files.createSymbolicLink(Paths.get(target.path), Paths.get(linkName))
            } catch (_: Exception) {}
            skipPadding(gz, size)
          }
          '0', '\u0000' -> { // regular file (0x30 or NUL typeflag)
            val f = File(destRoot, name)
            f.parentFile?.mkdirs()
            f.outputStream().use { out -> copyN(gz, out, size) }
            // tar mode bits: without this busybox/apk extract non-executable
            // and every exec inside proot fails.
            val modeStr = cString(header, 100, 8).trim()
            val mode = if (modeStr.isEmpty()) 0 else (modeStr.toIntOrNull(8) ?: 0)
            if (mode and 0x49 != 0) f.setExecutable(true, false)
            skipPadding(gz, size)
          }
          '1' -> { // hardlink — copy the already-extracted link target
            val src = File(destRoot, linkName)
            val dst = File(destRoot, name)
            dst.parentFile?.mkdirs()
            if (src.exists()) {
              src.copyTo(dst, overwrite = true)
              if (src.canExecute()) dst.setExecutable(true, false)
            }
            skipN(gz, size)
            skipPadding(gz, size)
          }
          else -> { // hardlink, char/block dev, fifo — skip contents
            skipN(gz, size)
            skipPadding(gz, size)
          }
        }

        seen += 512 + roundUp(size)
        val pct = ((seen * 100) / (totalBytes * 3)).toInt().coerceIn(0, 99)
        if (pct != lastPct) {
          lastPct = pct
          onPct(pct)
        }
      }
    }
    onPct(100)
  }

  private fun roundUp(n: Long): Long = if (n % 512L == 0L) n else n + (512L - n % 512L)

  private fun skipPadding(input: InputStream, size: Long) {
    var left = roundUp(size) - size
    val buf = ByteArray(512)
    while (left > 0) {
      val r = input.read(buf, 0, minOf(left, 512L).toInt())
      if (r < 0) break
      left -= r
    }
  }

  private fun readEntry(input: InputStream, size: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyN(input, out, size)
    skipPadding(input, size)
    return out.toByteArray()
  }

  private fun copyN(input: InputStream, output: java.io.OutputStream, size: Long) {
    var left = size
    val buf = ByteArray(1 shl 16)
    while (left > 0) {
      val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
      if (r < 0) break
      output.write(buf, 0, r)
      left -= r
    }
  }

  private fun skipN(input: InputStream, size: Long) {
    var left = size
    val buf = ByteArray(1 shl 16)
    while (left > 0) {
      val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
      if (r < 0) break
      left -= r
    }
  }

  private fun readFully(input: InputStream, buf: ByteArray): Boolean {
    var off = 0
    while (off < buf.size) {
      val r = input.read(buf, off, buf.size - off)
      if (r < 0) return off == buf.size
      off += r
    }
    return true
  }

  private fun cString(b: ByteArray, off: Int, len: Int): String {
    var end = off
    val limit = off + len
    while (end < limit && b[end].toInt() != 0) end++
    return String(b, off, end - off, Charsets.UTF_8)
  }

  companion object {
    // Real, stable Alpine minirootfs CDN. {arch} → aarch64 / armhf / x86_64 / x86.
    const val ALPINE_ROOTFS =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/{arch}/alpine-minirootfs-3.20.3-{arch}.tar.gz"
  }
}
