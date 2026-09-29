import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  DEFAULT_PORT,
  LOCAL_INSTANCE_ID,
  findInstance,
  isLoopbackUrl,
  normalizeServerUrl,
  normalizeSettings,
  parseHostList,
} from '../src/lib/settings.js';

describe('normalizeServerUrl', () => {
  test('bare host gets http and the default Ketch port', () => {
    assert.equal(normalizeServerUrl('nas.local'), `http://nas.local:${DEFAULT_PORT}`);
    assert.equal(normalizeServerUrl(' 192.168.1.20 '), `http://192.168.1.20:${DEFAULT_PORT}`);
    assert.equal(normalizeServerUrl('[::1]'), `http://[::1]:${DEFAULT_PORT}`);
  });

  test('bare host with a port keeps it', () => {
    assert.equal(normalizeServerUrl('nas.local:9000'), 'http://nas.local:9000');
  });

  test('explicit scheme keeps the scheme default port, for reverse proxies', () => {
    assert.equal(normalizeServerUrl('https://ketch.example.com'), 'https://ketch.example.com');
    assert.equal(normalizeServerUrl('https://example.com/ketch/'), 'https://example.com/ketch');
  });

  test('drops trailing slashes, query and fragment', () => {
    assert.equal(normalizeServerUrl('http://nas:8642///?x=1#y'), 'http://nas:8642');
  });

  test('rejects blank input, other schemes and embedded credentials', () => {
    assert.throws(() => normalizeServerUrl('  '), /Enter the address/);
    assert.throws(() => normalizeServerUrl('ftp://nas.local'), /http:\/\/ or https:\/\//);
    assert.throws(() => normalizeServerUrl('http://user:secret@nas.local'), /token field/);
    assert.throws(() => normalizeServerUrl('http://exa mple.com'), /not a valid address/);
  });
});

describe('isLoopbackUrl', () => {
  test('recognizes addresses on this computer', () => {
    assert.ok(isLoopbackUrl('http://127.0.0.1:8642'));
    assert.ok(isLoopbackUrl('http://127.8.9.10:8642'));
    assert.ok(isLoopbackUrl('http://localhost:8642'));
    assert.ok(isLoopbackUrl('http://ketch.localhost'));
    assert.ok(isLoopbackUrl('http://[::1]:8642'));
  });

  test('treats other hosts and invalid input as remote', () => {
    assert.equal(isLoopbackUrl('http://192.168.1.20:8642'), false);
    assert.equal(isLoopbackUrl('http://127.0.0.1.example.com'), false);
    assert.equal(isLoopbackUrl('not a url'), false);
  });
});

describe('parseHostList', () => {
  test('accepts pasted URLs, wildcards and ports, one per line or comma separated', () => {
    assert.deepEqual(
      parseHostList('https://Example.com/files?id=1\n*.cdn.net:443, mirror.org.'),
      ['example.com', 'cdn.net', 'mirror.org'],
    );
  });

  test('drops blanks and duplicates', () => {
    assert.deepEqual(parseHostList(['a.com', '', 'A.com', ' b.com ']), ['a.com', 'b.com']);
  });
});

describe('normalizeSettings', () => {
  test('missing settings start with Ketch on this computer as the default', () => {
    const settings = normalizeSettings(undefined);
    assert.equal(settings.instances.length, 1);
    assert.equal(settings.instances[0].id, LOCAL_INSTANCE_ID);
    assert.equal(settings.instances[0].url, `http://127.0.0.1:${DEFAULT_PORT}`);
    assert.equal(settings.defaultInstanceId, LOCAL_INSTANCE_ID);
    assert.equal(settings.interceptDownloads, true);
  });

  test('drops instances without an id or a valid address, and duplicate ids', () => {
    const settings = normalizeSettings({
      instances: [
        { id: 'nas', name: 'NAS', url: 'nas.local' },
        { id: '', url: 'http://a' },
        { id: 'broken', url: 'ftp://b' },
        { id: 'nas', name: 'Duplicate', url: 'http://c' },
      ],
    });
    assert.deepEqual(settings.instances, [
      { id: 'nas', name: 'NAS', url: `http://nas.local:${DEFAULT_PORT}`, token: '' },
    ]);
  });

  test('falls back to the local instance when every instance is invalid', () => {
    const settings = normalizeSettings({ instances: [{ id: 'x', url: '' }] });
    assert.deepEqual(settings.instances.map((it) => it.id), [LOCAL_INSTANCE_ID]);
  });

  test('an unknown default falls back to the first instance', () => {
    const settings = normalizeSettings({
      instances: [{ id: 'a', url: 'http://a:1' }, { id: 'b', url: 'http://b:1' }],
      defaultInstanceId: 'removed',
    });
    assert.equal(settings.defaultInstanceId, 'a');
  });

  test('a blank name becomes the server host', () => {
    const settings = normalizeSettings({ instances: [{ id: 'a', name: ' ', url: 'nas:9' }] });
    assert.equal(settings.instances[0].name, 'nas:9');
  });

  test('repairs invalid preferences and keeps valid ones', () => {
    const settings = normalizeSettings({
      interceptDownloads: false,
      forwardCookies: 'yes',
      minFileSizeMb: -4,
      excludedHosts: 'https://A.com/x',
    });
    assert.equal(settings.interceptDownloads, false);
    assert.equal(settings.forwardCookies, true);
    assert.equal(settings.minFileSizeMb, 0);
    assert.deepEqual(settings.excludedHosts, ['a.com']);
  });
});

describe('findInstance', () => {
  const settings = normalizeSettings({
    instances: [{ id: 'a', url: 'http://a:1' }, { id: 'b', url: 'http://b:1' }],
    defaultInstanceId: 'b',
  });

  test('returns the requested instance', () => {
    assert.equal(findInstance(settings, 'a').id, 'a');
  });

  test('returns the default for a missing or unknown id', () => {
    assert.equal(findInstance(settings).id, 'b');
    assert.equal(findInstance(settings, 'removed').id, 'b');
  });
});
