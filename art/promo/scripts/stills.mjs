// Renders many stills with one bundle and one browser (much faster than repeated
// `remotion still`). public/ is symlinked into the bundle, which is deleted when done.
//
//   node scripts/stills.mjs [--locale <locale>|all] [--theme dark|light|all] [--scale <n>]
//     <outDir> <Comp>:<frame>[,…] […]
//
//   npm run stills -- out Promo:20,50,90 S08:0,120 Kit:70        English
//   npm run stills -- --locale zh-Hans out Promo:200 S04:60       Promo-zh-Hans, S04-zh-Hans
//   npm run stills -- --locale all out S10:60                     S10 and S10-zh-Hans
//   npm run stills -- --theme light out S10:60                    the light film's S10-light
//   npm run stills -- --scale 2 out Promo-60:901                  the 60 fps film at 4K
//
// Frames are the composition's own (global for Promo, local for a scene; at 60 fps, Promo-60,
// twice the design frames: Promo:450 is Promo-60:900). Files are named
// <outDir>/<compositionId>-<frame>.png. A spec may also name a localized or themed id directly
// (S08-zh-Hans:10, S08-light:10), which --locale and --theme leave alone. --theme picks the film
// (dark, the default; light; all for both). --scale renders at that multiple of 1920 × 1080
// (2: 3840 × 2160, as the 4K60 film).
import fs from 'node:fs';
import path from 'node:path';
import {
  THEMES,
  bundleProject,
  localesOf,
  localizedId,
  requireFromRoot,
  themedId,
  unthemedId,
} from './lib/remotion.mjs';

const USAGE =
  'usage: node scripts/stills.mjs [--locale <locale>|all] [--theme dark|light|all] ' +
  '[--scale <n>] <outDir> <Comp>:<frame>[,<frame>...] ...';

const args = process.argv.slice(2);
let locale = 'en';
const li = args.indexOf('--locale');
if (li >= 0) {
  locale = args[li + 1];
  args.splice(li, 2);
}
let theme = 'dark';
const ti = args.indexOf('--theme');
if (ti >= 0) {
  theme = args[ti + 1];
  args.splice(ti, 2);
}
const themes = theme === 'all' ? THEMES : [theme];
let scale = 1;
const si = args.indexOf('--scale');
if (si >= 0) {
  scale = Number(args[si + 1]);
  args.splice(si, 2);
}
const [outDir, ...specs] = args;
const badSpec = specs.some((s) => !/^[\w-]+:\d+(,\d+)*$/.test(s));
const badTheme = themes.some((t) => !THEMES.includes(t));
if (!locale || !outDir || specs.length === 0 || badSpec || badTheme || !(scale > 0)) {
  console.error(USAGE);
  process.exit(2);
}

const {renderStill, selectComposition, getCompositions, openBrowser} =
  requireFromRoot('@remotion/renderer');

/**
 * The page's warnings and errors, each once: Root warns when a clip is shorter than the scenes
 * that play it, and scenes log what goes wrong.
 */
const seen = new Set();
const onBrowserLog = ({type, text}) => {
  if ((type === 'warning' || type === 'error') && !seen.has(text)) {
    seen.add(text);
    console.warn(`[${type}] ${text}`);
  }
};

fs.mkdirSync(outDir, {recursive: true});
const serveUrl = await bundleProject({quiet: true});
const browser = await openBrowser('chrome', {chromiumOptions: {gl: 'angle'}});
try {
  const ids = (await getCompositions(serveUrl, {puppeteerInstance: browser, onBrowserLog})).map(
    (c) => c.id,
  );
  const known = localesOf(ids);
  const locales = locale === 'all' ? known : [locale];
  const unknown = locales.filter((l) => !known.includes(l));
  if (unknown.length > 0)
    throw new Error(`Unknown locale ${unknown.join(', ')}; the film has ${known.join(', ')}`);
  for (const spec of specs) {
    const [base, frames] = spec.split(':');
    // An id that already names a locale or a theme (S08-zh-Hans, S08-light) is rendered as
    // given.
    const named = unthemedId(base) !== base || known.some((l) => base.endsWith(`-${l}`));
    const targets = named
      ? [base]
      : themes.flatMap((t) => locales.map((l) => themedId(localizedId(base, l), t)));
    for (const id of targets) {
      if (!ids.includes(id)) throw new Error(`No composition ${id}`);
      const composition = await selectComposition({serveUrl, id, puppeteerInstance: browser});
      for (const f of frames.split(',')) {
        const output = path.join(outDir, `${id}-${f}.png`);
        await renderStill({
          composition,
          serveUrl,
          output,
          frame: Number(f),
          puppeteerInstance: browser,
          imageFormat: 'png',
          scale,
          onBrowserLog,
        });
        console.log(output);
      }
    }
  }
} finally {
  await browser.close({silent: true});
  fs.rmSync(serveUrl, {recursive: true, force: true});
}
