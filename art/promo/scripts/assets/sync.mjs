// Puts a locale's rendered real-UI assets for one film into it: links the clips and stills that
// PromoSnapshots.kt wrote to app/shared/build/snapshots/promo/<locale>/ (the dark film) or
// promo/<locale>/light/ (the light one) into public/, checks them against their records, and
// writes the manifest of the locale and theme (src/manifest.json for English, dark;
// src/manifest.<locale>.json for the other locales; .light before .json for the light film:
// lib.mjs, layoutOf) with each asset's size, frames, bounds and events. The browser-extension
// popup (tools/extension-popup) is already in public/ and is registered from there. Assets of
// the manifest that this run did not render stay as long as their files do. render-ui.sh runs
// this after Gradle; run it alone after a render you started yourself.
//
//   node scripts/assets/sync.mjs <locale> [--theme light]
//
// The manifest is written whole, in a fixed order (assets by name, fields in the order below),
// through a temporary file, so the same assets always give the same file. See docs/ASSETS.md.
//
// A clip's record may name the rate it was recorded at (`fps`, a whole multiple of 30: 30 or
// 60); the manifest keeps it, and leaves it out at 30, the default (DEFAULT_CLIP_FPS in
// src/assets.ts). `frames`, `events` and `syncedTo` are in the clip's own frames; the film maps
// them to its 30 fps design frames (src/assets.ts). It may name its frames' format (`ext`, png
// or jpg; left out at png, the default), and every record names its pixels per dp (`density`;
// 2 when a record names none), which the manifest keeps for the scenes' source-px constants.
import fs from 'node:fs';
import path from 'node:path';
import {
  fail,
  imageSize,
  layoutOf,
  linkOrCopy,
  localeOf,
  looksFlat,
  promoRoot,
  snapshotDir,
  takeTheme,
  THEMES,
  writeAtomic,
} from './lib.mjs';

/**
 * Stills another tool renders straight into public/, which the manifest lists all the same, by
 * the film's theme: the popup in that theme's colour scheme (tools/extension-popup/render.mjs).
 */
const externalStills = (theme) => ({
  [`popup-${theme}`]: `tools/extension-popup/render.sh <locale> --theme ${theme}`,
});

/** The fields of a manifest entry, in the order the manifest lists them. */
const CLIP_FIELDS = [
  'path',
  'frames',
  'fps',
  'ext',
  'width',
  'height',
  'density',
  'alpha',
  'bounds',
  'events',
  'syncedTo',
];
/** A clip's rate when its record names none (DEFAULT_CLIP_FPS in src/assets.ts). */
const DEFAULT_CLIP_FPS = 30;
/** A clip's frame format when its record names none, and the formats there may be. */
const DEFAULT_CLIP_EXT = 'png';
const CLIP_EXTS = ['png', 'jpg'];
/** Pixels per dp when a record names none (DEFAULT_ASSET_DENSITY in src/assets.ts). */
const DEFAULT_DENSITY = 2;
const STILL_FIELDS = ['path', 'width', 'height', 'density', 'bounds', 'syncedTo'];

const args = process.argv.slice(2);
const theme = takeTheme(args);
const [name] = args;
if (!name) fail('usage: node scripts/assets/sync.mjs <locale> [--theme light]');
const locale = localeOf(name);
const layout = layoutOf(locale, theme);
const publicDir = path.join(promoRoot, 'public');
const source = snapshotDir(locale.name, theme);
const warnings = [];

const previous = fs.existsSync(layout.manifest)
  ? JSON.parse(fs.readFileSync(layout.manifest, 'utf8'))
  : {};
const clips = {};
const stills = {};

// What the harness rendered, by its records.
const records = fs.existsSync(source)
  ? fs
      .readdirSync(source)
      .filter((file) => file.endsWith('.json'))
      .sort()
  : [];
