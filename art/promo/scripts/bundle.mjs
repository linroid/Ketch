// Bundles the project once into <outDir> (public/ symlinked, so delete it after use) and prints
// the directory; `npx remotion render <outDir> <Comp> …` then renders without bundling again,
// with the settings in remotion.config.ts. Used by render-film.sh.
//
//   node scripts/bundle.mjs <outDir>
import fs from 'node:fs';
import {bundleProject} from './lib/remotion.mjs';

const [outDir] = process.argv.slice(2);
if (!outDir || outDir.startsWith('-')) {
  console.error('usage: node scripts/bundle.mjs <outDir>');
  process.exit(2);
}
fs.mkdirSync(outDir, {recursive: true});
console.log(await bundleProject({outDir}));
