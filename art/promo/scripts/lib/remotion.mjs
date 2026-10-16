// Helpers shared by the render scripts (stills.mjs, bundle.mjs, render-film.sh through
// bundle.mjs). Plain Node, no TypeScript: run from anywhere, paths resolve from this file.
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

/** art/promo, the Remotion project. */
export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** `require` resolving from the project's node_modules (Remotion's Node APIs are CommonJS). */
export const requireFromRoot = createRequire(path.join(root, 'package.json'));

/** The default locale, whose compositions have bare ids (`Promo`): DEFAULT_LOCALE in locale.tsx. */
export const DEFAULT_LOCALE = 'en';

/** Composition id in a locale: `Promo` → `Promo-zh-Hans` (localizedId in src/locale.tsx). */
export const localizedId = (id, locale) => (locale === DEFAULT_LOCALE ? id : `${id}-${locale}`);

/**
 * The films' themes (FILM_THEMES in src/theme-context.tsx): the dark film's compositions have
 * bare ids, the others' end in `-<theme>` after the locale (`Promo-zh-Hans-light`).
 */
export const THEMES = ['dark', 'light'];
export const DEFAULT_THEME = 'dark';

/** Composition id in a theme: `Promo-zh-Hans` → `Promo-zh-Hans-light` (themedId in src). */
export const themedId = (id, theme) => (theme === DEFAULT_THEME ? id : `${id}-${theme}`);

/** A composition id without its theme's suffix: `Promo-zh-Hans-light` → `Promo-zh-Hans`. */
export const unthemedId = (id) => {
  const theme = THEMES.find((t) => t !== DEFAULT_THEME && id.endsWith(`-${t}`));
  return theme ? id.slice(0, -(theme.length + 1)) : id;
};

/**
 * Locales a bundle registers, read from its composition ids (`Promo`, `Promo-<locale>`, and
 * the same with a theme's suffix), so src/locale.tsx stays the only list of locales. The other
 * rates' films (`Promo-60`, `Promo-60-<locale>`) are left out: a locale tag starts with a
 * letter.
 */
export const localesOf = (ids) => [
  ...new Set(
    ids
      .map(unthemedId)
      .flatMap((id) =>
        id === 'Promo'
          ? [DEFAULT_LOCALE]
          : /^Promo-[A-Za-z]/.test(id)
            ? [id.slice('Promo-'.length)]
            : [],
      ),
  ),
];

/**
 * Bundles src/index.ts once. public/ is symlinked into the bundle instead of copied (the clips
 * are hundreds of MB per locale), so the bundle only works on this machine and must be deleted
 * after use. Returns the bundle directory (`outDir` when given, else a temp folder).
 */
export const bundleProject = async ({outDir, quiet = false} = {}) => {
  const {bundle} = requireFromRoot('@remotion/bundler');
  let last = -1;
  return bundle({
    entryPoint: path.join(root, 'src/index.ts'),
    rootDir: root,
    publicDir: path.join(root, 'public'),
    symlinkPublicDir: true,
    ...(outDir ? {outDir} : {}),
    onProgress: (p) => {
      const step = Math.floor(p / 25);
      if (!quiet && step !== last) {
        last = step;
        process.stderr.write(`bundling ${p}%\n`);
      }
    },
  });
};
