/**
 * Command extraction — the bug where "install X" replies were echoed but never
 * executed came from only accepting a perfectly-formed ```sh fence. These
 * tests pin down every reply shape models actually produce.
 */
import {extractCommands, extractShBlock} from '../agent';

describe('extractCommands', () => {
  it('extracts a plain ```sh block', () => {
    const r = extractCommands('Installing git.\n```sh\napk add git\n```\nDone.');
    expect(r).not.toBeNull();
    expect(r!.lines).toEqual(['apk add git']);
    expect(r!.script).toBe('apk add git');
  });

  it('extracts ```bash blocks (the common model default)', () => {
    const r = extractCommands('```bash\napk update\napk add python3\n```');
    expect(r!.lines).toEqual(['apk update', 'apk add python3']);
  });

  it.each(['shell', 'zsh', 'ash', 'console', 'terminal', 'Bash', 'SH'])(
    'accepts the %s fence tag',
    tag => {
      const r = extractCommands('```' + tag + '\napk add git\n```');
      expect(r).not.toBeNull();
      expect(r!.lines).toEqual(['apk add git']);
    },
  );

  it('handles a truncated reply with no closing fence', () => {
    const r = extractCommands('Let me install that.\n```sh\napk add nodejs npm');
    expect(r!.lines).toEqual(['apk add nodejs npm']);
  });

  it('strips "$ " shell prompts from lines', () => {
    const r = extractCommands('```sh\n$ apk update\n$ apk add git\n```');
    expect(r!.lines).toEqual(['apk update', 'apk add git']);
    expect(r!.script).toBe('apk update\napk add git');
  });

  it('runs untagged blocks that clearly look like commands', () => {
    const r = extractCommands('Run this:\n```\napk add git\ncd /root\n```');
    expect(r!.lines).toEqual(['apk add git', 'cd /root']);
  });

  it('ignores untagged blocks that look like prose or config', () => {
    const r = extractCommands('```\nThis is just an explanation of the plan.\n```');
    expect(r).toBeNull();
  });

  it('ignores non-shell code blocks', () => {
    const r = extractCommands('```python\nprint("hi")\n```');
    expect(r).toBeNull();
  });

  it('concatenates multiple shell blocks in order', () => {
    const r = extractCommands('```sh\napk update\n```\nthen\n```sh\napk add git\n```');
    expect(r!.lines).toEqual(['apk update', 'apk add git']);
    expect(r!.script).toBe('apk update\napk add git');
  });

  it('prefers tagged blocks over untagged ones', () => {
    const r = extractCommands('```\nsome plan text here\n```\n```sh\napk add git\n```');
    expect(r!.lines).toEqual(['apk add git']);
  });

  it('drops comment and blank display lines but keeps them in the script', () => {
    const r = extractCommands('```sh\n# install git\napk add git\n\n```');
    expect(r!.lines).toEqual(['apk add git']);
    expect(r!.script).toContain('# install git');
  });

  it('preserves heredoc bodies exactly in the script', () => {
    const block = 'cat > /tmp/f <<EOF\n  indented line\nEOF';
    const r = extractCommands('```sh\n' + block + '\n```');
    expect(r!.script).toBe(block);
  });

  it('returns null when there is no code block at all', () => {
    expect(extractCommands('You should run apk add git to install it.')).toBeNull();
  });

  it('returns null for an empty or comment-only block', () => {
    expect(extractCommands('```sh\n# nothing to do\n```')).toBeNull();
  });
});

describe('extractShBlock (legacy shim)', () => {
  it('returns just the lines', () => {
    expect(extractShBlock('```bash\napk add git\n```')).toEqual(['apk add git']);
    expect(extractShBlock('no commands here')).toBeNull();
  });
});
