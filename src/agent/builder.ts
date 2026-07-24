/**
 * Builder-mode staging. Before a Builder turn we prepare the sandbox so the
 * agent can scaffold and push with no further setup:
 *
 *   1. decode the bundled website→APK generator to ~/.intellishell/mkwrapper.sh
 *   2. write git push credentials (a credential-store file + user identity)
 *
 * Credentials are staged by US via a direct bridge call, NOT by the model, so the
 * token never enters the LLM's context. The staging shell interpolates the token
 * into a file write only — it is never echoed to stdout.
 */
import {Bridge} from '../native/bridge';
import {Settings} from '../store';
import {stageScaffoldScript} from './wrapperScaffold';

/** POSIX-single-quote a value so it can be embedded safely in a shell script. */
function shq(v: string): string {
  return `'${v.replace(/'/g, `'\\''`)}'`;
}

/**
 * Shell that writes git credentials into the sandbox HOME. Uses git's
 * `store` helper (~/.git-credentials) plus a global identity, so a later plain
 * `git push` over https authenticates with no token in the command line. Written
 * with `printf` (never echoed), and the credentials file is chmod 600.
 */
function gitCredentialScript(user: string, token: string, email: string): string {
  const mail = email || `${user}@users.noreply.github.com`;
  return [
    'set -e',
    'H="${HOME:-$PWD}"',
    // Git config: identity + the store credential helper.
    `printf '%s\\n' '[user]' ${shq(`\tname = ${user}`)} ${shq(`\temail = ${mail}`)} '[credential]' '\thelper = store' '[init]' '\tdefaultBranch = main' > "$H/.gitconfig"`,
    // The credential the store helper reads for github.com over https.
    `printf 'https://%s:%s@github.com\\n' ${shq(user)} ${shq(token)} > "$H/.git-credentials"`,
    'chmod 600 "$H/.git-credentials"',
    'echo "git credentials staged for github.com"',
  ].join('\n');
}

/**
 * Prepare the sandbox for a Builder run. Idempotent and safe to call before
 * every turn. Returns silently on any bridge error — the agent will still run,
 * just without pre-staging, and surface the failure through its own commands.
 */
export async function prepareBuilder(settings: Settings): Promise<void> {
  try {
    await Bridge.run(stageScaffoldScript());
  } catch (_e) {
    // Non-fatal: the generator can be re-staged by the agent if needed.
  }
  if (settings.githubUser && settings.githubToken) {
    try {
      await Bridge.run(
        gitCredentialScript(settings.githubUser, settings.githubToken, settings.githubEmail),
      );
    } catch (_e) {
      // Non-fatal: pushing will fail loudly and the agent will report it.
    }
  }
}
