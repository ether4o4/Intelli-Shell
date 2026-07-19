/**
 * OpenAI-compatible streaming client, implemented over XMLHttpRequest so it
 * works in React Native without any extra dependency. Reads Server-Sent Events
 * incrementally and forwards each token delta.
 *
 * Works against any `/chat/completions` endpoint that speaks the OpenAI wire
 * format: OpenAI, OpenRouter, DeepSeek, Groq, or the user's own gateway.
 */
import {ChatMessage, LlmProvider, StreamOpts} from './types';
import {registerAborter} from '../agent/abort';

export interface CloudConfig {
  baseUrl: string;
  apiKey: string;
  model: string;
}

export function makeCloudProvider(cfg: CloudConfig, extraHeaders?: Record<string, string>): LlmProvider {
  return {
    id: 'cloud',
    label: cfg.model,
    stream(opts: StreamOpts) {
      return streamChat(cfg, opts.messages, opts.onDelta, extraHeaders, opts.signal);
    },
  };
}

function streamChat(
  cfg: CloudConfig,
  messages: ChatMessage[],
  onDelta: (d: string) => void,
  extraHeaders: Record<string, string> | undefined,
  signal: {aborted: boolean} | undefined,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const url = cfg.baseUrl.replace(/\/+$/, '') + '/chat/completions';
    xhr.open('POST', url);
    xhr.setRequestHeader('Content-Type', 'application/json');
    if (cfg.apiKey) {
      xhr.setRequestHeader('Authorization', 'Bearer ' + cfg.apiKey);
    }
    if (extraHeaders) {
      Object.keys(extraHeaders).forEach(k => xhr.setRequestHeader(k, extraHeaders[k]));
    }

    let seen = 0;
    let buffer = '';
    let full = '';
    let settled = false;

    // Register with the emergency-stop core so stopAll() aborts this request
    // the instant the user hits STOP.
    const unregister = registerAborter(() => {
      try {
        xhr.abort();
      } catch (_e) {}
    });
    const finish = (fn: () => void) => {
      if (settled) {
        return;
      }
      settled = true;
      unregister();
      fn();
    };

    function consume(text: string) {
      buffer += text;
      let idx: number;
      // eslint-disable-next-line no-cond-assign
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx).trim();
        buffer = buffer.slice(idx + 1);
        if (!line || line.startsWith(':') || !line.startsWith('data:')) {
          continue;
        }
        const data = line.slice(5).trim();
        if (data === '[DONE]') {
          continue;
        }
        try {
          const j = JSON.parse(data);
          const delta = j?.choices?.[0]?.delta?.content;
          if (typeof delta === 'string' && delta.length) {
            full += delta;
            onDelta(delta);
          }
        } catch (_e) {
          // partial JSON across chunk boundary — wait for more
        }
      }
    }

    xhr.onreadystatechange = () => {
      if (signal && signal.aborted) {
        try {
          xhr.abort();
        } catch (_e) {}
        finish(() => reject(new Error('aborted')));
        return;
      }
      if (xhr.readyState >= 3) {
        const t = xhr.responseText || '';
        if (t.length > seen) {
          consume(t.slice(seen));
          seen = t.length;
        }
      }
      if (xhr.readyState === 4) {
        if (signal && signal.aborted) {
          finish(() => reject(new Error('aborted')));
        } else if (xhr.status === 0) {
          // aborted or dropped connection
          finish(() => reject(new Error('aborted')));
        } else if (xhr.status >= 200 && xhr.status < 300) {
          finish(() => resolve(full));
        } else {
          finish(() =>
            reject(new Error('HTTP ' + xhr.status + ': ' + String(xhr.responseText || '').slice(0, 300))),
          );
        }
      }
    };
    xhr.onerror = () =>
      finish(() => reject(new Error('Network error — check the base URL and your connection.')));

    xhr.send(JSON.stringify({model: cfg.model, messages, stream: true}));
  });
}
