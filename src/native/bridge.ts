/**
 * Bridge — typed access to the native module (Kotlin `MveBridge`).
 *
 *   • run(script)           — execute a shell script (proot+Alpine, or toybox)
 *   • setupSandbox()        — install the Linux rootfs; streams progress
 *   • sandboxStatus()       — mode + readiness
 *   • model download/list/delete/path — on-device GGUF management
 *   • get/setPref           — small settings in SharedPreferences
 *   • killAll()             — force-kill the running shell (emergency stop)
 *   • onSandbox/onDownload  — progress event streams
 *
 * When the native module isn't linked, calls degrade to harmless stubs.
 */
import {NativeModules, NativeEventEmitter} from 'react-native';

export interface SandboxStatus {
  installed: boolean;
  ready: boolean;
  working: boolean;
  proot: boolean;
  alpine: boolean;
  statusText: string;
}

export interface DownloadEvent {
  id: string;
  pct: number;
  done: boolean;
  error?: string;
}

export interface SandboxEvent {
  phase: string;
  pct: number;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
const M: any = NativeModules.MveBridge;
const emitter = M ? new NativeEventEmitter(M) : null;

export const Bridge = {
  isNative: M != null,

  run(script: string): Promise<string> {
    return M ? M.run(script) : Promise.resolve('[shell unavailable — native bridge not linked]');
  },
  setupSandbox(): Promise<string> {
    return M ? M.setupSandbox() : Promise.resolve('no bridge');
  },
  sandboxStatus(): Promise<SandboxStatus> {
    return M
      ? M.sandboxStatus()
      : Promise.resolve({
          installed: false,
          ready: false,
          working: false,
          proot: false,
          alpine: false,
          statusText: 'no bridge',
        });
  },

  modelPath(id: string): Promise<string> {
    return M && M.modelPath ? M.modelPath(id) : Promise.resolve('');
  },
  listDownloadedModels(): Promise<string[]> {
    return M && M.listDownloadedModels ? M.listDownloadedModels() : Promise.resolve([]);
  },
  deleteModel(id: string): Promise<void> {
    return M && M.deleteModel ? M.deleteModel(id) : Promise.resolve();
  },
  downloadModel(id: string, url: string): Promise<string> {
    return M && M.downloadModel ? M.downloadModel(id, url) : Promise.reject(new Error('no bridge'));
  },

  getPref(key: string): Promise<string> {
    return M && M.getPref ? M.getPref(key) : Promise.resolve('');
  },
  setPref(key: string, value: string): Promise<void> {
    return M && M.setPref ? M.setPref(key, value) : Promise.resolve();
  },

  /** Force-kill any running shell process. Part of the emergency stop. */
  killAll(): Promise<void> {
    return M && M.killAll ? M.killAll() : Promise.resolve();
  },

  onSandbox(cb: (e: SandboxEvent) => void): () => void {
    if (!emitter) {
      return () => {};
    }
    const sub = emitter.addListener('mve_sandbox', cb);
    return () => sub.remove();
  },
  onDownload(cb: (e: DownloadEvent) => void): () => void {
    if (!emitter) {
      return () => {};
    }
    const sub = emitter.addListener('mve_download', cb);
    return () => sub.remove();
  },
};
