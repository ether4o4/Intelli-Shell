export type Role = 'system' | 'user' | 'assistant';

export interface ChatMessage {
  role: Role;
  content: string;
}

export interface AbortSignalLike {
  aborted: boolean;
}

export interface StreamOpts {
  messages: ChatMessage[];
  onDelta: (delta: string) => void;
  signal?: AbortSignalLike;
}

/** A model backend. `stream` resolves with the full assistant text. */
export interface LlmProvider {
  id: string;
  label: string;
  stream(opts: StreamOpts): Promise<string>;
}