for (const file of records) {
  const record = JSON.parse(fs.readFileSync(path.join(source, file), 'utf8'));
  if (record.locale !== locale.name)
    fail(`${file} is a ${record.locale} asset in the ${locale.name} folder`);
  // Records from before the light film name no theme: they are the dark film's.
  if ((record.theme ?? 'dark') !== theme)
    fail(`${file} is a ${record.theme} film's asset in the ${theme} film's folder`);
  checkBounds(record);
  if (record.kind === 'clip') clips[record.name] = syncClip(record);
  else if (record.kind === 'still') stills[record.name] = syncStill(record);
  else fail(`${file}: unknown kind ${record.kind}`);
}

// The popups, already in public/.
for (const [still, tool] of Object.entries(externalStills(theme))) {
  const relative = `${layout.stills}/${still}.png`;
  const file = path.join(publicDir, relative);
  if (!fs.existsSync(file)) {
    warnings.push(
      `${relative} is missing; render it with ${tool.replace('<locale>', locale.name)}`,
    );
    continue;
  }
  stills[still] = {path: relative, ...checkedSize(file), density: DEFAULT_DENSITY};
}

// Whatever the manifest had that this run did not touch, while its files are there.
for (const [clip, entry] of Object.entries(previous.clips ?? {})) {
  if (clip in clips) continue;
  const dir = path.join(publicDir, entry.path ?? `${layout.clips}/${clip}`);
  if (fs.existsSync(path.join(dir, `0000.${entry.ext ?? DEFAULT_CLIP_EXT}`)))
    clips[clip] = {...entry, path: path.relative(publicDir, dir)};
  else warnings.push(`dropped clip ${clip}: no frames in ${path.relative(promoRoot, dir)}`);
}
for (const [still, entry] of Object.entries(previous.stills ?? {})) {
  if (still in stills) continue;
  if (fs.existsSync(path.join(publicDir, entry.path))) stills[still] = entry;
  else warnings.push(`dropped still ${still}: ${entry.path} is missing`);
}

reportStrays();

const manifest = {
  $comment:
    'Generated by scripts/assets/sync.mjs from the real-UI assets; do not edit (docs/ASSETS.md).',
  locale: locale.name,
  theme,
  clips: Object.fromEntries(
    Object.keys(clips)
      .sort()
      .map((clip) => [clip, ordered(clips[clip], CLIP_FIELDS)]),
  ),
  stills: Object.fromEntries(
    Object.keys(stills)
      .sort()
      .map((still) => [still, ordered(stills[still], STILL_FIELDS)]),
  ),
};
writeAtomic(layout.manifest, `${format(manifest)}\n`);

console.log(
  `${path.relative(promoRoot, layout.manifest)}: ${Object.keys(clips).length} clips, ${Object.keys(stills).length} stills`,
);
for (const [clip, entry] of Object.entries(manifest.clips)) {
  console.log(
    `  clip  ${clip}: ${entry.frames} ${entry.ext ?? DEFAULT_CLIP_EXT} frames at ` +
      `${entry.fps ?? DEFAULT_CLIP_FPS} fps, ${entry.width}×${entry.height} ` +
      `(${entry.density ?? DEFAULT_DENSITY} px/dp)${keys(entry.bounds)}`,
  );
}
for (const [still, entry] of Object.entries(manifest.stills)) {
  console.log(
    `  still ${still}: ${entry.width}×${entry.height} ` +
      `(${entry.density ?? DEFAULT_DENSITY} px/dp)${keys(entry.bounds)}`,
  );
}
for (const warning of warnings) console.warn(`warning: ${warning}`);

