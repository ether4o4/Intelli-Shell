#!/usr/bin/env bash
#
# Build proot from source for Android with the NDK, and drop the result into
# android/app/src/main/jniLibs/<abi>/libproot.so so the APK ships full Alpine.
#
# Design:
#   • talloc is compiled standalone (static) and exposed to proot via a hand-written
#     pkg-config file — proot locates talloc through `pkg-config`.
#   • proot is built with NDK clang (dynamic against bionic, so on-device it uses the
#     system linker + libc) and lands in the native-lib dir, the one app-writable
#     location Android permits executing from.
#   • The loader blob is embedded with GNU cross-binutils objcopy/objdump (proot's
#     makefile parses GNU objdump output; llvm's differs).
#   • The 32-bit companion loader (HAS_LOADER_32BIT) is disabled — Alpine aarch64 is
#     all 64-bit, and building it would need a second 32-bit toolchain.
#
# Best-effort: always exits 0. If an ABI fails to build, it ships the toybox shell.
# Final line prints PROOT_BUILD_RESULT=full-alpine or =toybox-fallback.

set -u

OUT_ROOT="android/app/src/main/jniLibs"
API="${PROOT_API:-24}"
TALLOC_VER="${TALLOC_VER:-2.4.2}"
PROOT_REPO="https://github.com/proot-me/proot"
UTHASH_REPO="https://github.com/proot-me/uthash"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "No NDK found under $SDK/ndk — cannot build proot."
  echo "PROOT_BUILD_RESULT=toybox-fallback"
  exit 0
fi
echo "Using NDK: $NDK"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
AR="$TC/llvm-ar"
STRIP="$TC/llvm-strip"

echo "Installing pkg-config + GNU cross binutils"
sudo apt-get update -qq >/dev/null 2>&1 || true
sudo apt-get install -y -qq pkg-config binutils-aarch64-linux-gnu binutils-arm-linux-gnueabihf >/dev/null 2>&1 \
  || echo "warn: apt install had issues (loader embed may fail)"

built_any=0
WS="${GITHUB_WORKSPACE:-$PWD}"

write_replace_shim() {
  cat > "$1" <<'EOF'
#ifndef _MVE_REPLACE_H
#define _MVE_REPLACE_H
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <ctype.h>
#include <limits.h>
#include <sys/types.h>
#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
#ifndef MAX
#define MAX(a,b) ((a)>(b)?(a):(b))
#endif
#ifndef discard_const
#define discard_const(ptr) ((void *)((intptr_t)(ptr)))
#endif
#ifndef discard_const_p
#define discard_const_p(type, ptr) ((type *)((intptr_t)(ptr)))
#endif
#ifndef PRINTF_ATTRIBUTE
#define PRINTF_ATTRIBUTE(a,b) __attribute__((format(__printf__,a,b)))
#endif
#ifndef _PUBLIC_
#define _PUBLIC_
#endif
#ifndef _DEPRECATED_
#define _DEPRECATED_
#endif
#endif
EOF
}

