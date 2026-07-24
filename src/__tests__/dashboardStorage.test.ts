/**
 * Dashboard storage helpers — parse tolerance, upsert/remove ordering, preview.
 */
import {
  Script,
  parseScripts,
  parseNotes,
  upsertById,
  removeById,
  preview,
  itemId,
} from '../dashboardStorage';

describe('parseScripts / parseNotes', () => {
  it('round-trips valid data', () => {
    const scripts: Script[] = [{id: 'a', name: 'build', body: 'echo hi', updatedAt: 5}];
    expect(parseScripts(JSON.stringify(scripts))).toEqual(scripts);
  });
  it('returns [] for empty/corrupt input', () => {
    expect(parseScripts('')).toEqual([]);
    expect(parseScripts('nonsense')).toEqual([]);
    expect(parseScripts('{"not":"array"}')).toEqual([]);
    expect(parseNotes('')).toEqual([]);
  });
  it('drops malformed entries and fills missing fields', () => {
    const out = parseScripts(JSON.stringify([{id: 'a'}, {name: 'noid'}, {id: 'b', name: 'x', body: 'y', updatedAt: 2}]));
    expect(out.map(s => s.id)).toEqual(['a', 'b']);
    expect(out[0]).toEqual({id: 'a', name: 'script', body: '', updatedAt: 0});
  });
  it('parses notes with title fallback', () => {
    const out = parseNotes(JSON.stringify([{id: 'n1', body: 'text'}]));
    expect(out[0]).toEqual({id: 'n1', title: 'note', body: 'text', updatedAt: 0});
  });
});

describe('upsertById / removeById', () => {
  const it0 = {id: 'a', updatedAt: 1};
  it('inserts newest-first and replaces by id', () => {
    let list = upsertById<{id: string; updatedAt: number}>([], it0);
    list = upsertById(list, {id: 'b', updatedAt: 5});
    list = upsertById(list, {id: 'a', updatedAt: 9});
    expect(list.map(x => x.id)).toEqual(['a', 'b']);
    expect(list[0].updatedAt).toBe(9);
  });
  it('removes by id', () => {
    expect(removeById([{id: 'a'}, {id: 'b'}], 'a').map(x => x.id)).toEqual(['b']);
  });
});

describe('preview', () => {
  it('takes the first non-empty line, truncating', () => {
    expect(preview('\n\n  hello world  \nsecond')).toBe('hello world');
    expect(preview('x'.repeat(80), 10)).toBe('xxxxxxxxx…');
    expect(preview('')).toBe('');
  });
});

describe('itemId', () => {
  it('produces a stable string for a seed', () => {
    expect(itemId(1)).toBe(itemId(1));
    expect(itemId(1)).not.toBe(itemId(2));
  });
});
