#!/usr/bin/env node
/**
 * Regenerate src/agent/wrapperScaffold.ts from scripts/mkwrapper.sh.
 * Run after editing the generator: `npm run gen:scaffold`.
 * A unit test (scaffold.test.ts) fails if the two ever drift.
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const scriptPath = path.join(root, 'scripts', 'mkwrapper.sh');
const outPath = path.join(root, 'src', 'agent', 'wrapperScaffold.ts');

const b64 = fs.readFileSync(scriptPath).toString('base64');

const ts = `/**
 * The website→APK "wrapper" generator, shipped *inside* the app bundle so the
 * on-device agent can scaffold a complete Android project with no network
 * access. The canonical source is scripts/mkwrapper.sh; WRAPPER_GENERATOR_B64 is
 * its base64, and src/agent/__tests__/scaffold.test.ts guards the two against
 * drift (re-run \`npm run gen:scaffold\` after editing the script).
 *
 * The generator is staged into the sandbox once per Builder run, then invoked by
 * the agent as: sh ~/.intellishell/mkwrapper.sh "Name" com.pkg.id https://url
 */

/** Where the generator is written inside the shell sandbox (HOME-relative). */
export const SCAFFOLD_PATH = '~/.intellishell/mkwrapper.sh';

/** base64 of scripts/mkwrapper.sh — do not hand-edit; regenerate from the script. */
export const WRAPPER_GENERATOR_B64 =
  '${b64}';

/**
 * A shell snippet that decodes the bundled generator into the sandbox and marks
 * it executable. Run once (via the bridge) before a Builder turn so the agent
 * can rely on the generator being present. HOME-relative so it works in both the
 * Alpine (HOME=/root) and toybox fallback shells.
 */
export function stageScaffoldScript(): string {
  return [
    'set -e',
    'DIR="\${HOME:-$PWD}/.intellishell"',
    'mkdir -p "$DIR"',
    "base64 -d > \\"$DIR/mkwrapper.sh\\" <<'ISH_B64'",
    WRAPPER_GENERATOR_B64,
    'ISH_B64',
    'chmod +x "$DIR/mkwrapper.sh"',
    'echo "scaffold generator ready at $DIR/mkwrapper.sh"',
  ].join('\\n');
}
`;

fs.writeFileSync(outPath, ts);
console.log(`wrote ${path.relative(root, outPath)} (${b64.length} b64 chars)`);
