/**
 * Tiny observable store — no Redux. Holds chat messages, terminal lines, agent
 * status, the split ratio, and settings. Settings persist to the native
 * SharedPreferences via the bridge so keys/model survive restarts.
 */
import {useSyncExternalStore} from 'react';
import {ChatMessage} from './llm/types';
import {LocalModel} from './models';
import {Bridge} from './native/bridge';

export interface DownloadState {
  pct: number;
  done: boolean;
  error?: string;
}

export interface SandboxState {
  statusText: string;
  ready: boolean;
  proot: boolean;
  alpine: boolean;
}

export interface UiMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
}

export interface TermEntry {
  id: string;
  kind: 'cmd' | 'out' | 'err' | 'info';
  text: string;
}

export type Provider = 'cloud' | 'local';

/** Which pre-configured agent runs: the general shell agent or the APK Builder. */
export type AgentMode = 'shell' | 'builder';

export interface Settings {
  provider: Provider;
  cloudBaseUrl: string;
  cloudModel: string;
  cloudKey: string;
  localModelId: string;
  customModels: LocalModel[];
  agentMode: AgentMode;
  githubUser: string;
  githubToken: string;
  githubEmail: string;
}

export type Status = 'idle' | 'thinking' | 'working';

interface State {
  messages: UiMessage[];
  term: TermEntry[];
  status: Status;
  split: number;
  settings: Settings;
  settingsOpen: boolean;
  history: ChatMessage[];
  downloads: Record<string, DownloadState>;
  downloadedModels: string[];
  sandbox: SandboxState;
}

let counter = 0;
const nid = () => `${++counter}-${Math.random().toString(36).slice(2, 7)}`;

let state: State = {
  messages: [],
  term: [{id: nid(), kind: 'info', text: 'IntelliShell ready. Ask the agent to do something below.'}],
  status: 'idle',
  split: 0.54,
  settings: {
    provider: 'cloud',
    cloudBaseUrl: 'https://api.openai.com/v1',
    cloudModel: 'gpt-4o-mini',
    cloudKey: '',
    localModelId: 'qwen2.5-3b-instruct-q4',
    customModels: [],
    agentMode: 'shell',
    githubUser: '',
    githubToken: '',
    githubEmail: '',
  },
  settingsOpen: false,
  history: [],
  downloads: {},
  downloadedModels: [],
  sandbox: {statusText: 'checking…', ready: false, proot: false, alpine: false},
};

const subs = new Set<() => void>();
const emit = () => subs.forEach(f => f());
function set(patch: Partial<State>) {
  state = {...state, ...patch};
  emit();
}

export function subscribe(fn: () => void): () => void {
  subs.add(fn);
  return () => {
    subs.delete(fn);
  };
}
export function getState(): State {
  return state;
}
export function useStore<T>(selector: (s: State) => T): T {
  return useSyncExternalStore(subscribe, () => selector(state));
}

export const actions = {
  addUser(text: string) {
    set({messages: [...state.messages, {id: nid(), role: 'user', text}]});
  },
  startAssistant(): string {
    const id = nid();
    set({messages: [...state.messages, {id, role: 'assistant', text: ''}]});
    return id;
  },
  appendAssistant(id: string, delta: string) {
    set({messages: state.messages.map(m => (m.id === id ? {...m, text: m.text + delta} : m))});
  },
  endAssistant(_id: string) {
    // reserved for future trimming/formatting
  },
  addCmd(text: string) {
    set({term: [...state.term, {id: nid(), kind: 'cmd', text}]});
  },
  addOut(text: string, isErr: boolean) {
    set({term: [...state.term, {id: nid(), kind: isErr ? 'err' : 'out', text}]});
  },
  addInfo(text: string) {
    set({term: [...state.term, {id: nid(), kind: 'info', text}]});
  },
  clearTerm() {
    set({term: []});
  },
  setStatus(s: Status) {
    set({status: s});
  },
  setSplit(n: number) {
    set({split: Math.max(0.24, Math.min(0.76, n))});
  },
  openSettings(open: boolean) {
    set({settingsOpen: open});
  },
  setDownload(id: string, d: DownloadState) {
    set({downloads: {...state.downloads, [id]: d}});
  },
  setDownloadedModels(list: string[]) {
    set({downloadedModels: list});
  },
  setSandbox(s: SandboxState) {
    set({sandbox: s});
  },
  updateSettings(patch: Partial<Settings>) {
    const next = {...state.settings, ...patch};
    set({settings: next});
    void persist(next);
  },
  history(): ChatMessage[] {
    return state.history;
  },
};

