#!/bin/sh
# mkwrapper.sh — generate a minimal, buildable Android WebView "wrapper" app that
# loads a website. The generated project builds its APK on GitHub Actions (no
# Android SDK needed on the phone): push it, and CI produces app-debug.apk and
# attaches it to a rolling release.
#
# Usage:
#   sh mkwrapper.sh "App Name" com.example.app https://example.com [output-dir]
#
# Emits a self-contained project: no Gradle wrapper jar, no icon binaries, no
# AndroidX — the CI workflow uses a system Gradle and a built-in launcher icon so
# everything is plain text a shell can write.
set -eu

APP_NAME="${1:-My Web App}"
PKG="${2:-com.example.webapp}"
URL="${3:-https://example.com}"
OUT="${4:-}"

# A filesystem/Gradle-safe slug from the app name.
slug=$(printf '%s' "$APP_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//; s/-*$//')
[ -n "$slug" ] || slug=webapp
[ -n "$OUT" ] || OUT="./$slug"

# Package id must be dotted (com.example.app) so it maps to a source path.
case "$PKG" in
  *.*[!a-zA-Z0-9._]*|*[!a-zA-Z0-9._]*.*|.*|*.) echo "mkwrapper: package id must look like com.example.app" >&2; exit 2 ;;
  *.*) : ;;
  *) echo "mkwrapper: package id must look like com.example.app" >&2; exit 2 ;;
esac
pkgpath=$(printf '%s' "$PKG" | tr '.' '/')

# XML-escape values that land inside strings.xml.
esc_xml() { printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g; s/"/\&quot;/g'; }
APP_XML=$(esc_xml "$APP_NAME")
URL_XML=$(esc_xml "$URL")

SRC="$OUT/app/src/main"
mkdir -p "$SRC/java/$pkgpath" "$SRC/res/values" "$OUT/.github/workflows"

# ---- settings.gradle -------------------------------------------------------
cat > "$OUT/settings.gradle" <<EOF
pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositories { google(); mavenCentral() }
}
rootProject.name = "$slug"
include ":app"
EOF

# ---- root build.gradle -----------------------------------------------------
cat > "$OUT/build.gradle" <<'EOF'
plugins {
  id 'com.android.application' version '8.5.2' apply false
  id 'org.jetbrains.kotlin.android' version '1.9.24' apply false
}
EOF

# ---- gradle.properties -----------------------------------------------------
cat > "$OUT/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
EOF

# ---- app/build.gradle ------------------------------------------------------
cat > "$OUT/app/build.gradle" <<EOF
plugins {
  id 'com.android.application'
  id 'org.jetbrains.kotlin.android'
}

android {
  namespace '$PKG'
  compileSdk 34

  defaultConfig {
    applicationId '$PKG'
    minSdk 24
    targetSdk 34
    versionCode 1
    versionName '1.0'
  }

  buildTypes {
    release {
      minifyEnabled false
    }
  }

  compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = '17' }
}
EOF

# ---- AndroidManifest.xml ---------------------------------------------------
# No package attr (AGP takes it from `namespace`). Uses a built-in launcher icon
# and system theme so no binary resources are needed.
cat > "$SRC/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

  <uses-permission android:name="android.permission.INTERNET" />

  <application
      android:allowBackup="true"
      android:label="@string/app_name"
      android:icon="@android:drawable/sym_def_app_icon"
      android:usesCleartextTraffic="false"
      android:theme="@android:style/Theme.Material.Light.NoActionBar">

    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:configChanges="orientation|screenSize|keyboardHidden|screenLayout">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
EOF

# ---- res/values/strings.xml ------------------------------------------------
cat > "$SRC/res/values/strings.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <string name="app_name">$APP_XML</string>
  <string name="home_url">$URL_XML</string>
</resources>
EOF

# ---- MainActivity.kt -------------------------------------------------------
# Plain Activity (no AndroidX) hosting a full-screen WebView. In-app navigation,
# JS + DOM storage, file chooser, and hardware back = WebView back.
cat > "$SRC/java/$pkgpath/MainActivity.kt" <<EOF
package $PKG

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
  private lateinit var web: WebView

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    web = WebView(this)
    web.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      useWideViewPort = true
      loadWithOverviewMode = true
      cacheMode = WebSettings.LOAD_DEFAULT
    }
    // Keep navigation inside the app; let the page manage its own links.
    web.webViewClient = WebViewClient()
    web.webChromeClient = WebChromeClient()
    setContentView(web)
    if (savedInstanceState == null) {
      web.loadUrl(getString(R.string.home_url))
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    web.saveState(outState)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    web.restoreState(savedInstanceState)
  }

  @Suppress("DEPRECATION")
  override fun onBackPressed() {
    if (web.canGoBack()) web.goBack() else super.onBackPressed()
  }
}
EOF

# ---- .github/workflows/build-apk.yml ---------------------------------------
# Builds a debug-signed APK on every push and refreshes a rolling release so the
# same URL always serves the newest build. Uses a system Gradle (no wrapper jar).
cat > "$OUT/.github/workflows/build-apk.yml" <<'EOF'
name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: android-actions/setup-android@v3

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.7'

      - name: Build debug APK
        run: gradle assembleDebug --no-daemon --stacktrace

      - name: Stage APK
        run: |
          mkdir -p dist
          APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
          cp "$APK" dist/app-debug.apk
          ls -la dist

      - name: Upload build artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: dist/app-debug.apk

      - name: Publish rolling release
        if: github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh release delete android --yes --cleanup-tag 2>/dev/null || true
          gh release create android dist/app-debug.apk \
            --title "Android build ${GITHUB_SHA::7}" \
            --notes "Debug-signed APK built from ${GITHUB_SHA::7}. Enable 'Install unknown apps' to sideload." \
            --latest
EOF

# ---- README.md -------------------------------------------------------------
cat > "$OUT/README.md" <<EOF
# $APP_NAME

An Android app that wraps **$URL** in a native WebView.

## Install
Grab the APK from the [latest release](../../releases/latest) and sideload it
(enable "Install unknown apps"). Every push to \`main\` rebuilds it.

## Build locally
\`\`\`sh
gradle assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
\`\`\`

Generated by IntelliShell Builder.
EOF

echo "mkwrapper: wrote $APP_NAME ($PKG -> $URL) to $OUT"
