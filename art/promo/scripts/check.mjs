// The checks that define "done" for the film, in one command (`npm run check`):
//
//   types    tsc --noEmit
//   format   Prettier, in check mode (npm run format fixes it)
//   facts    src/facts.json against the latest release and the repository (needs gh)
//   music    public/audio/music.wav and CUES.md are what npm run music makes now
//   ties     the music's picture ties (audio/score.py) match the scene code they follow
//   films    every rendered out/ketch-promo-<locale>.mp4 has no near-empty run outside the
//            lane wipes and the final fade (skipped when none is rendered yet)
//
//   node scripts/check.mjs [<check> …]       e.g. npm run check -- types music
//
// Runs every check (or the ones named), prints a summary, and exits 1 when any failed.
// Each check is also a script of its own; see README.md, "Check the film".
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {root} from './lib/remotion.mjs';

const bin = (name) => path.join(root, 'node_modules/.bin', name);
const hasFilms = () =>
  fs.existsSync(path.join(root, 'out')) &&
  fs.readdirSync(path.join(root, 'out')).some((f) => /^ketch-promo-.+\.mp4$/.test(f));

/** name → command, or a reason to skip it. */
const CHECKS = {
  types: () => [bin('tsc'), '--noEmit'],
  format: () => [bin('prettier'), '--check', '--log-level', 'warn', '.'],
  facts: () => ['node', 'scripts/update-facts.mjs', '--check'],
  music: () => ['node', 'scripts/check-music.mjs'],
  // check-music.mjs hides the generator's output, so its picture-tie WARNINGs need their own check.
  ties: () => ['audio/make-music.sh', '--check'],
  films: () =>
    hasFilms() ? ['node', 'scripts/check-film.mjs', 'all'] : 'no film in out/ yet (npm run film)',
};

const asked = process.argv.slice(2);
const unknown = asked.filter((a) => !(a in CHECKS));
if (unknown.length > 0) {
  console.error(
    `unknown check ${unknown.join(', ')}; the checks are ${Object.keys(CHECKS).join(', ')}`,
  );
  process.exit(2);
}

const results = [];
for (const name of asked.length > 0 ? asked : Object.keys(CHECKS)) {
  const command = CHECKS[name]();
  if (typeof command === 'string') {
    results.push([name, `skipped: ${command}`]);
    continue;
  }
  const shown = command.map((c) => (c.startsWith(root) ? path.relative(root, c) : c));
  console.log(`\n== ${name}: ${shown.join(' ')}`);
  const r = spawnSync(command[0], command.slice(1), {cwd: root, stdio: 'inherit'});
  results.push([name, r.status === 0 ? 'ok' : 'FAILED']);
}

console.log('\n== summary');
for (const [name, result] of results) console.log(`  ${name.padEnd(7)} ${result}`);
process.exit(results.some(([, r]) => r === 'FAILED') ? 1 : 0);