async function persist(s: Settings) {
  try {
    await Bridge.setPref('provider', s.provider);
    await Bridge.setPref('cloud.baseUrl', s.cloudBaseUrl);
    await Bridge.setPref('cloud.model', s.cloudModel);
    await Bridge.setPref('cloud.key', s.cloudKey);
    await Bridge.setPref('local.modelId', s.localModelId);
    await Bridge.setPref('local.customModels', JSON.stringify(s.customModels));
    await Bridge.setPref('agent.mode', s.agentMode);
    await Bridge.setPref('github.user', s.githubUser);
    await Bridge.setPref('github.token', s.githubToken);
    await Bridge.setPref('github.email', s.githubEmail);
  } catch (_e) {}
}

export async function loadSettings(): Promise<void> {
  try {
    const [provider, baseUrl, model, key, localId, customJson, mode, ghUser, ghToken, ghEmail] =
      await Promise.all([
        Bridge.getPref('provider'),
        Bridge.getPref('cloud.baseUrl'),
        Bridge.getPref('cloud.model'),
        Bridge.getPref('cloud.key'),
        Bridge.getPref('local.modelId'),
        Bridge.getPref('local.customModels'),
        Bridge.getPref('agent.mode'),
        Bridge.getPref('github.user'),
        Bridge.getPref('github.token'),
        Bridge.getPref('github.email'),
      ]);
    const s = {...state.settings};
    if (provider === 'cloud' || provider === 'local') {
      s.provider = provider;
    }
    if (mode === 'shell' || mode === 'builder') {
      s.agentMode = mode;
    }
    if (ghUser) {
      s.githubUser = ghUser;
    }
    if (ghToken) {
      s.githubToken = ghToken;
    }
    if (ghEmail) {
      s.githubEmail = ghEmail;
    }
    if (baseUrl) {
      s.cloudBaseUrl = baseUrl;
    }
    if (model) {
      s.cloudModel = model;
    }
    if (key) {
      s.cloudKey = key;
    }
    if (localId) {
      s.localModelId = localId;
    }
    if (customJson) {
      try {
        const list = JSON.parse(customJson);
        if (Array.isArray(list)) {
          s.customModels = list.filter(
            (m: any) => m && typeof m.id === 'string' && typeof m.url === 'string',
          );
        }
      } catch (_e) {}
    }
    set({settings: s});
  } catch (_e) {}
}

/** Load settings + device state and wire native progress events. Call once on mount. */
export async function bootstrap(): Promise<void> {
  await loadSettings();

  try {
    const [models, status] = await Promise.all([
      Bridge.listDownloadedModels(),
      Bridge.sandboxStatus(),
    ]);
    actions.setDownloadedModels(models);
    actions.setSandbox({
      statusText: status.statusText,
      ready: status.ready,
      proot: status.proot,
      alpine: status.alpine,
    });
    actions.addInfo('shell: ' + status.statusText);
  } catch (_e) {}

  Bridge.onDownload(e => {
    actions.setDownload(e.id, {pct: e.pct, done: e.done, error: e.error});
    if (e.done) {
      Bridge.listDownloadedModels().then(actions.setDownloadedModels).catch(() => {});
    }
  });

  let lastPhase = '';
  Bridge.onSandbox(e => {
    if (e.phase !== lastPhase) {
      lastPhase = e.phase;
      actions.addInfo('● ' + e.phase);
    }
    if (e.pct >= 100) {
      Bridge.sandboxStatus()
        .then(s =>
          actions.setSandbox({
            statusText: s.statusText,
            ready: s.ready,
            proot: s.proot,
            alpine: s.alpine,
          }),
        )
        .catch(() => {});
    }
  });
}
