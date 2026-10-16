// Draws an asset's bounds on it, to check them by eye: each bound is a box in its own colour,
// listed with its colour on the console. Reads the manifest of the locale and the film's theme
// (after sync.mjs), or with --build the record PromoSnapshots.kt just wrote, before any sync.
// Needs ffmpeg.
//
//   node scripts/assets/show-bounds.mjs <locale> <asset> [--theme light] [--frame <n>] [--build]
//     [--out <png>]
//
// A clip shows its last frame unless --frame picks one (the bounds hold for every frame). The
// picture goes to out/bounds/<locale>-<asset>.png, 1280 px wide. See docs/ASSETS.md.
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fail, layoutOf, localeOf, promoRoot, snapshotDir, takeTheme} from './lib.mjs';

const COLOURS = [
  'red',
  'lime',
  'cyan',
  'yellow',
  'magenta',
  'orange',
  'deepskyblue',
  'white',
  'hotpink',
];
const WIDTH = 1280;

const args = process.argv.slice(2);
const theme = takeTheme(args);
const option = (name) => {
  const at = args.indexOf(name);
  return at < 0 ? undefined : args.splice(at, 2)[1];
};
const frameOption = option('--frame');
const out = option('--out');
const build = args.includes('--build');
const [localeName, asset] = args.filter((arg) => !arg.startsWith('--'));
if (!localeName || !asset)
  fail(
    'usage: node scripts/assets/show-bounds.mjs <locale> <asset> [--theme light] [--frame <n>] ' +
      '[--build] [--out <png>]',
  );
const locale = localeOf(localeName);

const {image, bounds} = build ? fromRecord() : fromManifest();
if (!bounds || Object.keys(bounds).length === 0) fail(`${asset} has no bounds`);
const target = out ?? path.join(promoRoot, 'out/bounds', `${locale.name}-${asset}.png`);
fs.mkdirSync(path.dirname(target), {recursive: true});

// Boxes are drawn on the full-size image, 4 px thick, then the picture is scaled down.
const boxes = Object.entries(bounds).map(([key, {x, y, w, h}], index) => {
  const colour = COLOURS[index % COLOURS.length];
  console.log(`${colour.padEnd(12)} ${key}: x ${x} y ${y} w ${w} h ${h}`);
  return `drawbox=x=iw*${x}:y=ih*${y}:w=iw*${w}:h=ih*${h}:color=${colour}@0.9:t=4`;
});
execFileSync('ffmpeg', [
  '-loglevel',
  'error',
  '-y',
  '-i',
  image,
  '-vf',
  [...boxes, `scale=${WIDTH}:-1`].join(','),
  target,
]);
console.log(`→ ${path.relative(process.cwd(), target)}`);

/** The asset's image and bounds from the locale's manifest. */
function fromManifest() {
  const manifest = JSON.parse(fs.readFileSync(layoutOf(locale, theme).manifest, 'utf8'));
  const clip = manifest.clips?.[asset];
  const still = manifest.stills?.[asset];
  if (still) return {image: path.join(promoRoot, 'public', still.path), bounds: still.bounds};
  if (!clip) fail(`${asset} is not in the ${locale.name} manifest of the ${theme} film`);
  const frame = frameOption === undefined ? clip.frames - 1 : Number(frameOption);
  const file = `${String(frame).padStart(4, '0')}.${clip.ext ?? 'png'}`;
  return {image: path.join(promoRoot, 'public', clip.path, file), bounds: clip.bounds};
}

/** The asset's image and bounds from the record the harness wrote. */
function fromRecord() {
  const dir = snapshotDir(locale.name, theme);
  const record = JSON.parse(fs.readFileSync(path.join(dir, `${asset}.json`), 'utf8'));
  if (record.kind === 'still')
    return {image: path.join(dir, 'stills', `${asset}.png`), bounds: record.bounds};
  const frame = frameOption === undefined ? record.frames - 1 : Number(frameOption);
  return {
    image: path.join(dir, asset, `${String(frame).padStart(4, '0')}.${record.ext ?? 'png'}`),
    bounds: record.bounds,
  };
}
