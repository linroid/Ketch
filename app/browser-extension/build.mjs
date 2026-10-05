#!/usr/bin/env node
/**
 * Builds the extension for each browser family from `src/`:
 *
 *   build/chrome/    Chrome, Edge, Brave, Opera, Vivaldi and other Chromium browsers
 *   build/firefox/   Firefox
 *   build/ketch-extension-<version>-<browser>.zip   packages for releases and extension stores
 *
 * Usage: node build.mjs [--version <release version>] [--build <number>]
 *
 * Without options the version comes from package.json. The release workflow passes the tag's
 * version and its run number; see `releaseVersion`.
 *
 * `src/` itself is the Chromium build, so it can be loaded unpacked while developing.
 */
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync }
  from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
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

/**
 * Versions for the manifests of a release. Browsers accept only one to four dot-separated
 * numbers, so a pre-release such as `0.0.1-rc12` keeps its numeric part. `build`, the release
 * workflow's run number, becomes the fourth part, so every release counts as newer than the one
 * before, including a final release after its release candidates. Chromium shows `versionName`
 * to users instead.
 *
 * @param {string} release a semantic version, such as `1.2.3` or `0.0.1-rc12`
 * @param {string | number} [build]
 * @returns {{ version: string, versionName: string }}
 */
export function releaseVersion(release, build) {
  const match = /^(\d+\.\d+\.\d+)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.exec(release);
  if (!match) throw new Error(`"${release}" is not a version such as 1.2.3 or 1.2.3-rc1`);
  const parts = match[1].split('.');
  if (build !== undefined) parts.push(String(build));
  for (const part of parts) {
    if (!/^(0|[1-9]\d*)$/.test(part) || Number(part) > 65535) {
      throw new Error(`Version part "${part}" must be a number from 0 to 65535`);
    }
  }
  return { version: parts.join('.'), versionName: release };
}

/** @returns {Record<string, object>} the manifest for each browser build */
function targetManifests({ version: manifestVersion, versionName }) {
  const base = { ...manifest, version: manifestVersion };
  const chrome = versionName === manifestVersion ? base : { ...base, version_name: versionName };
  return { chrome, firefox: firefoxManifest(base), safari: safariManifest(base) };
}

/**
 * Firefox runs Manifest V3 background scripts as an event page rather than a service worker,
 * and identifies the add-on by its gecko id rather than by the Chromium `key`.
 */
function firefoxManifest(base) {
  const { background, key: _key, minimum_chrome_version: _version, ...rest } = base;
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

/** Safari reaches configured servers; native host launching and capture are not included. */
function safariManifest(base) {
  const { key: _key, minimum_chrome_version: _minimum, ...rest } = base;
  return {
    ...rest,
    options_ui: { page: base.options_ui.page },
    permissions: base.permissions.filter((permission) =>
      !['downloads', 'nativeMessaging', 'notifications', 'webRequest'].includes(permission)),
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({
    options: { version: { type: 'string' }, build: { type: 'string' } },
  });
  for (const { dir, zip } of build({ release: values.version, buildNumber: values.build })) {
    console.log(`Built ${relative(root, dir)} and ${relative(root, zip)}`);
  }
}

/**
 * Builds a directory and a zip for each browser.
 *
 * @param {{ outDir?: string, release?: string, buildNumber?: string }} [options] `outDir`
 *   defaults to `build/`; `release` to the version in package.json
 * @returns {{ browser: string, dir: string, zip: string }[]}
 */
export function build({ outDir = out, release = version, buildNumber } = {}) {
  const versions = releaseVersion(release, buildNumber);
  return Object.entries(targetManifests(versions)).map(([browser, browserManifest]) => {
    const dir = join(outDir, browser);
    const zip = join(outDir, `ketch-extension-${versions.versionName}-${browser}.zip`);
    // Only this build's own outputs, so test reports in build/ survive.
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    cpSync(src, dir, { recursive: true, filter: (path) => !path.endsWith('.DS_Store') });
    writeFileSync(join(dir, 'manifest.json'), `${JSON.stringify(browserManifest, null, 2)}\n`);
    checkReferencedFiles(dir, browserManifest);
    checkLocales(dir, browserManifest);
    writeFileSync(zip, zipDirectory(dir));
    return { browser, dir, zip };
  });
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

/**
 * Fails the build when the manifest's messages can't be found: browsers refuse to load an
 * extension whose `default_locale` has no `messages.json`, or that names a missing message.
 */
function checkLocales(dir, browserManifest) {
  const locale = browserManifest.default_locale;
  const file = join(dir, '_locales', locale ?? '', 'messages.json');
  if (!locale || !existsSync(file)) throw new Error(`Missing messages for locale "${locale}"`);
  const messages = JSON.parse(readFileSync(file, 'utf8'));
  const names = [...JSON.stringify(browserManifest).matchAll(/__MSG_(\w+)__/g)]
    .map((match) => match[1]);
  const missing = names.filter((name) => !Object.hasOwn(messages, name));
  if (missing.length > 0) throw new Error(`Missing messages: ${missing.join(', ')}`);
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
