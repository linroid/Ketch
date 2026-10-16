// Checks that the music bed matches the film before a render: render-film.sh runs it, and so
// does `npm run check`.
//
//   node scripts/check-music.mjs [--quick]
//
// With MUSIC on in src/config.ts, public/audio/music.wav must exist, be exactly as long as the
// film (src/timing.json), and be the file `npm run music` makes from the current timing and
// score. The generator is deterministic (audio/README.md: the same timing gives the same file
// byte for byte), so the last check renders the music again into a temporary folder, about 10 s,
// and compares the bytes of the WAV and of its cue sheet, public/audio/CUES.md. That catches a
// retime (`npm run retime`) or a score change without `npm run music` afterwards, which would
// otherwise render a film whose cues fall off its cuts. `--quick` skips it (length only).
//
// Exits 1, saying what to run, when the music does not match.
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {root} from './lib/remotion.mjs';

const WAV = path.join(root, 'public/audio/music.wav');
const CUES = path.join(root, 'public/audio/CUES.md');
const CONFIG = path.join(root, 'src/config.ts');
const TIMING = path.join(root, 'src/timing.json');

const quick = process.argv.slice(2).includes('--quick');
const fail = (message) => {
  console.error(`music: ${message}`);
  process.exit(1);
};

/**
 * MUSIC's value in src/config.ts. A declaration this cannot find stops the check instead of
 * passing it, so reformatting config.ts can never switch the check off unnoticed.
 */
const musicOn = () => {
  const m = fs
    .readFileSync(CONFIG, 'utf8')
    .match(/export\s+const\s+MUSIC\s*(?::\s*boolean\s*)?=\s*(true|false)\b/);
  if (!m)
    fail('cannot find `export const MUSIC = true | false` in src/config.ts; update this script');
  return m[1] === 'true';
};

/** Sample rate and length in sample frames of a PCM WAV file, from its RIFF chunks. */
const wavInfo = (file) => {
  const b = fs.readFileSync(file);
  if (b.toString('ascii', 0, 4) !== 'RIFF' || b.toString('ascii', 8, 12) !== 'WAVE')
    fail(`${path.relative(root, file)} is not a WAV file`);
  let rate;
  let blockAlign;
  for (let at = 12; at + 8 <= b.length;) {
    const id = b.toString('ascii', at, at + 4);
    const size = b.readUInt32LE(at + 4);
    if (id === 'fmt ') {
      rate = b.readUInt32LE(at + 12);
      blockAlign = b.readUInt16LE(at + 20);
    } else if (id === 'data' && rate && blockAlign) {
      return {rate, samples: Math.floor(size / blockAlign)};
    }
    at += 8 + size + (size % 2);
  }
  fail(`${path.relative(root, file)} has no fmt or data chunk`);
};

if (!musicOn()) {
  console.log('music: off (MUSIC = false in src/config.ts); the film renders silent');
  process.exit(0);
}
if (!fs.existsSync(WAV))
  fail(
    'public/audio/music.wav is missing: run npm run music (or set MUSIC = false in src/config.ts)',
  );

const timing = JSON.parse(fs.readFileSync(TIMING, 'utf8'));
const {rate, samples} = wavInfo(WAV);
const expected = Math.round((timing.total / timing.fps) * rate);
if (samples !== expected) {
  const s = (n) => `${(n / rate).toFixed(3)} s`;
  fail(
    `public/audio/music.wav is ${s(samples)} but the film is ${s(expected)} ` +
      `(${timing.total} frames in src/timing.json): run npm run music`,
  );
}
if (quick) {
  console.log('music: ok (length only, --quick)');
  process.exit(0);
}

// Render the music again and compare.
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ketch-music-check-'));
try {
  const wav = path.join(tmp, 'music.wav');
  const cues = path.join(tmp, 'CUES.md');
  try {
    execFileSync(path.join(root, 'audio/make-music.sh'), ['--out', wav, '--cues', cues], {
      cwd: root,
      stdio: ['ignore', 'pipe', 'pipe'],
      encoding: 'utf8',
    });
  } catch (e) {
    console.error(String(e.stdout ?? '') + String(e.stderr ?? ''));
    fail('could not render the music to compare (audio/make-music.sh failed above)');
  }
  // The cue sheet names the file it was written for; compare it as if written in place.
  const fresh = fs.readFileSync(cues, 'utf8').replaceAll(wav, 'public/audio/music.wav');
  if (!fs.readFileSync(wav).equals(fs.readFileSync(WAV)))
    fail(
      'public/audio/music.wav is not what src/timing.json and audio/ make now ' +
        '(a retime or a score change since it was rendered): run npm run music',
    );
  if (!fs.existsSync(CUES) || fresh !== fs.readFileSync(CUES, 'utf8'))
    fail('public/audio/CUES.md is out of date with the music: run npm run music');
  console.log('music: ok (music.wav and CUES.md are what npm run music makes now)');
} finally {
  fs.rmSync(tmp, {recursive: true, force: true});
}
