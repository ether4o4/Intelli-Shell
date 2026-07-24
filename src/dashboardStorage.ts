/**
 * Dashboard persistence — pure helpers for the Scripts and Notes tabs. All
 * side-effect-free so they can be unit-tested directly; the store owns the
 * actual pref I/O via the native bridge.
 *
 * Layout in SharedPreferences:
 *   dash.scripts → Script[] JSON
 *   dash.notes   → Note[] JSON
 */
export interface Script {
  id: string;
  name: string;
  body: string;
  updatedAt: number;
}

export interface Note {
  id: string;
  title: string;
  body: string;
  updatedAt: number;
}

export const SCRIPTS_KEY = 'dash.scripts';
export const NOTES_KEY = 'dash.notes';

/** Stable-ish id without Date.now/Math.random collisions mattering (list-local). */
export function itemId(seed: number): string {
  return 'i' + seed.toString(36);
}

function str(v: any, fallback = ''): string {
  return typeof v === 'string' ? v : fallback;
}
function num(v: any): number {
  return typeof v === 'number' && isFinite(v) ? v : 0;
}

export function parseScripts(json: string): Script[] {
  if (!json) {
    return [];
  }
  try {
    const list = JSON.parse(json);
    if (!Array.isArray(list)) {
      return [];
    }
    return list
      .filter(s => s && typeof s.id === 'string')
      .map(s => ({id: s.id, name: str(s.name, 'script'), body: str(s.body), updatedAt: num(s.updatedAt)}));
  } catch (_e) {
    return [];
  }
}

export function parseNotes(json: string): Note[] {
  if (!json) {
    return [];
  }
  try {
    const list = JSON.parse(json);
    if (!Array.isArray(list)) {
      return [];
    }
    return list
      .filter(n => n && typeof n.id === 'string')
      .map(n => ({id: n.id, title: str(n.title, 'note'), body: str(n.body), updatedAt: num(n.updatedAt)}));
  } catch (_e) {
    return [];
  }
}

/** Insert or replace an item by id, keeping the list newest-first. */
export function upsertById<T extends {id: string; updatedAt: number}>(list: T[], item: T): T[] {
  return [item, ...list.filter(x => x.id !== item.id)].sort((a, b) => b.updatedAt - a.updatedAt);
}

export function removeById<T extends {id: string}>(list: T[], id: string): T[] {
  return list.filter(x => x.id !== id);
}

/** A one-line preview of a script/note body for list rows. */
export function preview(body: string, max = 60): string {
  const line = body.split('\n').find(l => l.trim().length > 0) || '';
  const t = line.trim();
  return t.length > max ? t.slice(0, max - 1) + '…' : t;
}
