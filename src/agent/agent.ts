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
import {Bridge} from '../native/bridge';

/**
 * Command extraction. The prompt asks for ```sh, but models routinely answer
 * with ```bash, ```shell, an untagged ``` block, `$ `-prefixed lines, or get
 * truncated before the closing fence. If we only accept a perfectly-formed
 * ```sh block, all of those turns silently run nothing — the user sees the
 * command in chat and the app "just repeats what you want but doesn't do it".
 * So: accept any shell-ish fence tag, tolerate a missing closing fence, strip
 * shell prompts, and fall back to untagged blocks that clearly look like
 * commands.
 */

// Fence tags we treat as runnable shell. Anything else (python, json, …) is
// display-only. An empty tag is handled separately by looksLikeCommands().
const SHELL_TAGS = new Set([
  'sh',
  'bash',
  'shell',
  'zsh',
  'ash',
  'dash',
  'ksh',
  'posix',
  'console',
  'terminal',
  'shell-session',
  'sh-session',
  'bash-session',
]);

// `(?:```|$)` lets the final block be unterminated — streams truncated by
// token limits often lose the closing fence.
const FENCE_RE = /```[ \t]*([^\n`]*)\n([\s\S]*?)(?:```|$)/g;

// First words that mark an untagged block as "this is a command, run it".
const KNOWN_CMDS = new Set([
  'apk', 'apt', 'apt-get', 'dpkg', 'yum', 'dnf', 'pacman', 'pkg', 'brew',
  'pip', 'pip3', 'python', 'python3', 'node', 'npm', 'npx', 'yarn', 'git',
  'wget', 'curl', 'cd', 'ls', 'cat', 'echo', 'printf', 'mkdir', 'rmdir',
  'rm', 'cp', 'mv', 'ln', 'tar', 'gzip', 'gunzip', 'unzip', 'sh', 'ash',
  'bash', 'chmod', 'chown', 'touch', 'grep', 'find', 'sed', 'awk', 'which',
  'uname', 'busybox', 'ps', 'kill', 'df', 'du', 'free', 'head', 'tail',
  'wc', 'sort', 'uniq', 'cut', 'tr', 'xargs', 'tee', 'date', 'sleep',
  'make', 'gcc', 'cc', 'go', 'cargo', 'ssh', 'scp', 'rsync', 'env',
  'export', 'source', 'pwd', 'whoami', 'id', 'hostname', 'test', 'true',
]);

/** Strip a leading interactive-shell prompt ("$ " or "> ") from a line. */
function stripPrompt(line: string): string {
  return line.replace(/^[ \t]*[$>][ \t]+/, '');
}

/** The non-empty, non-comment lines of a block, prompt-stripped and trimmed. */
function commandLines(body: string): string[] {
  return body
    .split('\n')
    .map(l => stripPrompt(l).trim())
    .filter(l => l.length > 0 && !l.startsWith('#'));
}

/** Heuristic for untagged blocks: every line must start like a command. */
function looksLikeCommands(lines: string[]): boolean {
  if (!lines.length) {
    return false;
  }
  return lines.every(l => {
    const first = l.split(/[ \t]/, 1)[0];
    if (KNOWN_CMDS.has(first)) {
      return true;
    }
    // Paths (./configure, /usr/bin/foo) and VAR=value assignments count too.
    return /^\.?\//.test(first) || /^[A-Za-z_][A-Za-z0-9_]*=/.test(first);
  });
}

export interface ExtractedCommands {
  /** Whole script to execute — body kept intact so heredocs etc. survive. */
  script: string;
  /** Cleaned one-per-line view for the terminal pane. */
  lines: string[];
}

/**
 * Pull runnable commands out of a model reply, or null if there are none.
 * All shell-tagged blocks run, in order; untagged blocks only count when no
 * tagged block exists and their content clearly looks like commands.
 */
export function extractCommands(text: string): ExtractedCommands | null {
  const tagged: string[] = [];
  const untagged: string[] = [];
  FENCE_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = FENCE_RE.exec(text)) !== null) {
    const tag = m[1].trim().toLowerCase();
    const body = m[2];
    if (SHELL_TAGS.has(tag)) {
      tagged.push(body);
    } else if (tag === '') {
      untagged.push(body);
    }
  }

  let bodies = tagged;
  if (!bodies.length) {
    bodies = untagged.filter(b => looksLikeCommands(commandLines(b)));
  }

  const lines = bodies.map(commandLines).reduce((a, b) => a.concat(b), []);
  if (!lines.length) {
    return null;
  }
  // Execute the raw bodies (prompt-stripped, comments kept) so multi-line
  // constructs like heredocs keep their exact shape.
  const script = bodies
    .map(b => b.split('\n').map(stripPrompt).join('\n').replace(/^\n+/, '').replace(/\s+$/, ''))
    .join('\n');
  return {script, lines};
}

/** @deprecated older name — kept so external callers/tests don't break. */
export function extractShBlock(text: string): string[] | null {
  const r = extractCommands(text);
  return r ? r.lines : null;
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

const MAX_STEPS = 12;

function looksLikeError(out: string): boolean {
  return /(^|\n)\s*(error|not found|No such file|Permission denied|command not found)/i.test(out);
}

export async function runAgent(
  provider: LlmProvider,
  systemPrompt: string,
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
        messages: [{role: 'system', content: systemPrompt}, ...history],
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

    const cmds = extractCommands(full);
    if (!cmds) {
      cb.onStatus('idle');
      return;
    }

    // Run the block as one script so cd / vars persist across its lines.
    cb.onStatus('working');
    cmds.lines.forEach(c => cb.onShellCommand(c));
    let out = '';
    try {
      out = await Bridge.run(cmds.script);
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

  // Hitting the cap used to end the turn silently, which looks like a hang.
  const noteId = cb.onAssistantStart();
  cb.onAssistantDelta(noteId, `⚠ Paused after ${MAX_STEPS} steps — say "continue" to keep going.`);
  cb.onAssistantEnd(noteId);
  cb.onStatus('idle');
}
