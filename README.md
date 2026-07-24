# IntelliShell

An AI agent with its own Linux shell, in one Android app. **Chat on top, a live
terminal on the bottom.** Tell it what to do and watch it work — it explains
itself in the chat while it runs real commands in the terminal below.

## Download

Every push to `main` rebuilds the APK and republishes it, so this link
**always serves the latest build**:

**→ https://github.com/ether4o4/Intelli-Shell/releases/latest/download/intellishell.apk**

(The older stable URL
`…/releases/download/android-preview/intellishell.apk` points at the same
file and keeps working.)

Enable "Install unknown apps" to sideload; installing over the top keeps your
settings and the Alpine rootfs. Not sure which build you have? The
[release page](https://github.com/ether4o4/Intelli-Shell/releases/latest)
shows the exact commit and build time of the APK currently behind the link.

## The split screen

```
┌─────────────────────────────┐
│  IntelliShell   [STOP] [⚙]   │  app bar — emergency stop is always here
├─────────────────────────────┤
│  chat (you ⇄ the agent)      │  streams token by token
│  ─── drag to resize ───      │
│  shell (what it's running)   │  live command + output
├─────────────────────────────┤
│  Ask the agent to do…    [↑] │
└─────────────────────────────┘
```

When the agent decides to run something, it emits a shell block; the app runs it
in the sandbox, streams the command and output into the terminal, feeds the
result back to the model, and continues — until the task is done.

## Agent modes

Pick a mode at the top of Settings:

- **Shell agent** — the general-purpose agent described above: ask it to do
  anything in the Linux shell.
- **APK Builder** — a pre-configured, coding-focused preset that turns a website
  into an Android **WebView wrapper app** and pushes it to *your* GitHub, where
  CI builds the APK. It plans and it codes — and it declines anything that isn't
  a build/coding task, so it stays on the rails and acts instead of lecturing.

### How Builder works

A phone can't run the Android SDK, so the APK is compiled on **GitHub Actions**,
not on the device. Builder's on-device job is to scaffold the project and push
it:

1. A generator (`scripts/mkwrapper.sh`, shipped inside the app) writes a complete,
   buildable project — a Gradle module, a Kotlin `WebView` `Activity` that loads
   your URL, and a `.github/workflows/build-apk.yml` that compiles the APK.
2. The agent creates a repo under your account and pushes.
3. The workflow builds `app-debug.apk` and attaches it to a rolling `android`
   release, so `https://github.com/<you>/<repo>/releases/latest` always serves
   the newest build.

Set your **GitHub username + a personal access token** (repo scope) in Settings →
APK Builder. The token is stored only on the device, wired in as a `git` push
credential, and **never sent to the model**. Builder needs the Alpine shell (for
`git`) — set it up under On-device. Then just say:

> wrap https://news.ycombinator.com as "HN Reader"

and watch it scaffold, push, and hand you the releases URL. The generator is
also usable standalone:

```sh
sh scripts/mkwrapper.sh "My App" com.example.app https://example.com ./my-app
```

## Models: on-device or cloud

- **On-device** — pick a small GGUF model (Qwen2.5 3B, Llama 3.2 3B, Gemma 2 2B,
  Phi-3.5), download it from Hugging Face right in the app, and run it locally via
  **llama.rn** (llama.cpp). Private, offline, no key. Or paste **any GGUF link
  from Hugging Face** in Settings to add your own model to the list.
- **Cloud** — point it at any OpenAI-compatible endpoint (OpenAI, OpenRouter,
  DeepSeek, Groq, or your own gateway) with your own key. Streams live. Your key
  stays on the device.

Switch from the model chip in the app bar; manage downloads in Settings.

## The shell

Two modes, chosen automatically:

- **proot + Alpine Linux** — a real userland (apk, coreutils, busybox), installed
  on demand from Settings. Commands run as fake-root inside the rootfs.
- **toybox fallback** — Android's `/system/bin/sh` in a private sandbox. Always
  available: `ls, cat, grep, find, sed, awk, echo, wc, ps…`

The agent and UI never change between modes; installing Alpine just upgrades what
the shell can do. CI compiles proot from source with the NDK
(`scripts/build-proot.sh`) and bundles it per ABI, so the published APK ships the
full Alpine experience out of the box.

## Emergency stop

The red **STOP** button in the app bar is always live. One tap:

1. aborts the in-flight model request instantly,
2. stops on-device token generation,
3. **force-kills any running shell process** (native `destroyForcibly`),
4. halts the agent loop and returns control.

## Architecture

- `src/llm/*` — the `LlmProvider` interface. `openaiStream.ts` (cloud, SSE over
  XHR) and `localLlama.ts` (on-device, llama.rn) both implement it.
- `src/agent/*` — the loop tying the model to the shell, and the emergency-stop
  core.
- `src/native/bridge.ts` → Kotlin bridge + `SandboxManager` — the shell,
  model downloads, settings, and `killAll`.
- `src/ui/*` — split-screen panes, composer, settings.

## Build

React Native 0.81. Every push to `main` builds the APK and refreshes the rolling
`android-preview` release (`.github/workflows/publish-apk.yml`). Locally:

```bash
npm install --legacy-peer-deps
npm run android        # run on a device/emulator
npm run build:debug    # or build a debug APK
```
