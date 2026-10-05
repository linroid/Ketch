import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { captureDecision, hostMatches } from '../src/lib/intercept.js';
import { normalizeSettings } from '../src/lib/settings.js';

const EXTENSION_ID = 'ketch-extension';
const settings = normalizeSettings({
  instances: [
    { id: 'local', url: 'http://127.0.0.1:8642' },
    { id: 'nas', url: 'https://nas.example.com/ketch' },
  ],
  excludedHosts: ['example.org'],
  minFileSizeMb: 1,
});

function download(overrides = {}) {
  return { url: 'https://files.example.net/big.iso', state: 'in_progress', ...overrides };
}

function decide(item, custom = settings) {
  return captureDecision(item, custom, EXTENSION_ID);
}

describe('hostMatches', () => {
  test('matches the host and its subdomains but not look-alikes', () => {
    assert.ok(hostMatches('example.org', ['example.org']));
    assert.ok(hostMatches('DL.Example.org', ['example.org']));
    assert.equal(hostMatches('notexample.org', ['example.org']), false);
  });
});

describe('captureDecision', () => {
  test('captures an ordinary download of unknown size', () => {
    assert.deepEqual(decide(download({ totalBytes: -1 })), { capture: true, reason: '' });
  });

  test('skips everything when capturing is off', () => {
    const off = { ...settings, interceptDownloads: false };
    assert.equal(decide(download(), off).capture, false);
  });

  test('skips its own downloads and private windows', () => {
    assert.equal(decide(download({ byExtensionId: EXTENSION_ID })).capture, false);
    assert.equal(decide(download({ incognito: true })).capture, false);
  });

  test('leaves downloads started by other extensions to their owner', () => {
    assert.equal(decide(download({ byExtensionId: 'other-extension' })).capture, false);
  });

  test('skips downloads that are no longer running', () => {
    assert.equal(decide(download({ state: 'complete' })).capture, false);
  });

  test('skips links that only exist in the browser', () => {
    assert.equal(decide(download({ url: 'blob:https://a.com/1' })).capture, false);
    assert.equal(decide(download({ url: 'data:text/plain,hello' })).capture, false);
  });

  test('skips files served by a configured Ketch instance', () => {
    assert.equal(decide(download({ url: 'http://127.0.0.1:8642/files/a.zip' })).capture, false);
    assert.equal(decide(download({ url: 'https://nas.example.com/other' })).capture, false);
    assert.equal(decide(download({ url: 'http://127.0.0.1:9000/a.zip' })).capture, true);
  });

  test('skips excluded sites, judged by the final URL after redirects', () => {
    assert.equal(decide(download({ url: 'https://cdn.example.org/a.zip' })).capture, false);
    const redirected = download({
      url: 'https://example.net/get',
      finalUrl: 'https://cdn.example.org/a.zip',
    });
    assert.equal(decide(redirected).capture, false);
  });

  test('skips files below the minimum size and captures ones at it', () => {
    assert.equal(decide(download({ totalBytes: 1024 * 1024 - 1 })).capture, false);
    assert.equal(decide(download({ totalBytes: 1024 * 1024 })).capture, true);
    assert.equal(decide(download({ totalBytes: 0, fileSize: 10 })).capture, false);
  });

  test('captures .torrent files whatever their size', () => {
    const tiny = { totalBytes: 144 };
    assert.equal(decide(download({ ...tiny, mime: 'application/x-bittorrent' })).capture, true);
    assert.equal(decide(download({ ...tiny, filename: '/tmp/ubuntu.torrent' })).capture, true);
    assert.equal(decide(download({ ...tiny, url: 'https://a.com/ubuntu.torrent' })).capture, true);
  });
});

test('site exclusions follow the page and original URL when files use a CDN', () => {
  const url = 'https://cdn.example.net/file.iso';
  assert.equal(decide(download({ url, referrer: 'https://example.org/page' })).capture, false);
  assert.equal(decide(download({ url, pageUrl: 'https://sub.example.org/page' })).capture, false);
  assert.equal(decide(download({ url: 'https://example.org/get', finalUrl: url })).capture, false);
});

test('known POST requests and saved documents remain browser-owned', () => {
  assert.equal(decide(download({ requestMethod: 'POST' })).capture, false);
  assert.equal(decide(download({ filename: 'page.mhtml' })).capture, false);
  assert.equal(decide(download({ mime: 'text/html; charset=utf-8' })).capture, false);
});

test('file type rules match multipart suffixes and let only-mode exclude unknown types', () => {
  const only = { ...settings, fileTypeMode: 'only', fileExtensions: ['tar.gz'] };
  assert.equal(decide(download({ filename: 'archive.TAR.GZ' }), only).capture, true);
  assert.equal(decide(download({ filename: 'archive.gz' }), only).capture, false);
  assert.equal(decide(download({ url: 'https://example.net/get' }), only).capture, false);
  assert.equal(decide(download({ filename: 'archive.tar.gz' }),
    { ...only, fileTypeMode: 'except' }).capture, false);
});

test('unknown-size preference does not prevent tiny torrent handoffs', () => {
  const configured = { ...settings, captureUnknownSize: false };
  assert.equal(decide(download({ totalBytes: -1 }), configured).capture, false);
  assert.equal(decide(download({ totalBytes: -1, mime: 'application/x-bittorrent' }),
    configured).capture, true);
});