build_one() {
  local abi="$1" triple="$2" gnu="$3"
  local CC="$TC/${triple}${API}-clang"
  local OBJCOPY_T="${gnu}-objcopy" OBJDUMP_T="${gnu}-objdump" STRIP_T="${gnu}-strip"
  if [ ! -x "$CC" ]; then echo "[$abi] no compiler at $CC — skipping"; return 1; fi
  command -v "$OBJCOPY_T" >/dev/null 2>&1 || { echo "[$abi] missing $OBJCOPY_T"; return 1; }
  echo "=== [$abi] building proot ==="
  local w; w="$(mktemp -d)"; pushd "$w" >/dev/null || return 1

  # --- talloc (standalone static) ---
  if ! curl -fsSL --retry 3 "https://download.samba.org/pub/talloc/talloc-${TALLOC_VER}.tar.gz" -o t.tgz; then
    echo "[$abi] talloc download failed"; popd >/dev/null; return 1
  fi
  tar xf t.tgz
  local T="talloc-${TALLOC_VER}"
  write_replace_shim "$T/replace.h"
  local V_MAJOR="${TALLOC_VER%%.*}" V_REST="${TALLOC_VER#*.}"
  local V_MINOR="${V_REST%%.*}" V_REL="${V_REST#*.}"
  echo "[$abi] compiling talloc ${V_MAJOR}.${V_MINOR}.${V_REL}"
  "$CC" -O2 -fPIC -I"$T" \
    -DTALLOC_BUILD_VERSION_MAJOR="$V_MAJOR" \
    -DTALLOC_BUILD_VERSION_MINOR="$V_MINOR" \
    -DTALLOC_BUILD_VERSION_RELEASE="$V_REL" \
    -Wno-macro-redefined -Wno-error \
    -c "$T/talloc.c" -o talloc.o 2>&1 | tail -40
  if [ ! -f talloc.o ]; then echo "[$abi] talloc compile FAILED"; popd >/dev/null; return 1; fi
  "$AR" rcs libtalloc.a talloc.o

  # pkg-config file so proot's `pkg-config --cflags/--libs talloc` resolves.
  cat > "$w/talloc.pc" <<EOF
Name: talloc
Description: talloc
Version: ${TALLOC_VER}
Cflags: -I$w/$T
Libs: -L$w -ltalloc
EOF

  # --- proot (+ uthash header submodule) ---
  if ! git clone --depth 1 "$PROOT_REPO" proot 2>/dev/null; then
    echo "[$abi] proot clone failed"; popd >/dev/null; return 1
  fi
  git clone --depth 1 "$UTHASH_REPO" proot/lib/uthash 2>/dev/null || echo "[$abi] warn: uthash clone failed"
  git -C proot tag v5.4.0 2>/dev/null || true   # give `git describe` a name

  echo "[$abi] compiling proot"
  # proot is old C; clang 15+ makes several legacy patterns hard errors. Downgrade
  # them to warnings by baking the flags into CC (without clobbering proot's own
  # CPPFLAGS/CFLAGS which carry its -I. include paths).
  local RELAX="-Wno-error -Wno-implicit-function-declaration -Wno-implicit-int -Wno-int-conversion -Wno-incompatible-function-pointer-types"

  # The proot loader links at a huge -Ttext vaddr (0x2000000000 on arm64). ld.lld
  # pads the output FILE up to that address (~128GB arm64 / 256MB armv7) — the build
  # dies on "No space". GNU ld keeps the file compact at the same vaddr. The loader
  # is freestanding (-static -nostdlib, only .o files), so we invoke GNU ld DIRECTLY
  # for that link (going through the clang driver makes it pass lld-only flags like
  # --no-rosegment that GNU ld rejects). Translate the driver args: expand -Wl,
  # groups into bare ld options, drop -nostdlib/-static (driver-only). Every other
  # link (the final dynamic proot) still uses clang's default lld.
  export MVE_GNU_LD="/usr/bin/${gnu}-ld" MVE_CC="$CC"
  cat > "$w/ldwrap" <<'WRAP'
#!/bin/sh
case " $* " in
  *Ttext*)
    newargs=""
    for a in "$@"; do
      case "$a" in
        -nostdlib|-static) ;;                                 # driver-only, drop
        -Wl,*) g=${a#-Wl,}; newargs="$newargs $(printf '%s' "$g" | tr ',' ' ')" ;;
        *) newargs="$newargs $a" ;;
      esac
    done
    exec "$MVE_GNU_LD" $newargs
    ;;
esac
exec "$MVE_CC" "$@"
WRAP
  chmod +x "$w/ldwrap"

  # STRIP must be GNU (llvm-strip OOMs on the loader's high -Ttext vaddr).
  # PYTHON=false skips proot's Python extension.
  PKG_CONFIG_PATH="$w" make -C proot/src V=1 \
    CC="$CC $RELAX" LD="$w/ldwrap" AR="$AR" STRIP="$STRIP_T" \
    OBJCOPY="$OBJCOPY_T" OBJDUMP="$OBJDUMP_T" \
    HAS_LOADER_32BIT= PYTHON=false \
    proot 2>&1 | tail -160

  if [ -x proot/src/proot ]; then
    mkdir -p "$WS/$OUT_ROOT/$abi"
    cp proot/src/proot "$WS/$OUT_ROOT/$abi/libproot.so"
    "$STRIP_T" "$WS/$OUT_ROOT/$abi/libproot.so" 2>/dev/null || true
    echo "[$abi] ✓ proot built ($(wc -c <"$WS/$OUT_ROOT/$abi/libproot.so") bytes)"
    file "$WS/$OUT_ROOT/$abi/libproot.so" 2>/dev/null || true
    built_any=1
  else
    echo "[$abi] ✗ proot build FAILED"
  fi
  popd >/dev/null
}

build_one arm64-v8a   aarch64-linux-android    aarch64-linux-gnu
build_one armeabi-v7a armv7a-linux-androideabi arm-linux-gnueabihf

if [ "$built_any" = 1 ]; then
  echo "PROOT_BUILD_RESULT=full-alpine"
else
  echo "PROOT_BUILD_RESULT=toybox-fallback"
fi
exit 0
