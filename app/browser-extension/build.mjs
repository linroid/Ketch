#!/usr/bin/env node
/**
 * Builds the extension for each browser family from `src/`:
 *
 *   build/chrome/    Chrome, Edge, Brave, Opera, Vivaldi and other Chromium browsers
 *   build/firefox/   Firefox
 *   build/ketch-<browser>-<version>.zip   packages for the extension stores
 *
 * `src/` itself is the Chromium build, so it can be loaded unpacked while developing.
 */
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync }
  from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { crc32, deflateRawSync } from 'node:zlib';

const root = dirname(fileURLToPath(import.meta.url));
const src = join(root, 'src');
const out = join(root, 'build');

/** Firefox needs a stable add-on id; it can never change once published on addons.mozilla.org. */
const GECKO_ID = 'ketch@linroid.github.io';

const { version } = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const manifest = JSON.parse(readFileSync(join(src, 'manifest.json'), 'utf8'));
if (manifest.version !== version) {
  throw new Error(`src/manifest.json has version ${manifest.version}, package.json ${version}`);
}

const targets = {
  chrome: manifest,
  firefox: firefoxManifest(manifest),
};

/**
 * Firefox runs Manifest V3 background scripts as an event page rather than a service worker,
 * and identifies the add-on by its gecko id.
 */
function firefoxManifest(base) {
  const { background, minimum_chrome_version: _, ...rest } = base;
  return {
    ...rest,
    background: { scripts: [background.service_worker], type: background.type },
    browser_specific_settings: {
      gecko: {
        id: GECKO_ID,
        strict_min_version: '128.0',
        // Data only goes to Ketch servers the user adds, never to the developer.
        data_collection_permissions: { required: ['none'] },
      },
    },
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const [browser, browserManifest] of Object.entries(targets)) {
    const dir = join(out, browser);
    const zip = join(out, `ketch-${browser}-${version}.zip`);
    // Only this build's own outputs, so test reports in build/ survive.
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    cpSync(src, dir, { recursive: true, filter: (path) => !path.endsWith('.DS_Store') });
    writeFileSync(join(dir, 'manifest.json'), `${JSON.stringify(browserManifest, null, 2)}\n`);
    checkReferencedFiles(dir, browserManifest);
    writeFileSync(zip, zipDirectory(dir));
    console.log(`Built ${relative(root, dir)} and ${relative(root, zip)}`);
  }
}

/** Fails the build when the manifest points at a file that does not exist. */
function checkReferencedFiles(dir, browserManifest) {
  const files = [
    ...Object.values(browserManifest.icons ?? {}),
    ...Object.values(browserManifest.action?.default_icon ?? {}),
    browserManifest.action?.default_popup,
    browserManifest.options_ui?.page,
    browserManifest.background?.service_worker,
    ...(browserManifest.background?.scripts ?? []),
    ...(browserManifest.content_scripts ?? []).flatMap((script) => script.js ?? []),
  ].filter(Boolean);
  const missing = files.filter((file) => !existsSync(join(dir, file)));
  if (missing.length > 0) throw new Error(`Missing files: ${missing.join(', ')}`);
}

/** Writes a deflated zip with fixed timestamps, so identical sources give identical packages. */
function zipDirectory(dir) {
  const DOS_DATE_1980_01_01 = (1 << 5) | 1;
  const UTF8_NAMES = 0x0800;
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  const paths = listFiles(dir).sort();
  for (const path of paths) {
    const name = Buffer.from(relative(dir, path).split(sep).join('/'), 'utf8');
    const data = readFileSync(path);
    const compressed = deflateRawSync(data, { level: 9 });
    const checksum = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(UTF8_NAMES, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(DOS_DATE_1980_01_01, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, compressed);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(UTF8_NAMES, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(DOS_DATE_1980_01_01, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);

    offset += local.length + name.length + compressed.length;
  }
  const centralSize = centralParts.reduce((size, part) => size + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(paths.length, 8);
  end.writeUInt16LE(paths.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...localParts, ...centralParts, end]);
}

function listFiles(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? listFiles(path) : [path];
  });
}
