# jniLibs — the proot binary for full Alpine Linux

The app runs a real Linux shell (proot + Alpine) when a **static `proot`
binary** is shipped here as a native library. Without it, the app falls back to
Android's built-in toybox shell — everything still works, you just don't get a
package manager (`apk`) or full coreutils.

Executables must live in the APK's native-library dir to be runnable on modern
Android (app data dirs are `noexec`), so `proot` ships disguised as a `.so`.

## Layout

Drop a static proot build per ABI, named `libproot.so`:

```
android/app/src/main/jniLibs/
  arm64-v8a/libproot.so        # most phones
  armeabi-v7a/libproot.so      # older 32-bit
  x86_64/libproot.so           # emulators
```

If your proot build needs a separate loader, add `libproot_loader.so` next to it
in the same ABI folder — `SandboxManager` sets `PROOT_LOADER` automatically when
it's present.

## Getting a binary

Use a static proot build for Android (e.g. from a Termux `proot` package or a
`proot-static` build), verify it runs on-device, and copy it in with the names
above. Gradle packs everything under `jniLibs/<abi>/` into the APK and extracts
it to the executable `nativeLibraryDir` at install time.

Once present, open **Settings → On-device → Linux shell → Set up** to install
the Alpine rootfs (downloaded from the Alpine CDN and extracted on-device).
