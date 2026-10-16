// Helpers shared by the real-UI asset scripts (sync.mjs, tools/extension-popup/render.mjs):
// where things live, the locales, image sizes and atomic writes. Plain Node, no dependencies.
// See docs/ASSETS.md.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

/** art/promo, the Remotion project. */
export const promoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** The repository (the worktree) art/promo lives in. */
export const repoRoot = path.resolve(promoRoot, '../..');

/**
 * The films' themes, by the name `--theme` takes: the dark film (the default) and the light one,
 * as FILM_THEMES in src/theme-context.tsx. Every asset is named for the theme it shows
 * (`discover-dark`, `discover-light`); a film's assets are those its theme's run records.
 */
export const THEMES = ['dark', 'light'];
export const DEFAULT_THEME = 'dark';

/** The theme `name`; exits with the known themes when there is none. */
export const themeOf = (name = DEFAULT_THEME) => {
  if (!THEMES.includes(name)) fail(`unknown theme "${name}"; known: ${THEMES.join(', ')}`);
  return name;
};

/**
 * Takes `--theme <theme>` out of `args` (an argv array, changed in place) and returns the theme,
 * DEFAULT_THEME without one.
 */
export const takeTheme = (args) => {
  const at = args.indexOf('--theme');
  return at < 0 ? DEFAULT_THEME : themeOf(args.splice(at, 2)[1]);
};

/**
 * Where PromoSnapshots.kt writes a locale's assets and their records: promo/<locale>/ for the
 * dark film, promo/<locale>/<theme>/ for another (its PromoDir).
 */
export const snapshotDir = (locale, theme = DEFAULT_THEME) =>
  path.join(
    repoRoot,
    'app/shared/build/snapshots/promo',
    locale,
    theme === DEFAULT_THEME ? '' : theme,
  );

/** The locale table, scripts/assets/locales.json. */
export const readLocales = () =>
  JSON.parse(fs.readFileSync(path.join(promoRoot, 'scripts/assets/locales.json'), 'utf8'));

/** The locale `name`'s row of the table; exits with the known locales when there is none. */
export const localeOf = (name) => {
  const table = readLocales();
  const row = table.locales[name];
  if (!row) fail(`unknown locale "${name}"; known: ${Object.keys(table.locales).join(', ')}`);
  return {name, isDefault: name === table.default, ...row};
};

/**
 * Where a locale's assets for the film in `theme` go, relative to public/, and their manifest:
 * the default locale keeps clips/<name>, stills/<name>.png and src/manifest.json; another goes
 * under clips/<locale>/, stills/<locale>/ and src/manifest.<locale>.json. The light film's
 * assets go into a light/ folder below those (clips/light/, clips/<locale>/light/, …) and its
 * manifests are src/manifest.light.json and src/manifest.<locale>.light.json. src/assets.ts
 * reads the same layout.
 */
export const layoutOf = (locale, theme = DEFAULT_THEME) => {
  const folder = (base) => {
    const local = locale.isDefault ? base : `${base}/${locale.name}`;
    return theme === DEFAULT_THEME ? local : `${local}/${theme}`;
  };
  const name = [
    'manifest',
    ...(locale.isDefault ? [] : [locale.name]),
    ...(theme === DEFAULT_THEME ? [] : [theme]),
    'json',
  ].join('.');
  return {
    clips: folder('clips'),
    stills: folder('stills'),
    manifest: path.join(promoRoot, 'src', name),
  };
};

/** Width and height of a PNG, from its header. */
export const pngSize = (file) => {
  const fd = fs.openSync(file, 'r');
  try {
    const header = Buffer.alloc(24);
    fs.readSync(fd, header, 0, 24, 0);
    if (header.readUInt32BE(0) !== 0x89504e47 || header.toString('ascii', 12, 16) !== 'IHDR') {
      throw new Error(`${file} is not a PNG`);
    }
    return {width: header.readUInt32BE(16), height: header.readUInt32BE(20)};
  } finally {
    fs.closeSync(fd);
  }
};

/** Width and height of a JPEG, from its first frame header (SOF0–SOF15 but DHT, JPG, DAC). */
export const jpegSize = (file) => {
  const data = fs.readFileSync(file);
  if (data.readUInt16BE(0) !== 0xffd8) throw new Error(`${file} is not a JPEG`);
  let at = 2;
  while (at + 9 < data.length) {
    if (data[at] !== 0xff) throw new Error(`${file}: no marker at ${at}`);
    const marker = data[at + 1];
    const length = data.readUInt16BE(at + 2);
    const frame = marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker);
    if (frame) return {width: data.readUInt16BE(at + 7), height: data.readUInt16BE(at + 5)};
    at += 2 + length;
  }
  throw new Error(`${file} has no frame header`);
};

/** Width and height of a PNG or a JPEG, by its extension. */
export const imageSize = (file) => (/\.jpe?g$/i.test(file) ? jpegSize(file) : pngSize(file));

/**
 * Whether an image of this size is small enough to be one flat colour: a real screen compresses to
 * hundreds of KB, a blank frame to a few (as a PNG or a JPEG). Not proof, but it catches a scene that drew nothing.
 */
export const looksFlat = (file, {width, height}) =>
  fs.statSync(file).size < (width * height) / FLAT_PIXELS_PER_BYTE;
const FLAT_PIXELS_PER_BYTE = 400;

/** Writes `text` to `file` through a temporary file and a rename, so no reader sees half. */
export const writeAtomic = (file, data) => {
  const temporary = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(temporary, data);
  fs.renameSync(temporary, file);
};

/**
 * Puts `source` at `target` as a hard link, so a clip in public/ costs no more disk than the
 * render it came from; a copy where links do not work (another volume).
 */
export const linkOrCopy = (source, target) => {
  try {
    fs.linkSync(source, target);
  } catch (error) {
    if (!['EXDEV', 'EPERM', 'ENOTSUP'].includes(error.code)) throw error;
    fs.copyFileSync(source, target);
  }
};

/** Prints `message` and exits with status 1. */
export function fail(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}
