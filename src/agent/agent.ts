/**
 * The agent loop — the thing that makes the split screen alive.
 *
 * Each turn: stream the model's reply into the chat; if it contains an `sh`
 * block, run that block in the sandbox (streaming the command + output into the
 * terminal), feed the output back, and loop — until the model answers with no
 * command. This is what lets the user watch it think (top) and work (bottom) at
 * the same time.
 */
import {ChatMessage, LlmProvider, AbortSignalLike} from '../llm/types';
import {SYSTEM_PROMPT} from './prompt';
import {Bridge} from '../native/bridge';

const FENCE = /```sh[^\n]*\n([\s\S]*?)```/i;

/** Pull the command lines out of the first ```sh block, or null if none. */
export function extractShBlock(text: string): string[] | null {
  const m = text.match(FENCE);
  if (!m) {
    return null;
  }
  const cmds = m[1]
    .split('\n')
    .map(l => l.trim())
    .filter(l => l.length > 0 && !l.startsWith('#'));
  return cmds.length ? cmds : null;
}

export interface AgentCallbacks {
  onUser(text: string): void;
  onAssistantStart(): string;
  onAssistantDelta(id: string, delta: string): void;
  onAssistantEnd(id: string): void;
  onStatus(s: 'thinking' | 'working' | 'idle'): void;
  onShellCommand(cmd: string): void;
  onShellOutput(text: string, isErr: boolean): void;
}

const MAX_STEPS = 6;

function looksLikeError(out: string): boolean {
  return /(^|\n)\s*(error|not found|No such file|Permission denied|command not found)/i.test(out);
}

export async function runAgent(
  provider: LlmProvider,
  history: ChatMessage[],
  userText: string,
  cb: AgentCallbacks,
  signal: AbortSignalLike,
): Promise<void> {
  cb.onUser(userText);
  history.push({role: 'user', content: userText});

  for (let step = 0; step < MAX_STEPS; step++) {
    if (signal.aborted) {
      cb.onStatus('idle');
      return;
    }

    cb.onStatus('thinking');
    const id = cb.onAssistantStart();

    let full = '';
    try {
      full = await provider.stream({
        messages: [{role: 'system', content: SYSTEM_PROMPT}, ...history],
        onDelta: d => cb.onAssistantDelta(id, d),
        signal,
      });
    } catch (e: any) {
      const msg = e && e.message ? e.message : String(e);
      if (msg !== 'aborted') {
        cb.onAssistantDelta(id, '\n⚠ ' + msg);
      }
      cb.onAssistantEnd(id);
      cb.onStatus('idle');
      return;
    }

    cb.onAssistantEnd(id);
    history.push({role: 'assistant', content: full});

    const cmds = extractShBlock(full);
    if (!cmds) {
      cb.onStatus('idle');
      return;
    }

    // Run the block as one script so cd / vars persist across its lines.
    cb.onStatus('working');
    cmds.forEach(c => cb.onShellCommand(c));
    let out = '';
    try {
      out = await Bridge.run(cmds.join('\n'));
    } catch (e: any) {
      out = 'error: ' + (e && e.message ? e.message : String(e));
    }
    cb.onShellOutput(out.length ? out : '(no output)', looksLikeError(out));

    // Emergency stop can land mid-command — don't feed results back or continue.
    if (signal.aborted) {
      cb.onStatus('idle');
      return;
    }

    const trimmed = out.length > 4000 ? out.slice(0, 4000) + '\n…(truncated)' : out;
    history.push({role: 'user', content: '[shell output]\n' + trimmed});
  }

  cb.onStatus('idle');
}