/** Links a clip's frames into public/ and returns its manifest entry. */
function syncClip(record) {
  const from = path.join(source, record.name);
  const ext = record.ext ?? DEFAULT_CLIP_EXT;
  if (!CLIP_EXTS.includes(ext))
    fail(`${record.name}: frames in ${ext}, which is none of ${CLIP_EXTS.join(', ')}`);
  const frames = fs.existsSync(from)
    ? fs
        .readdirSync(from)
        .filter((file) => file.endsWith(`.${ext}`))
        .sort()
    : [];
  if (frames.length !== record.frames)
    fail(`${record.name}: ${frames.length} frames, its record says ${record.frames}`);
  const fps = record.fps ?? DEFAULT_CLIP_FPS;
  if (!Number.isInteger(fps) || fps <= 0 || fps % DEFAULT_CLIP_FPS !== 0)
    fail(`${record.name}: fps ${record.fps} is not a whole multiple of ${DEFAULT_CLIP_FPS}`);
  frames.forEach((file, index) => {
    if (file !== `${String(index).padStart(4, '0')}.${ext}`)
      fail(`${record.name}: frame ${index} is ${file}`);
  });
  // Every frame is the same size; the first and the last stand for them all.
  for (const file of [frames[0], frames.at(-1)]) {
    const size = imageSize(path.join(from, file));
    if (size.width !== record.width || size.height !== record.height) {
      fail(
        `${record.name}/${file} is ${size.width}×${size.height}, its record says ${record.width}×${record.height}`,
      );
    }
  }
  const middle = frames[Math.floor(frames.length / 2)];
  for (const file of [frames[0], middle, frames.at(-1)]) {
    if (looksFlat(path.join(from, file), record))
      warnings.push(`${record.name}/${file} looks like one flat colour`);
  }

  // Into a staging folder first, then swapped in, so the film never sees half a clip.
  const relative = `${layout.clips}/${record.name}`;
  const target = path.join(publicDir, relative);
  const staging = path.join(path.dirname(target), `.${record.name}.sync`);
  const old = path.join(path.dirname(target), `.${record.name}.old`);
  fs.rmSync(staging, {recursive: true, force: true});
  fs.rmSync(old, {recursive: true, force: true});
  fs.mkdirSync(staging, {recursive: true});
  for (const file of frames) linkOrCopy(path.join(from, file), path.join(staging, file));
  if (fs.existsSync(target)) fs.renameSync(target, old);
  fs.renameSync(staging, target);
  fs.rmSync(old, {recursive: true, force: true});

  return {
    path: relative,
    frames: record.frames,
    // 30 and png are the defaults: left out, so the manifests of such clips stay as they were.
    fps: fps === DEFAULT_CLIP_FPS ? undefined : fps,
    ext: ext === DEFAULT_CLIP_EXT ? undefined : ext,
    width: record.width,
    height: record.height,
    density: record.density ?? DEFAULT_DENSITY,
    alpha: record.alpha,
    bounds: record.bounds,
    events: record.events,
    syncedTo: record.syncedTo,
  };
}

/** Links a still into public/ and returns its manifest entry. */
function syncStill(record) {
  const from = path.join(source, 'stills', `${record.name}.png`);
  if (!fs.existsSync(from)) fail(`${record.name}: ${path.relative(promoRoot, from)} is missing`);
  const size = imageSize(from);
  if (size.width !== record.width || size.height !== record.height) {
    fail(
      `${record.name} is ${size.width}×${size.height}, its record says ${record.width}×${record.height}`,
    );
  }
  if (looksFlat(from, size)) warnings.push(`${record.name} looks like one flat colour`);
  const relative = `${layout.stills}/${record.name}.png`;
  const target = path.join(publicDir, relative);
  fs.mkdirSync(path.dirname(target), {recursive: true});
  if (!sameFile(from, target)) {
    // Linked beside it first, then renamed over it, so the film never sees a missing still.
    const staging = `${target}.sync`;
    fs.rmSync(staging, {force: true});
    linkOrCopy(from, staging);
    fs.renameSync(staging, target);
  }
  return {
    path: relative,
    width: record.width,
    height: record.height,
    density: record.density ?? DEFAULT_DENSITY,
    bounds: record.bounds,
    syncedTo: record.syncedTo,
  };
}

