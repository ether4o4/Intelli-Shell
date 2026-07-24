/**
 * Guards the embedded website→APK generator and the Builder preset:
 *  - the base64 in wrapperScaffold.ts must decode to the exact scripts/mkwrapper.sh
 *    (regenerate with `npm run gen:scaffold` after editing the script),
 *  - the staging shell and Builder prompt must carry the pieces the agent relies on.
 */
import * as fs from 'fs';
import * as path from 'path';
import {
  WRAPPER_GENERATOR_B64,
  SCAFFOLD_PATH,
  stageScaffoldScript,
} from '../wrapperScaffold';
import {buildBuilderPrompt} from '../prompt';

const scriptPath = path.resolve(__dirname, '../../../scripts/mkwrapper.sh');

describe('wrapper generator embedding', () => {
  it('base64 decodes to the exact mkwrapper.sh (no drift)', () => {
    const onDisk = fs.readFileSync(scriptPath, 'utf8');
    const decoded = Buffer.from(WRAPPER_GENERATOR_B64, 'base64').toString('utf8');
    expect(decoded).toBe(onDisk);
  });

  it('staging shell decodes the generator to the advertised path and marks it +x', () => {
    const s = stageScaffoldScript();
    expect(s).toContain('base64 -d');
    expect(s).toContain(WRAPPER_GENERATOR_B64);
    expect(s).toContain('mkwrapper.sh');
    expect(s).toContain('chmod +x');
    // The path the prompt tells the agent to call must match where we write it.
    expect(SCAFFOLD_PATH).toBe('~/.intellishell/mkwrapper.sh');
  });

  it('the embedded generator is a real webview scaffolder', () => {
    const gen = Buffer.from(WRAPPER_GENERATOR_B64, 'base64').toString('utf8');
    expect(gen).toContain('WebView');
    expect(gen).toContain('AndroidManifest.xml');
    expect(gen).toContain('build-apk.yml');
    expect(gen).toContain('assembleDebug');
  });
});

describe('buildBuilderPrompt', () => {
  const gh = {user: 'octocat', hasToken: true};

  it('scopes the agent to coding + planning and names the generator path', () => {
    const p = buildBuilderPrompt(true, gh);
    expect(p).toContain('coding');
    expect(p).toContain(SCAFFOLD_PATH);
    expect(p).toContain('WebView');
    expect(p).toContain('octocat');
  });

  it('never instructs the model to read or print the token', () => {
    const p = buildBuilderPrompt(true, gh);
    expect(p).toMatch(/never (print|echo)/i);
  });

  it('tells the user to configure GitHub when nothing is set', () => {
    const p = buildBuilderPrompt(true, {user: '', hasToken: false});
    expect(p).toMatch(/set (their|your)? ?GitHub/i);
  });

  it('warns that pushing needs a token when a user has no token stored', () => {
    const p = buildBuilderPrompt(true, {user: 'octocat', hasToken: false});
    expect(p).toMatch(/token/i);
    expect(p).toContain('octocat');
  });

  it('tells the user to install Alpine when the shell is the toybox fallback', () => {
    const p = buildBuilderPrompt(false, gh);
    expect(p).toMatch(/Alpine/i);
  });
});
