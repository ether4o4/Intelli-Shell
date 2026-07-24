# IntelliShell

**Termux with a built-in AI agent** — one Android app where the AI has the same
file and shell reach you do. **Chat on top, a live terminal on the bottom:** tell
it what to do and watch it work, running real commands in the terminal below.

## Two views

- **Terminal** — the split screen: AI chat over a live shell.
- **Dashboard** (▦ in the app bar) — three tabs:
  - **Scripts** — save shell scripts and **Run** them in the terminal in one tap.
  - **Notes** — quick persistent notes.
  - **AI config** — provider + OpenAI/API keys, a **Hugging Face token** (for
    private/gated model downloads), on-device **model downloads**, the Linux
    shell installer, and **full file access** (Termux-level) for the agent.

Scripts and notes are saved on-device and survive restarts.

## File access

IntelliShell requests Android's **All files access** so the agent's shell can
read and write your storage like Termux. Grant it from Dashboard → AI config →
File access.

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

## Models: on-device or cloud

- **On-device** — download a small GGUF model from Hugging Face right in the app
  and run it locally via **llama.rn** (llama.cpp). Private, offline, no key. The
  list leads with two agent-tuned tool-callers — **xLAM-2 3B** (Salesforce, fast
  Q4) and **RefinedToolCall V5 3B** (Q6) — alongside Qwen2.5 3B, Llama 3.2 3B,
  Gemma 2 2B, and Phi-3.5. Paste **any GGUF link** to add your own, and set a
  Hugging Face token to pull private/gated repos.
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
