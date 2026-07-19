/**
 * Controller — turns a user submission into an agent run against the currently
 * selected provider, wiring the agent's callbacks into the store so the chat and
 * terminal update live.
 */
import {getState, actions} from '../store';
import {runAgent} from './agent';
import {makeCloudProvider} from '../llm/openaiStream';
import {makeLocalProvider} from '../llm/localLlama';
import {LlmProvider} from '../llm/types';
import {beginRun, stopAll as abortInFlight} from './abort';
import {Bridge} from '../native/bridge';

let running = false;
export function isRunning(): boolean {
  return running;
}

/**
 * Emergency stop. Instantly: abort the model request / on-device generation,
 * force-kill any running shell process, halt the loop, and hand control back.
 */
export function stopAll(): void {
  abortInFlight();
  void Bridge.killAll();
  running = false;
  actions.setStatus('idle');
  actions.addInfo('■ stopped by user');
}

async function providerFor(): Promise<LlmProvider> {
  const s = getState().settings;
  if (s.provider === 'local') {
    const path = await Bridge.modelPath(s.localModelId);
    if (!path) {
      throw new Error('That model isn’t downloaded yet — open Settings and download it.');
    }
    return makeLocalProvider(path);
  }
  const extra = /openrouter/i.test(s.cloudBaseUrl)
    ? {'HTTP-Referer': 'https://github.com/ether4o4/Intelli-Shell', 'X-Title': 'IntelliShell'}
    : undefined;
  return makeCloudProvider(
    {baseUrl: s.cloudBaseUrl, apiKey: s.cloudKey, model: s.cloudModel},
    extra,
  );
}

export async function send(text: string): Promise<void> {
  const t = text.trim();
  if (!t || running) {
    return;
  }
  const s = getState().settings;
  if (s.provider === 'cloud' && !s.cloudKey) {
    actions.openSettings(true);
    return;
  }

  let provider: LlmProvider;
  try {
    provider = await providerFor();
  } catch (e: any) {
    actions.addUser(t);
    actions.addInfo('⚠ ' + (e && e.message ? e.message : String(e)));
    actions.openSettings(true);
    return;
  }

  running = true;
  const signal = beginRun();
  try {
    await runAgent(
      provider,
      getState().history,
      t,
      {
        onUser: x => actions.addUser(x),
        onAssistantStart: () => actions.startAssistant(),
        onAssistantDelta: (id, d) => actions.appendAssistant(id, d),
        onAssistantEnd: id => actions.endAssistant(id),
        onStatus: st => actions.setStatus(st),
        onShellCommand: c => actions.addCmd(c),
        onShellOutput: (o, e) => actions.addOut(o, e),
      },
      signal,
    );
  } finally {
    running = false;
  }
}
