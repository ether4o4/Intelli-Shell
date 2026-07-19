/**
 * Model catalog. Local models are GGUF files pulled from Hugging Face and run
 * on-device via llama.rn; cloud presets are OpenAI-compatible endpoints the
 * user points at with their own key.
 */
export interface LocalModel {
  id: string;
  name: string;
  params: string;
  size: string;
  note: string;
  url: string; // Hugging Face GGUF download
}

export const LOCAL_MODELS: LocalModel[] = [
  {
    id: 'qwen2.5-3b-instruct-q4',
    name: 'Qwen2.5 3B Instruct',
    params: '3B',
    size: '~1.9 GB',
    note: 'Balanced, multilingual',
    url: 'https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf',
  },
  {
    id: 'llama3.2-3b-q4',
    name: 'Llama 3.2 3B',
    params: '3B',
    size: '~2.0 GB',
    note: 'Strong general chat',
    url: 'https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf',
  },
  {
    id: 'gemma2-2b-q4',
    name: 'Gemma 2 2B',
    params: '2B',
    size: '~1.6 GB',
    note: 'Lightest, fastest',
    url: 'https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf',
  },
  {
    id: 'phi3.5-mini-q4',
    name: 'Phi-3.5 Mini',
    params: '3.8B',
    size: '~2.2 GB',
    note: 'Great at reasoning',
    url: 'https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf',
  },
];

export interface CloudPreset {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
}

export const CLOUD_PRESETS: CloudPreset[] = [
  {id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini'},
  {id: 'openrouter', name: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', model: 'openrouter/auto'},
  {id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat'},
  {id: 'groq', name: 'Groq', baseUrl: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile'},
];

export function localModelById(id: string, custom: LocalModel[] = []): LocalModel | undefined {
  return LOCAL_MODELS.find(m => m.id === id) || custom.find(m => m.id === id);
}

/**
 * Build a LocalModel from a pasted GGUF URL (Hugging Face or any direct link).
 * Accepts hf "blob" page URLs and rewrites them to "resolve" download URLs.
 * Returns null if the URL isn't an https link to a .gguf file.
 */
export function customModelFromUrl(raw: string): LocalModel | null {
  let url = raw.trim();
  if (!/^https:\/\//i.test(url)) {
    return null;
  }
  // hf.co/owner/repo/blob/main/file.gguf → …/resolve/main/file.gguf
  url = url.replace(/^(https:\/\/(?:huggingface\.co|hf\.co)\/[^?#]+)\/blob\//i, '$1/resolve/');
  const path = url.split(/[?#]/)[0];
  if (!/\.gguf$/i.test(path)) {
    return null;
  }
  const file = path.split('/').pop() || 'model.gguf';
  const base = file.replace(/\.gguf$/i, '');
  const id = 'custom-' + base.toLowerCase().replace(/[^a-z0-9._-]+/g, '-');
  return {
    id,
    name: base,
    params: '?',
    size: '?',
    note: 'Custom GGUF',
    url: url.replace(/ /g, '%20'),
  };
}
