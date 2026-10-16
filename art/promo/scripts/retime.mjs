// Changes scene lengths in src/timing.json and moves everything after them: every later scene's
// start, the total, and each lane wipe with the cut it sits on. Lengths must be whole beats.
//
//   node scripts/retime.mjs <SceneId>=<frames> [...] [--dry-run]     (npm run retime -- S05=285)
//
// Afterwards: regenerate the music (npm run music; its cues follow timing.json), check the
// scene's own local timings (exits, softCutExitAt, camera keys) and look at the cuts
// (npm run stills -- out Promo:<cut-2>,<cut>,<cut+2>). See README.md.
import fs from 'node:fs';
import path from 'node:path';
import {root} from './lib/remotion.mjs';

const FILE = path.join(root, 'src/timing.json');
const USAGE = 'usage: node scripts/retime.mjs <SceneId>=<frames> [...] [--dry-run]';

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const specs = args.filter((a) => a !== '--dry-run');
if (specs.length === 0 || specs.some((s) => !/^S\d\d=\d+$/.test(s))) {
  console.error(USAGE);
  process.exit(2);
}

const fail = (message) => {
  console.error(message);
  process.exit(1);
};

const text = fs.readFileSync(FILE, 'utf8');
const t = JSON.parse(text);
const wipeScene = new Map(t.wipes.map((w) => [w, t.scenes.find((s) => s.start === w)?.id]));

for (const spec of specs) {
  const [id, value] = spec.split('=');
  const s = t.scenes.find((x) => x.id === id);
  const len = Number(value);
  if (!s) fail(`No scene ${id}`);
  if (len <= 0 || len % t.beat !== 0)
    fail(`${id}=${len} is not a whole number of beats (${t.beat} frames)`);
  s.len = len;
}

const old = JSON.parse(text);
let start = 0;
for (const s of t.scenes) {
  s.start = start;
  start += s.len;
}
t.total = start;
t.wipes = t.wipes.map((w) => {
  const id = wipeScene.get(w);
  if (!id) fail(`wipe at ${w} is not on a cut; fix timing.json by hand first`);
  return t.scenes.find((s) => s.id === id).start;
});

console.log('scene   start        len');
t.scenes.forEach((s, i) => {
  const o = old.scenes[i];
  const d = (a, b) => (a === b ? String(b) : `${a} -> ${b}`);
  console.log(`${s.id}     ${d(o.start, s.start).padEnd(12)} ${d(o.len, s.len)}`);
});
console.log(`total   ${old.total} -> ${t.total} (${(t.total / t.fps).toFixed(1)} s)`);
console.log(`wipes   ${old.wipes.join(', ')} -> ${t.wipes.join(', ')}`);

if (dryRun) {
  console.log('(dry run: nothing written)');
} else {
  const oneLine = text.trim().split('\n').length === 1;
  fs.writeFileSync(FILE, `${oneLine ? JSON.stringify(t) : JSON.stringify(t, null, 2)}\n`);
  console.log('src/timing.json updated. Next: npm run music, then check the cuts.');
}
