/**
 * On-device provider — runs a GGUF model locally through llama.rn (llama.cpp).
 *
 * llama.rn is required lazily so the app builds and runs even in a variant that
 * doesn't bundle the native inference engine. `localAvailable()` reports whether
 * this build actually has it; until then the provider throws a clear message and
 * the UI steers the user to a cloud model.
 *
 * The engine build (milestone 2) adds `llama.rn` to package.json and NDK config
 * to CI — no changes needed here beyond that.
 */
import {LlmProvider, StreamOpts} from './types';
import {registerAborter} from '../agent/abort';

/* eslint-disable @typescript-eslint/no-explicit-any */
let mod: any = null;
let tried = false;

function load(): any {
  if (tried) {
    return mod;
  }
  tried = true;
  try {
    // Present only when this build bundles the on-device engine.
    mod = require('llama.rn');
  } catch (_e) {
    mod = null;
  }
  return mod;
}

export function localAvailable(): boolean {
  return load() != null;
}

let ctx: any = null;
let loadedPath = '';

async function ensureModel(path: string): Promise<any> {
  const m = load();
  if (!m) {
    throw new Error(
      'On-device engine is not in this build yet. Pick a cloud model in Settings, or install the local-model build.',
    );
  }
  if (ctx && loadedPath === path) {
    return ctx;
  }
  if (ctx) {
    try {
      await ctx.release();
    } catch (_e) {}
    ctx = null;
  }
  const init = m.initLlama || (m.default && m.default.initLlama);
  ctx = await init({model: path, n_ctx: 4096, n_gpu_layers: 0});
  loadedPath = path;
  return ctx;
}

export function makeLocalProvider(modelPath: string): LlmProvider {
  return {
    id: 'local',
    label: 'on-device',
    async stream(opts: StreamOpts): Promise<string> {
      const c = await ensureModel(modelPath);
      // Emergency stop: halt token generation immediately.
      const unregister = registerAborter(() => {
        try {
          if (c.stopCompletion) {
            c.stopCompletion();
          }
        } catch (_e) {}
      });
      try {
        const res = await c.completion(
          {messages: opts.messages, n_predict: 512, temperature: 0.7, stop: ['<|im_end|>', '</s>']},
          (tk: any) => {
            if (opts.signal && opts.signal.aborted) {
              return;
            }
            const token = tk && (tk.token || tk.content);
            if (typeof token === 'string' && token.length) {
              opts.onDelta(token);
            }
          },
        );
        return (res && (res.text || res.content)) || '';
      } finally {
        unregister();
      }
    },
  };
}
