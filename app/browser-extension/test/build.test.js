import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, test } from 'node:test';
import { build, releaseVersion } from '../build.mjs';

describe('releaseVersion', () => {
  test('a pre-release keeps its numeric part and adds the build number', () => {
    assert.deepEqual(releaseVersion('0.0.1-rc12', '57'), {
      version: '0.0.1.57',
      versionName: '0.0.1-rc12',
    });
  });

  test('without a build number a release keeps three parts', () => {
    assert.deepEqual(releaseVersion('1.2.3'), { version: '1.2.3', versionName: '1.2.3' });
  });

  test('rejects versions browsers cannot use', () => {
    assert.throws(() => releaseVersion('v1.2.3'), /not a version/);
    assert.throws(() => releaseVersion('1.2'), /not a version/);
    assert.throws(() => releaseVersion('1.02.3'), /0 to 65535/);
    assert.throws(() => releaseVersion('1.2.3', '70000'), /0 to 65535/);
  });
});

describe('build', () => {
  const outDir = mkdtempSync(join(tmpdir(), 'ketch-extension-build-'));
  after(() => rmSync(outDir, { recursive: true, force: true }));
  const locales = readdirSync(new URL('../src/_locales/', import.meta.url));
  const outputs = build({ outDir });

  test('builds for Chromium and Firefox', () => {
    assert.deepEqual(outputs.map((it) => it.browser), ['chrome', 'firefox']);
  });

  test('every build and zip has the messages of every language', () => {
    assert.ok(locales.includes('en'));
    for (const { browser, dir, zip } of outputs) {
      const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
      assert.equal(manifest.default_locale, 'en', browser);
      assert.equal(manifest.name, '__MSG_extension_name__', browser);
      const packaged = readFileSync(zip);
      for (const locale of locales) {
        const path = `_locales/${locale}/messages.json`;
        assert.equal(
          readFileSync(join(dir, path), 'utf8'),
          readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8'),
          `${browser} ${path}`,
        );
        assert.ok(packaged.includes(Buffer.from(path)), `${browser} zip lacks ${path}`);
      }
    }
  });
});