/** Whether `b` exists and is the same file as `a`, a link to it from an earlier sync. */
function sameFile(a, b) {
  if (!fs.existsSync(b)) return false;
  const [x, y] = [fs.statSync(a), fs.statSync(b)];
  return x.ino === y.ino && x.dev === y.dev;
}

/** The size of an external still, warning when it looks blank. */
function checkedSize(file) {
  const size = imageSize(file);
  if (looksFlat(file, size))
    warnings.push(`${path.relative(publicDir, file)} looks like one flat colour`);
  return size;
}

/** Fails on bounds outside the image or without area. */
function checkBounds(record) {
  for (const [key, {x, y, w, h}] of Object.entries(record.bounds ?? {})) {
    const inside = x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= 1.0001 && y + h <= 1.0001;
    if (!inside)
      fail(`${record.name}: bounds.${key} ${JSON.stringify({x, y, w, h})} is not inside the image`);
  }
}

/** Warns about files in this locale's public folders that no manifest entry uses. */
function reportStrays() {
  const used = new Set([
    ...Object.values(clips).map((entry) => path.normalize(entry.path ?? '')),
    ...Object.values(stills).map((entry) => path.normalize(entry.path)),
  ]);
  for (const [folder, isAsset] of [
    [layout.clips, (entry) => entry.isDirectory()],
    [layout.stills, (entry) => entry.isFile() && entry.name.endsWith('.png')],
  ]) {
    const dir = path.join(publicDir, folder);
    if (!fs.existsSync(dir)) continue;
    for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
      if (!isAsset(entry) || entry.name.startsWith('.')) continue;
      // The default locale's folders hold the other locales' folders too, and the dark film's
      // the other themes' (clips/light/, clips/zh-Hans/light/).
      if (entry.isDirectory() && locale.isDefault && localeFolder(entry.name)) continue;
      if (entry.isDirectory() && THEMES.includes(entry.name) && entry.name !== theme) continue;
      const relative = path.normalize(`${folder}/${entry.name}`);
      if (!used.has(relative)) warnings.push(`public/${relative} is in no manifest entry`);
    }
  }
}

/** Whether a folder of public/clips is another locale's. */
function localeFolder(folder) {
  return (
    folder !== locale.name && fs.existsSync(path.join(promoRoot, 'src', `manifest.${folder}.json`))
  );
}

/** `entry` with only `fields`, in that order, leaving out the empty ones. */
function ordered(entry, fields) {
  const result = {};
  for (const field of fields) {
    let value = entry[field];
    if (value === undefined || value === null) continue;
    if (field === 'bounds')
      value = Object.fromEntries(
        Object.keys(value)
          .sort()
          .map((key) => [key, value[key]]),
      );
    if (field === 'events')
      value = Object.fromEntries(Object.entries(value).sort(([, a], [, b]) => a - b));
    if (typeof value === 'object' && Object.keys(value).length === 0) continue;
    result[field] = value;
  }
  return result;
}

/**
 * JSON with two-space indents, except that an object of plain values nested inside an asset's
 * entry (a rect, the events, syncedTo) stays on one line, so a diff shows which rect moved.
 */
function format(value, depth = 0) {
  const indent = '  '.repeat(depth);
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  const entries = Object.entries(value);
  if (entries.length === 0) return '{}';
  const flat = entries.every(([, v]) => v === null || typeof v !== 'object');
  if (flat && depth >= 3)
    return `{${entries.map(([k, v]) => `${JSON.stringify(k)}: ${JSON.stringify(v)}`).join(', ')}}`;
  const inner = entries.map(([k, v]) => `${indent}  ${JSON.stringify(k)}: ${format(v, depth + 1)}`);
  return `{\n${inner.join(',\n')}\n${indent}}`;
}

/** ", bounds a, b" for a log line. */
function keys(bounds) {
  return bounds ? `, bounds ${Object.keys(bounds).join(', ')}` : '';
}
