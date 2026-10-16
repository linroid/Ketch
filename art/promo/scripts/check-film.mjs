// Scans rendered films for near-empty frames: the storyboard's soft-cut rule allows no run of
// more than MAX_RUN near-empty frames (a bare canvas, nothing on it yet or any more) except where
// the film is meant to be empty: the lane wipes and the final fade. The film opens on the
// finished lock-up, so its first frames get no allowance (OPENING).
//
//   node scripts/check-film.mjs [<file.mp4> | <locale>[-light][-4k60] | all]
//                                                               (npm run check:film -- …)
//
// `all` (the default) scans every out/ketch-promo-*.mp4 there is (the 4K60 and the light films
// too); a locale scans out/ketch-promo-<locale>.mp4, `<locale>-4k60`
// out/ketch-promo-<locale>-4k60.mp4, `<locale>-light` the light film. Prints each offending run
// with the scene and local frames it falls on, and exits 1 when there is one. Needs ffmpeg; a
// 77 s film takes a few seconds, a 4K60 one about a minute.
//
// Everything below is in design frames (timing.json, 30 fps). A film at another rate (60 fps)
// is scanned frame by frame and its frames counted as design frames (two to one), so the rule is
// the same length of time at any rate: no near-empty run longer than MAX_RUN design frames.
//
// A frame counts as near-empty when it has almost no edges: scaled to 480 × 270, ffmpeg's
// edgedetect (low 0.05, high 0.15) averages under EMPTY_YAVG. Text, UI and the sail all have
// edges; the canvas's gradients and blooms have none, on the dark canvas and the light one alike
// (edges measure change, not brightness: the bare light canvas scans about as empty as the dark
// one). The reviewers' scans that defined "done" used exactly these settings.
import {execFileSync, spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {root} from './lib/remotion.mjs';

/** Longest run of near-empty frames allowed outside the windows below (STORYBOARD.md). */
const MAX_RUN = 3;
/** Edge density (mean luma of the edge map, 0..255) under which a frame is near-empty. */
const EMPTY_YAVG = 0.6;
const SCAN_FILTER =
  'scale=480:270,edgedetect=low=0.05:high=0.15,signalstats,' +
  'metadata=print:key=lavfi.signalstats.YAVG:file=-';

/**
 * Frames at the start that may be empty: none. The film opens on the finished lock-up (S01's
 * frame 0 is the cover art), so its first frame already has edges (about 2.4 here, against
 * EMPTY_YAVG). Raise it only for an opening that fades in from black.
 */
const OPENING = 0;
/** Frames at the end that may be empty: S12's fade to black (FADE_LEN in S12OpenSource.tsx). */
const FINAL_FADE = 20;

const timing = JSON.parse(fs.readFileSync(path.join(root, 'src/timing.json'), 'utf8'));

/** The lane wipe's length (WIPE_LEN in src/timing.ts); it covers cut − len/2 … cut + len/2. */
const wipeLen = (() => {
  const m = fs.readFileSync(path.join(root, 'src/timing.ts'), 'utf8').match(/WIPE_LEN = (\d+)/);
  if (!m) throw new Error('check-film: cannot find WIPE_LEN in src/timing.ts; update this script');
  return Number(m[1]);
})();

/** Frames that may be empty, with why. */
const windows = [
  ...(OPENING > 0 ? [{from: 0, to: OPENING - 1, why: 'opening fade'}] : []),
  ...timing.wipes.map((cut) => ({
    from: cut - wipeLen / 2,
    to: cut + wipeLen / 2,
    why: `lane wipe at ${cut}`,
  })),
  {from: timing.total - FINAL_FADE, to: timing.total - 1, why: 'final fade'},
];
const allowed = (f) => windows.some((w) => f >= w.from && f <= w.to);

/** The frame rate of a film's video, from ffprobe (e.g. 30 or 60). */
const fpsOf = (file) => {
  const out = execFileSync(
    'ffprobe',
    ['-v', 'error', '-select_streams', 'V:0', '-show_entries', 'stream=r_frame_rate', file],
    {encoding: 'utf8'},
  );
  const m = out.match(/r_frame_rate=(\d+)\/(\d+)/);
  if (!m) throw new Error(`check-film: cannot read the frame rate of ${file}`);
  return Number(m[1]) / Number(m[2]);
};

/** Scene and local frame of a global (design) frame, e.g. {id: 'S06', local: 120}. */
const sceneAt = (f) => {
  const s = timing.scenes.find((x) => f >= x.start && f < x.start + x.len);
  return s ? {id: s.id, local: f - s.start} : {id: 'past the end', local: f - timing.total};
};

/** Where design frames a…b fall: "S06 local 120–123"; across a cut "S05 local 268 – S06 local 2". */
const where = (a, b) => {
  const x = sceneAt(a);
  const y = sceneAt(b);
  return x.id === y.id
    ? `${x.id} local ${x.local}–${y.local}`
    : `${x.id} local ${x.local} – ${y.id} local ${y.local}`;
};

/** Edge density of every frame of `file`, in frame order. */
const scan = (file) => {
  const r = spawnSync(
    'ffmpeg',
    ['-v', 'error', '-i', file, '-vf', SCAN_FILTER, '-an', '-f', 'null', '-'],
    {
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    },
  );
  if (r.error)
    throw new Error(`check-film: cannot run ffmpeg (${r.error.message}); brew install ffmpeg`);
  if (r.status !== 0) throw new Error(`check-film: ffmpeg failed on ${file}:\n${r.stderr}`);
  return [...r.stdout.matchAll(/YAVG=([\d.]+)/g)].map((m) => Number(m[1]));
};

/** Runs of consecutive frames for which `pick` holds, as [first, last]. */
const runs = (n, pick) => {
  const out = [];
  let start = -1;
  for (let f = 0; f <= n; f++) {
    if (f < n && pick(f)) {
      if (start < 0) start = f;
    } else if (start >= 0) {
      out.push([start, f - 1]);
      start = -1;
    }
  }
  return out;
};

/** Checks one film; returns the number of problems. */
const checkFilm = (file) => {
  const rel = path.relative(root, file);
  const fps = fpsOf(file);
  // Output frames per design frame (1 at 30 fps, 2 at 60), and a frame's design frame.
  const k = fps / timing.fps;
  if (!Number.isInteger(k) || k < 1) {
    console.log(`${rel}: ${fps} fps is not a whole multiple of ${timing.fps} (src/timing.json)`);
    return 1;
  }
  const design = (f) => Math.floor(f / k);
  const yavg = scan(file);
  let problems = 0;
  if (yavg.length !== timing.total * k) {
    console.log(
      `${rel}: ${yavg.length} frames, but src/timing.json has ${timing.total * k} at ${fps} fps: ` +
        'render it again',
    );
    problems++;
  }
  const empty = (f) => yavg[f] < EMPTY_YAVG;
  const bad = runs(yavg.length, (f) => empty(f) && !allowed(design(f))).filter(
    ([a, b]) => b - a + 1 > MAX_RUN * k,
  );
  const ok = runs(yavg.length, empty).length;
  const at = k === 1 ? '' : ` at ${fps} fps`;
  for (const [a, b] of bad) {
    const min = Math.min(...yavg.slice(a, b + 1)).toFixed(3);
    const span = k === 1 ? '' : ` (design ${design(a)}–${design(b)})`;
    console.log(
      `${rel}: frames ${a}–${b}${at}${span} (${where(design(a), design(b))}): ` +
        `${b - a + 1} near-empty, min ${min}`,
    );
    problems++;
  }
  if (problems === 0)
    console.log(
      `${rel}: ok (${yavg.length} frames${at}; ${ok} near-empty runs, each a wipe, a fade ` +
        `or at most ${MAX_RUN} design frames)`,
    );
  return problems;
};

// Which films.
const arg = process.argv[2] ?? 'all';
if (process.argv.length > 3 || arg.startsWith('-')) {
  console.error('usage: node scripts/check-film.mjs [<file.mp4> | <locale>[-light][-4k60] | all]');
  process.exit(2);
}
const outDir = path.join(root, 'out');
let files;
if (arg.endsWith('.mp4')) {
  files = [path.resolve(arg)];
} else if (arg === 'all') {
  files = fs.existsSync(outDir)
    ? fs
        .readdirSync(outDir)
        .filter((f) => /^ketch-promo-.+\.mp4$/.test(f))
        .map((f) => path.join(outDir, f))
    : [];
  if (files.length === 0) {
    console.error(
      'check-film: no out/ketch-promo-<locale>.mp4 to scan; render one with npm run film',
    );
    process.exit(1);
  }
} else {
  files = [path.join(outDir, `ketch-promo-${arg}.mp4`)];
}
for (const f of files) {
  if (!fs.existsSync(f)) {
    console.error(
      `check-film: ${path.relative(root, f)} does not exist; render it with npm run film`,
    );
    process.exit(1);
  }
}

execFileSync('ffmpeg', ['-version'], {stdio: 'ignore'});
const problems = files.reduce((n, f) => n + checkFilm(f), 0);
process.exit(problems > 0 ? 1 : 0);
